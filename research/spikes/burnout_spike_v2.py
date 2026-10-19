"""
BURNOUT SPIKE — PROMPT v2

WHAT CHANGED FROM v1, AND WHY

  Problem seen in v1: the models OVER-TAG. gemma3:4b flagged 'mental' just
  because someone slept badly, and flagged 'emotional' on any work complaint.
  Precision was 76% — meaning a quarter of what it flagged wasn't really there.

  Three fixes, in order of how much they should help:

  FIX 1 — NEGATIVE EXAMPLES (in the prompt)
      v1 showed the model 3 examples, only one of which was a "good day".
      v2 shows it several good/neutral days, and — crucially — days that
      MENTION work or sleep but are NOT burnout. Small models pattern-match on
      keywords; you beat that by showing them the keyword WITHOUT the label.

  FIX 2 — AN EXPLICIT "EVIDENCE" RULE
      v2 makes the model justify itself: for every dimension it flags, it must
      quote the words from the text that prove it. Forcing a model to cite its
      evidence makes it much more reluctant to invent things.

  FIX 3 — A CONFIDENCE FLOOR (in code, not in the model)
      Anything the model flags below --floor confidence gets thrown away by
      Python before it ever reaches the score.

SESSION 14 ADDITION — CLOUD PROVIDERS
  Same harness, same scoring, now also supports calling Gemini or Groq's
  real APIs instead of a local Ollama model — so cloud options can be
  compared on the exact same 40 cases, apples-to-apples with every local
  model tested so far.

  API keys are read from environment variables, NOT command-line args —
  deliberately, so they never appear in a pasted terminal screenshot.
  Set them once per terminal session before running:

      set GEMINI_API_KEY=your-key-here
      set GROQ_API_KEY=your-key-here

HOW TO RUN
  python burnout_spike_v2.py --mock --limit 8
  python burnout_spike_v2.py --provider ollama --model gemma3:4b --out v2_4b.csv
  python burnout_spike_v2.py --provider gemini --model gemini-2.5-flash --out v2_gemini.csv
  python burnout_spike_v2.py --provider groq --model llama-3.3-70b-versatile --out v2_groq.csv
  python burnout_spike_v2.py --provider gemini --model gemini-2.5-flash --sweep
"""

import argparse
import csv
import json
import os
import random
import re
import sys
import time

import requests

OLLAMA_URL = "http://localhost:11434/api/generate"
GEMINI_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
DIMENSIONS = ["physical", "mental", "emotional", "social", "professional", "personal"]
MAX_RETRIES = 2


# ---------------------------------------------------------------------------
# PROMPT v2 (unchanged)
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """You are a strict labelling tool for a wellbeing app. You do not diagnose, advise, or chat.

You read a short description of someone's day and identify ONLY the types of depletion for which the text gives DIRECT evidence.

THE SIX TYPES
- physical: tiredness, poor sleep, aches, illness, appetite loss, bodily depletion
- mental: concentration problems, brain fog, forgetfulness, decision fatigue, inability to switch off
- emotional: low mood, irritability, tearfulness, numbness, feeling drained or overwhelmed
- social: withdrawing from people, loneliness, dreading contact, cancelling on others
- professional: work exhaustion, dread of work, cynicism about work, feeling ineffective at work
- personal: no time or energy for one's own life, lost hobbies, lost meaning or identity outside work

THE EVIDENCE RULE — THIS IS THE MOST IMPORTANT RULE
For every type you flag, you MUST quote the exact words from the text that prove it.
If you cannot quote proof, DO NOT FLAG IT. No exceptions.

WHAT IS NOT EVIDENCE
- Mentioning work is NOT professional depletion. A normal day at work is just a day at work.
- Mentioning sleep is NOT physical depletion unless the sleep was bad or they are tired.
- Mentioning people is NOT social depletion unless they withdrew, dreaded contact, or felt lonely.
- Being tired is NOT automatically mental depletion. Only flag mental if focus/memory/thinking is mentioned.
- Do not infer a second type just because it often accompanies the first. Flag only what is written.

NEUTRAL AND GOOD DAYS
Many days are fine. If the text shows no depletion, return an empty list and primary "none".
Do not hunt for problems that are not there.

SCORES
- intensity: 0.0-1.0, how severe the depletion appears
- confidence: 0.0-1.0, how certain you are the evidence really supports it. Be honest. Use low
  values when you are guessing.

Output ONLY a JSON object. No explanation, no markdown, no backticks.

OUTPUT SCHEMA
{"dimensions": [{"dimension": "<one of the six>", "evidence": "<exact words from the text>", "intensity": <0.0-1.0>, "confidence": <0.0-1.0>}], "primary": "<one of the six, or none>"}

EXAMPLES

Input: "Worked until midnight for the third night. My eyes are burning and I couldn't focus by the end."
Output: {"dimensions": [{"dimension": "professional", "evidence": "Worked until midnight for the third night", "intensity": 0.8, "confidence": 0.9}, {"dimension": "physical", "evidence": "My eyes are burning", "intensity": 0.7, "confidence": 0.8}, {"dimension": "mental", "evidence": "couldn't focus by the end", "intensity": 0.6, "confidence": 0.8}], "primary": "professional"}

Input: "Turned down two invitations this week. I'd rather just be by myself."
Output: {"dimensions": [{"dimension": "social", "evidence": "Turned down two invitations", "intensity": 0.7, "confidence": 0.8}], "primary": "social"}

Input: "Slept well, finished my work on time, went for a run in the evening."
Output: {"dimensions": [], "primary": "none"}

Input: "Busy day at the office, lots of meetings, but we shipped the release and I felt good about it."
Output: {"dimensions": [], "primary": "none"}

Input: "Had dinner with friends, got home late so I'm a bit sleepy, but it was worth it."
Output: {"dimensions": [], "primary": "none"}

Input: "Long day at work. Came home, cooked, watched an episode, going to bed."
Output: {"dimensions": [], "primary": "none"}

Input: "Slept badly again, maybe five hours, and I've been yawning all day."
Output: {"dimensions": [{"dimension": "physical", "evidence": "Slept badly again, maybe five hours", "intensity": 0.6, "confidence": 0.9}], "primary": "physical"}
"""


def build_user_prompt(text):
    return f'Input: "{text}"\nOutput:'


# ---------------------------------------------------------------------------
# CALLING THE MODEL — one function per provider
# ---------------------------------------------------------------------------

def call_ollama(model, text):
    payload = {
        "model": model,
        "system": SYSTEM_PROMPT,
        "prompt": build_user_prompt(text),
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.0, "num_predict": 400},
    }
    r = requests.post(OLLAMA_URL, json=payload, timeout=300)
    r.raise_for_status()
    return r.json()["response"]


def call_gemini(model, text):
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("\nERROR: GEMINI_API_KEY not set. Run:  set GEMINI_API_KEY=your-key")
        sys.exit(1)

    # Query-param auth (?key=...), confirmed working directly against this
    # account via a real curl call to the models-list endpoint. The header
    # form (x-goog-api-key) is what produced the earlier 404 — switched
    # to match the method actually proven to work.
    url = GEMINI_URL_TEMPLATE.format(model=model) + f"?key={api_key}"
    payload = {
        "systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": [{"parts": [{"text": build_user_prompt(text)}]}],
        "generationConfig": {
            "temperature": 0.0,
            "maxOutputTokens": 400,
            "responseMimeType": "application/json",
        },
    }
    r = requests.post(url, json=payload, timeout=120)
    r.raise_for_status()
    data = r.json()
    return data["candidates"][0]["content"]["parts"][0]["text"]


def call_groq(model, text):
    api_key = os.environ.get("GROQ_API_KEY")
    if not api_key:
        print("\nERROR: GROQ_API_KEY not set. Run:  set GROQ_API_KEY=your-key")
        sys.exit(1)

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(text)},
        ],
        "temperature": 0.0,
        "max_tokens": 400,
        "response_format": {"type": "json_object"},
    }
    r = requests.post(
        GROQ_URL, json=payload,
        headers={"Authorization": f"Bearer {api_key}"}, timeout=120,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


def fake_model(text):
    """Keyword baseline — the thing every real model must beat."""
    keywords = {
        "physical": ["slept", "sleep", "tired", "exhaust", "ache", "headache", "sick", "yawn", "appetite"],
        "mental": ["focus", "concentrat", "forgot", "brain", "fog", "zoned", "decision", "switch off"],
        "emotional": ["cried", "tears", "angry", "irritab", "snapped", "low", "numb", "flat", "guilty"],
        "social": ["friend", "invit", "alone", "lonely", "people", "cancel", "flatmate", "party"],
        "professional": ["work", "meeting", "manager", "deadline", "office", "client", "team"],
        "personal": ["hobby", "guitar", "paint", "myself", "my own", "resent"],
    }
    low = text.lower()
    found = [{"dimension": d, "evidence": "", "intensity": 0.7,
              "confidence": round(random.uniform(0.4, 0.9), 2)}
             for d, ws in keywords.items() if any(w in low for w in ws)]
    return json.dumps({"dimensions": found,
                       "primary": found[0]["dimension"] if found else "none"})


# ---------------------------------------------------------------------------
# PARSING + THE CONFIDENCE FLOOR (unchanged)
# ---------------------------------------------------------------------------

def parse_reply(raw):
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass
    cleaned = raw.replace("```json", "").replace("```", "").strip()
    m = re.search(r"\{.*\}", cleaned, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(0))
        except json.JSONDecodeError:
            return None
    return None


def normalise(parsed, floor):
    """
    Cleans the model's answer AND applies the confidence floor.

    'floor' is the key new lever: any dimension the model flagged with
    confidence below this number is DISCARDED. Raising the floor raises
    precision (fewer false flags) but lowers recall (more misses). The --sweep
    option finds the sweet spot for you.
    """
    if not isinstance(parsed, dict):
        return None

    kept = set()
    dropped = set()
    evidence = {}

    for e in parsed.get("dimensions", []) or []:
        if isinstance(e, dict):
            name = str(e.get("dimension", "")).strip().lower()
            try:
                conf = float(e.get("confidence", 1.0))
            except (TypeError, ValueError):
                conf = 1.0
            ev = str(e.get("evidence", ""))[:60]
        else:
            name, conf, ev = str(e).strip().lower(), 1.0, ""

        if name not in DIMENSIONS:
            continue
        if conf >= floor:
            kept.add(name)
            evidence[name] = ev
        else:
            dropped.add(name)

    primary = str(parsed.get("primary", "none")).strip().lower()
    if primary not in DIMENSIONS + ["none"]:
        primary = "none"
    if primary != "none" and primary not in kept:
        primary = "none"

    return {"dims": kept, "primary": primary, "dropped": dropped, "evidence": evidence}


def extract(provider, model, text, mock, floor):
    caller = {
        "ollama": call_ollama,
        "gemini": call_gemini,
        "groq": call_groq,
    }[provider]

    for _ in range(MAX_RETRIES + 1):
        try:
            raw = fake_model(text) if mock else caller(model, text)
        except requests.exceptions.ConnectionError:
            print(f"\nERROR: {provider} not reachable. If using Ollama, run:  ollama serve")
            sys.exit(1)
        except requests.exceptions.HTTPError as exc:
            print(f"  ! HTTP error from {provider}: {exc}; retrying")
            continue
        except Exception as exc:
            print(f"  ! call failed ({exc}); retrying")
            continue
        result = normalise(parse_reply(raw), floor)
        if result is not None:
            return result, raw
    return None, ""


# ---------------------------------------------------------------------------
# SCORING (unchanged)
# ---------------------------------------------------------------------------

def evaluate(rows_raw, floor):
    """Re-scores already-collected raw model replies at a given floor.
    This is what makes --sweep cheap: we call the model ONCE, then try
    several floors against the same stored replies."""
    hits = misses = extras = 0
    primary_ok = 0
    none_correct = 0
    none_total = 0
    scored = 0

    for r in rows_raw:
        got = normalise(parse_reply(r["raw"]), floor)
        if got is None:
            continue
        scored += 1
        exp = set(r["expected_dims"])
        hits += len(exp & got["dims"])
        misses += len(exp - got["dims"])
        extras += len(got["dims"] - exp)
        if got["primary"] == r["primary"]:
            primary_ok += 1
        if r["primary"] == "none":
            none_total += 1
            if got["primary"] == "none" and not got["dims"]:
                none_correct += 1

    p = hits / (hits + extras) if (hits + extras) else 0
    rec = hits / (hits + misses) if (hits + misses) else 0
    f1 = 2 * p * rec / (p + rec) if (p + rec) else 0
    return {
        "floor": floor,
        "precision": p,
        "recall": rec,
        "f1": f1,
        "primary": primary_ok / scored if scored else 0,
        "good_day": none_correct / none_total if none_total else 0,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", choices=["ollama", "gemini", "groq"], default="ollama",
                    help="Which backend to call")
    ap.add_argument("--model", default="gemma3:4b",
                    help="Model name — depends on provider, e.g. gemini-2.5-flash, llama-3.3-70b-versatile")
    ap.add_argument("--mock", action="store_true")
    ap.add_argument("--cases", default="test_cases.json")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--floor", type=float, default=0.5,
                    help="Discard dimensions the model flagged below this confidence")
    ap.add_argument("--sweep", action="store_true",
                    help="Try several confidence floors against the same model replies")
    ap.add_argument("--out", default="spike_v2_results.csv")
    args = ap.parse_args()

    with open(args.cases) as f:
        cases = json.load(f)
    if args.limit:
        cases = cases[:args.limit]

    label = "MOCK" if args.mock else f"{args.provider}:{args.model}"
    print(f"\nPROMPT v2 — {len(cases)} cases against {label} (floor {args.floor})\n")

    raw_rows = []
    durations = []
    unusable = 0

    for c in cases:
        t0 = time.time()
        got, raw = extract(args.provider, args.model, c["text"], args.mock, args.floor)
        secs = time.time() - t0
        durations.append(secs)

        if got is None:
            unusable += 1
            print(f"[{c['id']:>2}] UNUSABLE")
            continue

        exp = set(c["expected_dims"])
        ok = (exp == got["dims"] and got["primary"] == c["primary"])
        partial = (got["primary"] == c["primary"]) or (exp == got["dims"])
        flag = "✓" if ok else ("~" if partial else "✗")
        verdict = "GOOD" if ok else ("PARTIAL" if partial else "POOR")

        drop_note = f" [floor dropped: {sorted(got['dropped'])}]" if got["dropped"] else ""
        print(f"[{c['id']:>2}] {flag} exp={sorted(exp) or ['none']} "
              f"got={sorted(got['dims']) or ['none']} "
              f"primary {c['primary']}→{got['primary']} ({secs:.1f}s){drop_note}")

        raw_rows.append({
            "id": c["id"], "text": c["text"],
            "expected_dims": c["expected_dims"], "primary": c["primary"],
            "raw": raw, "got_dims": "|".join(sorted(got["dims"])),
            "got_primary": got["primary"],
            "evidence": " ; ".join(f"{k}:{v}" for k, v in got["evidence"].items()),
            "seconds": round(secs, 1), "verdict": verdict,
        })

    if not raw_rows:
        print("No usable results.")
        return

    m = evaluate(raw_rows, args.floor)
    avg = sum(durations) / len(durations)
    print("\n" + "=" * 64)
    print(f"SCORECARD v2 — {label} @ floor {args.floor}")
    print("=" * 64)
    print(f"  JSON usable          {1 - unusable / len(cases):6.0%}")
    print(f"  Precision            {m['precision']:6.0%}   (v1 4b was 76%)")
    print(f"  Recall               {m['recall']:6.0%}   (v1 4b was 76%)")
    print(f"  F1                   {m['f1']:6.0%}   (v1 4b was 76%; keyword baseline 70%)")
    print(f"  Primary correct      {m['primary']:6.0%}   (v1 4b was 80%)")
    print(f"  Good days left alone {m['good_day']:6.0%}   (NEW — the over-tagging test)")
    print(f"  Avg seconds          {avg:6.1f}s")
    print("=" * 64)

    if args.sweep:
        print("\nCONFIDENCE FLOOR SWEEP (no extra model calls — reusing replies)")
        print(f"{'floor':>6} {'prec':>6} {'recall':>7} {'F1':>6} {'primary':>8} {'good days':>10}")
        for fl in [0.0, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8]:
            s = evaluate(raw_rows, fl)
            print(f"{fl:>6.1f} {s['precision']:>6.0%} {s['recall']:>7.0%} "
                  f"{s['f1']:>6.0%} {s['primary']:>8.0%} {s['good_day']:>10.0%}")
        print("\nPick the floor with the best F1 that still keeps recall reasonable.")
        print("Missing real burnout (low recall) is worse than a false flag.\n")

    fields = ["id", "text", "expected_dims", "primary", "got_dims", "got_primary",
              "evidence", "seconds", "verdict"]
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        for r in raw_rows:
            r = dict(r)
            r["expected_dims"] = "|".join(sorted(r["expected_dims"]))
            w.writerow(r)
    print(f"Results written to {args.out}")
    print("The 'evidence' column shows WHY the model flagged each dimension —")
    print("read it on the POOR rows to see exactly where its reasoning went wrong.\n")


if __name__ == "__main__":
    main()
