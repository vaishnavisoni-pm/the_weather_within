"""
BURNOUT SPIKE v3 — THE REALITY CHECK

WHY THIS EXISTS
  v2 proved gemma3:4b hits 82-85% primary accuracy... on 40 test cases that
  CLAUDE wrote. Those are clean, tidy, one-clear-idea-per-sentence.

  Real people write like this at 11pm:  "ugh. same. cant."

  If the model only works on polished prose, it doesn't work. This script runs
  BOTH sets and reports them SEPARATELY, so you can see the drop.

  The gap between the two scorecards is the single most honest number in this
  whole spike. A small gap = ship it. A big gap = the model needs messier
  examples in its prompt.

HOW TO RUN
  1. Open my_cases.json, delete the _INSTRUCTIONS block, and replace every
     "WRITE YOUR OWN HERE" with a real day-description in your own voice.
     Label each one yourself. Include at least 5 good/ordinary days.
  2. Then:
       python burnout_spike_v3.py --model gemma3:4b --floor 0.7 --out v3_4b.csv
       python burnout_spike_v3.py --model qwen2.5:3b --floor 0.7 --out v3_qwen.csv

  Floor 0.7 is used as the default because the v2 sweep showed the models are
  systematically overconfident — nothing below 0.7 gets filtered at all.
"""

import argparse
import csv
import json
import os
import re
import sys
import time

import requests

OLLAMA_URL = "http://localhost:11434/api/generate"
DIMENSIONS = ["physical", "mental", "emotional", "social", "professional", "personal"]
MAX_RETRIES = 2

# The v2 prompt, unchanged — it's the one that got precision to 91%.
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

INFORMAL WRITING
People write tired, short, messy notes with slang, lowercase, missing words, or mixed languages.
Read the meaning, not the grammar. "cant even" means depleted. "meh" is not necessarily burnout.
Short does not mean severe. Judge the content, not the style.

NEUTRAL AND GOOD DAYS
Many days are fine. If the text shows no depletion, return an empty list and primary "none".
Do not hunt for problems that are not there.

SCORES
- intensity: 0.0-1.0, how severe the depletion appears
- confidence: 0.0-1.0, how certain you are the evidence really supports it. Be honest.

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

Input: "ugh. 3 hours sleep. running on fumes. cant even"
Output: {"dimensions": [{"dimension": "physical", "evidence": "3 hours sleep. running on fumes", "intensity": 0.8, "confidence": 0.9}], "primary": "physical"}

Input: "decent day tbh. finished the pipeline, chai with the team, home early"
Output: {"dimensions": [], "primary": "none"}

Input: "meh day. nothing much happened."
Output: {"dimensions": [], "primary": "none"}
"""


def call_ollama(model, text):
    payload = {
        "model": model,
        "system": SYSTEM_PROMPT,
        "prompt": f'Input: "{text}"\nOutput:',
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.0, "num_predict": 400},
    }
    r = requests.post(OLLAMA_URL, json=payload, timeout=300)
    r.raise_for_status()
    return r.json()["response"]


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
    if not isinstance(parsed, dict):
        return None
    kept, evidence = set(), {}
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
        if name in DIMENSIONS and conf >= floor:
            kept.add(name)
            evidence[name] = ev
    primary = str(parsed.get("primary", "none")).strip().lower()
    if primary not in DIMENSIONS + ["none"]:
        primary = "none"
    if primary != "none" and primary not in kept:
        primary = "none"
    return {"dims": kept, "primary": primary, "evidence": evidence}


def extract(model, text, floor):
    for _ in range(MAX_RETRIES + 1):
        try:
            raw = call_ollama(model, text)
        except requests.exceptions.ConnectionError:
            print("\nERROR: Ollama not reachable. Run:  ollama serve")
            sys.exit(1)
        except Exception as exc:
            print(f"  ! call failed ({exc}); retrying")
            continue
        result = normalise(parse_reply(raw), floor)
        if result is not None:
            return result
    return None


def scorecard(rows, name):
    """Prints one scorecard for one group of cases."""
    if not rows:
        return None
    hits = misses = extras = 0
    primary_ok = none_ok = none_total = 0
    for r in rows:
        exp, got = set(r["expected_dims"]), r["got_dims"]
        hits += len(exp & got)
        misses += len(exp - got)
        extras += len(got - exp)
        if r["got_primary"] == r["primary"]:
            primary_ok += 1
        if r["primary"] == "none":
            none_total += 1
            if r["got_primary"] == "none" and not got:
                none_ok += 1

    n = len(rows)
    p = hits / (hits + extras) if (hits + extras) else 0
    rc = hits / (hits + misses) if (hits + misses) else 0
    f1 = 2 * p * rc / (p + rc) if (p + rc) else 0
    prim = primary_ok / n
    good = none_ok / none_total if none_total else None

    print(f"\n  {name}  ({n} cases)")
    print(f"    Precision            {p:6.0%}")
    print(f"    Recall               {rc:6.0%}")
    print(f"    F1                   {f1:6.0%}")
    print(f"    Primary correct      {prim:6.0%}")
    if good is not None:
        print(f"    Good days left alone {good:6.0%}")
    else:
        print(f"    Good days left alone    n/a  (no 'none' cases in this set!)")
    return {"f1": f1, "primary": prim, "good": good}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gemma3:4b")
    ap.add_argument("--floor", type=float, default=0.7)
    ap.add_argument("--claude-cases", default="test_cases.json")
    ap.add_argument("--my-cases", default="my_cases.json")
    ap.add_argument("--out", default="spike_v3_results.csv")
    args = ap.parse_args()

    # --- Load Claude's clean cases
    with open(args.claude_cases) as f:
        claude_cases = [c for c in json.load(f) if "text" in c]
    for c in claude_cases:
        c["source"] = "claude"

    # --- Load your real messy cases
    my_cases = []
    if os.path.exists(args.my_cases):
        with open(args.my_cases) as f:
            raw = json.load(f)
        for c in raw:
            if "text" not in c:
                continue                        # skips the _INSTRUCTIONS block
            if c["text"].strip().upper().startswith("WRITE YOUR OWN"):
                continue                        # skips unfilled placeholders
            c["source"] = "mine"
            my_cases.append(c)

    if not my_cases:
        print("\n!! No real cases found in my_cases.json.")
        print("   Fill in the 'WRITE YOUR OWN HERE' entries first — that's the whole point")
        print("   of this run. Without them you're just re-testing Claude's clean prose.\n")
        sys.exit(1)

    cases = claude_cases + my_cases
    print(f"\nSPIKE v3 — {args.model} @ floor {args.floor}")
    print(f"{len(claude_cases)} clean cases (Claude's) + {len(my_cases)} real cases (yours)\n")

    rows = []
    durations = []
    for c in cases:
        t0 = time.time()
        got = extract(args.model, c["text"], args.floor)
        secs = time.time() - t0
        durations.append(secs)
        if got is None:
            print(f"[{c['id']:>3}] UNUSABLE")
            continue
        exp = set(c["expected_dims"])
        ok = (exp == got["dims"] and got["primary"] == c["primary"])
        partial = (got["primary"] == c["primary"])
        flag = "✓" if ok else ("~" if partial else "✗")
        tag = "MINE " if c["source"] == "mine" else "clean"
        print(f"[{c['id']:>3}] {tag} {flag} exp={sorted(exp) or ['none']} "
              f"got={sorted(got['dims']) or ['none']} "
              f"primary {c['primary']}→{got['primary']} ({secs:.1f}s)")
        rows.append({
            "id": c["id"], "source": c["source"], "text": c["text"],
            "expected_dims": exp, "primary": c["primary"],
            "got_dims": got["dims"], "got_primary": got["primary"],
            "evidence": " ; ".join(f"{k}:{v}" for k, v in got["evidence"].items()),
            "seconds": round(secs, 1),
            "verdict": "GOOD" if ok else ("PARTIAL" if partial else "POOR"),
        })

    clean_rows = [r for r in rows if r["source"] == "claude"]
    my_rows = [r for r in rows if r["source"] == "mine"]

    print("\n" + "=" * 64)
    print(f"SCORECARD v3 — {args.model} @ floor {args.floor}")
    print("=" * 64)
    a = scorecard(clean_rows, "CLAUDE'S CLEAN CASES")
    b = scorecard(my_rows, "YOUR REAL MESSY CASES")
    print(f"\n  Avg seconds per call   {sum(durations)/len(durations):.1f}s")
    print("=" * 64)

    # ---- THE VERDICT: it's the GAP that matters, not the absolute score
    if a and b:
        gap = a["primary"] - b["primary"]
        print(f"\n  REALITY GAP (primary accuracy): {gap:+.0%}")
        if gap <= 0.10:
            print("  → SMALL GAP. The model handles real writing. Spike CLOSED — build it.")
        elif gap <= 0.25:
            print("  → MODERATE GAP. Workable, but add a few of your messiest examples")
            print("    into the prompt's EXAMPLES section and re-run.")
        else:
            print("  → LARGE GAP. The model only works on tidy prose. Either coach it with")
            print("    real examples in the prompt, or lean harder on the taps and treat")
            print("    free-text as a bonus rather than a core signal.")
    print("=" * 64 + "\n")

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["id", "source", "text", "expected_dims", "primary",
                                          "got_dims", "got_primary", "evidence", "seconds", "verdict"])
        w.writeheader()
        for r in rows:
            r = dict(r)
            r["expected_dims"] = "|".join(sorted(r["expected_dims"]))
            r["got_dims"] = "|".join(sorted(r["got_dims"]))
            w.writerow(r)
    print(f"Results written to {args.out} — filter source='mine' to see how it did on YOUR writing.\n")


if __name__ == "__main__":
    main()
