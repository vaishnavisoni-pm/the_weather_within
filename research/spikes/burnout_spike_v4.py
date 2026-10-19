"""
BURNOUT SPIKE v4 — THE CASCADE TEST

THE QUESTION THIS ANSWERS
  "too tired to hang out" touches physical AND social. But the social part is a
  CONSEQUENCE of the physical part, not independent social burnout. If we count
  both at full strength, one tired evening inflates the overall score.

  So we now ask the model to label each dimension with a ROLE:
      driver      = this is the underlying cause
      consequence = this only happened BECAUSE of the driver

  The scoring engine then down-weights consequences (0.5x) so a single cause
  isn't counted three times.

THE RISK — AND WHY THIS SCRIPT IS AN A/B TEST
  Small models get WORSE when you ask them to do more. You already validated
  85% primary accuracy on a SIMPLE schema. Asking a 3B model to also reason
  about causality might break that.

  So this script runs BOTH schemas against the SAME cases and compares:

      SIMPLE  (v2 prompt) — just dimensions + primary
      CASCADE (v4 prompt) — dimensions + primary + driver/consequence roles

  DECISION RULE:
    If CASCADE keeps primary accuracy within ~5 points of SIMPLE, adopt it —
    the causal insight is worth it.
    If CASCADE drops primary accuracy meaningfully, REJECT it. Keep the simple
    schema and handle cascades in your deterministic scoring math instead
    (e.g. "if physical is the max and social rose the same day, discount social").
    You do NOT need the LLM to do the causal reasoning. It's just nicer if it can.

HOW TO RUN
  python burnout_spike_v4.py --model gemma3:4b --out v4_gemma.csv
  python burnout_spike_v4.py --model qwen2.5:3b --out v4_qwen.csv
"""

import argparse
import csv
import json
import re
import sys
import time

import requests

OLLAMA_URL = "http://localhost:11434/api/generate"
DIMENSIONS = ["physical", "mental", "emotional", "social", "professional", "personal"]
MAX_RETRIES = 2
CONSEQUENCE_WEIGHT = 0.5   # how much a 'consequence' dimension counts vs a 'driver'

SHARED_RULES = """THE SIX TYPES
- physical: tiredness, poor sleep, aches, illness, appetite loss, bodily depletion
- mental: concentration problems, brain fog, forgetfulness, decision fatigue, inability to switch off
- emotional: low mood, irritability, tearfulness, numbness, feeling drained or overwhelmed
- social: withdrawing from people, loneliness, dreading contact, finding company draining
- professional: work exhaustion, dread of work, cynicism about work, feeling ineffective at work
- personal: no energy or ownership of one's own life, lost hobbies, lost meaning or identity

THE EVIDENCE RULE — THE MOST IMPORTANT RULE
For every type you flag you MUST quote the exact words that prove it.
If you cannot quote proof, DO NOT FLAG IT.

WHAT IS NOT EVIDENCE
- Mentioning work is NOT professional depletion. A normal day at work is just a day at work.
- Mentioning sleep is NOT physical depletion unless the sleep was bad or they are tired.
- Mentioning people is NOT social depletion unless they withdrew, dreaded contact, or felt lonely.
- Being tired is NOT automatically mental depletion. Only flag mental if focus/memory/thinking is mentioned.

INFORMAL WRITING
People write tired, short, messy notes: slang, lowercase, missing words, mixed languages.
Read the meaning, not the grammar. Short does not mean severe.

NEUTRAL AND GOOD DAYS
Many days are fine. If there is no depletion, return an empty list and primary "none".
Do not hunt for problems that are not there."""

# --------------------------------------------------------------------------
# SCHEMA A — SIMPLE (this is the v2 prompt that scored 82-85% primary)
# --------------------------------------------------------------------------
PROMPT_SIMPLE = f"""You are a strict labelling tool for a wellbeing app. You do not diagnose, advise, or chat.

You read a short description of someone's day and identify ONLY the types of depletion for which the text gives DIRECT evidence.

{SHARED_RULES}

Output ONLY a JSON object. No explanation, no markdown, no backticks.

SCHEMA
{{"dimensions": [{{"dimension": "<one of the six>", "evidence": "<exact words>", "intensity": <0.0-1.0>, "confidence": <0.0-1.0>}}], "primary": "<one of the six, or none>"}}

EXAMPLES

Input: "Worked until midnight for the third night. My eyes are burning and I couldn't focus by the end."
Output: {{"dimensions": [{{"dimension": "professional", "evidence": "Worked until midnight for the third night", "intensity": 0.8, "confidence": 0.9}}, {{"dimension": "physical", "evidence": "My eyes are burning", "intensity": 0.7, "confidence": 0.8}}, {{"dimension": "mental", "evidence": "couldn't focus", "intensity": 0.6, "confidence": 0.8}}], "primary": "professional"}}

Input: "Slept well, finished my work on time, went for a run in the evening."
Output: {{"dimensions": [], "primary": "none"}}

Input: "ugh. 3 hours sleep. running on fumes. cant even"
Output: {{"dimensions": [{{"dimension": "physical", "evidence": "3 hours sleep. running on fumes", "intensity": 0.8, "confidence": 0.9}}], "primary": "physical"}}
"""

# --------------------------------------------------------------------------
# SCHEMA B — CASCADE (adds the causal role)
# --------------------------------------------------------------------------
PROMPT_CASCADE = f"""You are a strict labelling tool for a wellbeing app. You do not diagnose, advise, or chat.

You read a short description of someone's day, identify the types of depletion it shows, AND work out which one CAUSED the others.

{SHARED_RULES}

THE CAUSAL RULE — NEW AND IMPORTANT
Types of depletion are not independent. They cascade. Being physically exhausted makes people
withdraw from friends; that is not the same as social burnout. Work exhaustion crowds out personal
life; that is not the same as losing your sense of meaning.

For EVERY type you flag, assign a role:
- "driver"      : the underlying cause. The thing that actually went wrong.
- "consequence" : this only appears BECAUSE of the driver. Remove the driver and this goes away.

Set "caused_by" on every consequence, naming the driver it follows from.
There is usually exactly ONE driver. If two things went wrong independently, you may mark two drivers.

WORKED EXAMPLE OF THE CAUSAL RULE
"Too tired to hang out with anyone tonight."
  physical is the DRIVER   — they are exhausted
  social is a CONSEQUENCE  — they skipped company only BECAUSE they were tired.
  They do not dread people; they have no energy. Mark social as caused_by physical.

CONTRAST
"Went to the party but stood in the corner wishing I was home."
  social is the DRIVER — they had the energy to attend but could not bear the company.
  Nothing caused this from elsewhere. This is real social depletion.

Output ONLY a JSON object. No explanation, no markdown, no backticks.

SCHEMA
{{"dimensions": [{{"dimension": "<one of the six>", "role": "driver" or "consequence", "caused_by": "<driver name, or null>", "evidence": "<exact words>", "intensity": <0.0-1.0>, "confidence": <0.0-1.0>}}], "primary": "<the DRIVER, or none>"}}

Note: "primary" must be a DRIVER, never a consequence.

EXAMPLES

Input: "Worked until midnight for the third night. My eyes are burning and I couldn't focus by the end."
Output: {{"dimensions": [{{"dimension": "professional", "role": "driver", "caused_by": null, "evidence": "Worked until midnight for the third night", "intensity": 0.8, "confidence": 0.9}}, {{"dimension": "physical", "role": "consequence", "caused_by": "professional", "evidence": "My eyes are burning", "intensity": 0.7, "confidence": 0.8}}, {{"dimension": "mental", "role": "consequence", "caused_by": "professional", "evidence": "couldn't focus", "intensity": 0.6, "confidence": 0.8}}], "primary": "professional"}}

Input: "Too tired to hang out with anyone tonight."
Output: {{"dimensions": [{{"dimension": "physical", "role": "driver", "caused_by": null, "evidence": "Too tired", "intensity": 0.7, "confidence": 0.9}}, {{"dimension": "social", "role": "consequence", "caused_by": "physical", "evidence": "to hang out with anyone", "intensity": 0.4, "confidence": 0.6}}], "primary": "physical"}}

Input: "Slept well, finished my work on time, went for a run in the evening."
Output: {{"dimensions": [], "primary": "none"}}

Input: "ugh. 3 hours sleep. running on fumes. cant even"
Output: {{"dimensions": [{{"dimension": "physical", "role": "driver", "caused_by": null, "evidence": "3 hours sleep. running on fumes", "intensity": 0.8, "confidence": 0.9}}], "primary": "physical"}}
"""


def call_ollama(model, system, text):
    payload = {
        "model": model,
        "system": system,
        "prompt": f'Input: "{text}"\nOutput:',
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.0, "num_predict": 500},
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


def normalise(parsed, floor=0.7):
    if not isinstance(parsed, dict):
        return None
    dims, roles, weights = set(), {}, {}
    for e in parsed.get("dimensions", []) or []:
        if not isinstance(e, dict):
            continue
        name = str(e.get("dimension", "")).strip().lower()
        if name not in DIMENSIONS:
            continue
        try:
            conf = float(e.get("confidence", 1.0))
        except (TypeError, ValueError):
            conf = 1.0
        if conf < floor:
            continue
        role = str(e.get("role", "driver")).strip().lower()
        if role not in ("driver", "consequence"):
            role = "driver"
        dims.add(name)
        roles[name] = role
        # THE DOWN-WEIGHTING: a consequence contributes half as much to the score
        weights[name] = CONSEQUENCE_WEIGHT if role == "consequence" else 1.0

    primary = str(parsed.get("primary", "none")).strip().lower()
    if primary not in DIMENSIONS + ["none"]:
        primary = "none"
    if primary != "none" and primary not in dims:
        primary = "none"

    # inflation = how much the score is exaggerated by counting consequences fully.
    # 1.0 means no inflation. 3.0 means one event is being counted three times.
    raw_load = len(dims)
    adj_load = sum(weights.values())
    return {"dims": dims, "primary": primary, "roles": roles,
            "raw_load": raw_load, "adj_load": adj_load}


def extract(model, system, text):
    for _ in range(MAX_RETRIES + 1):
        try:
            raw = call_ollama(model, system, text)
        except requests.exceptions.ConnectionError:
            print("\nERROR: Ollama not reachable. Run:  ollama serve")
            sys.exit(1)
        except Exception as exc:
            print(f"  ! call failed ({exc}); retrying")
            continue
        res = normalise(parse_reply(raw))
        if res is not None:
            return res
    return None


def score(rows):
    hits = misses = extras = 0
    prim_ok = none_ok = none_tot = 0
    raw_load = adj_load = 0.0
    for r in rows:
        exp, got = set(r["expected_dims"]), r["got_dims"]
        hits += len(exp & got)
        misses += len(exp - got)
        extras += len(got - exp)
        if r["got_primary"] == r["primary"]:
            prim_ok += 1
        if r["primary"] == "none":
            none_tot += 1
            if r["got_primary"] == "none" and not got:
                none_ok += 1
        raw_load += r["raw_load"]
        adj_load += r["adj_load"]
    n = len(rows) or 1
    p = hits / (hits + extras) if (hits + extras) else 0
    rc = hits / (hits + misses) if (hits + misses) else 0
    return {
        "precision": p,
        "recall": rc,
        "f1": 2 * p * rc / (p + rc) if (p + rc) else 0,
        "primary": prim_ok / n,
        "good_day": none_ok / none_tot if none_tot else 0,
        "raw_load": raw_load / n,
        "adj_load": adj_load / n,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gemma3:4b")
    ap.add_argument("--cases", default="test_cases.json")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--out", default="v4_results.csv")
    args = ap.parse_args()

    with open(args.cases) as f:
        cases = [c for c in json.load(f) if "text" in c]
    if args.limit:
        cases = cases[:args.limit]

    print(f"\nSPIKE v4 — A/B: SIMPLE schema vs CASCADE schema")
    print(f"Model: {args.model} | {len(cases)} cases | 2 calls each\n")

    simple_rows, cascade_rows, out_rows = [], [], []

    for c in cases:
        t0 = time.time()
        s = extract(args.model, PROMPT_SIMPLE, c["text"])
        k = extract(args.model, PROMPT_CASCADE, c["text"])
        secs = time.time() - t0
        if s is None or k is None:
            print(f"[{c['id']:>2}] UNUSABLE")
            continue

        base = {"expected_dims": c["expected_dims"], "primary": c["primary"]}
        simple_rows.append({**base, "got_dims": s["dims"], "got_primary": s["primary"],
                            "raw_load": s["raw_load"], "adj_load": s["adj_load"]})
        cascade_rows.append({**base, "got_dims": k["dims"], "got_primary": k["primary"],
                             "raw_load": k["raw_load"], "adj_load": k["adj_load"]})

        cons = [d for d, r in k["roles"].items() if r == "consequence"]
        sflag = "✓" if s["primary"] == c["primary"] else "✗"
        kflag = "✓" if k["primary"] == c["primary"] else "✗"
        print(f"[{c['id']:>2}] simple {sflag} {s['primary']:<12} | cascade {kflag} {k['primary']:<12} "
              f"consequences={cons or '—'} ({secs:.0f}s)")

        out_rows.append({
            "id": c["id"], "text": c["text"],
            "expected_dims": "|".join(sorted(c["expected_dims"])),
            "expected_primary": c["primary"],
            "simple_dims": "|".join(sorted(s["dims"])), "simple_primary": s["primary"],
            "cascade_dims": "|".join(sorted(k["dims"])), "cascade_primary": k["primary"],
            "cascade_roles": json.dumps(k["roles"]),
            "raw_load": k["raw_load"], "adj_load": round(k["adj_load"], 2),
        })

    a, b = score(simple_rows), score(cascade_rows)

    print("\n" + "=" * 70)
    print(f"A/B RESULT — {args.model}")
    print("=" * 70)
    print(f"{'metric':<24}{'SIMPLE':>10}{'CASCADE':>10}{'delta':>10}")
    print("-" * 70)
    for key, name in [("primary", "Primary correct"), ("f1", "F1"),
                      ("precision", "Precision"), ("recall", "Recall"),
                      ("good_day", "Good days left alone")]:
        d = b[key] - a[key]
        print(f"{name:<24}{a[key]:>9.0%}{b[key]:>10.0%}{d:>+10.0%}")
    print("-" * 70)
    print(f"{'Dims flagged /case':<24}{a['raw_load']:>9.2f}{b['raw_load']:>10.2f}")
    print(f"{'After down-weighting':<24}{'—':>9}{b['adj_load']:>10.2f}")
    infl = (b["raw_load"] / b["adj_load"]) if b["adj_load"] else 1
    print(f"\n  SCORE INFLATION AVOIDED: {infl:.2f}x")
    print(f"  (without the cascade fix, the average day's load was being")
    print(f"   counted {infl:.2f} times larger than it should be)")
    print("=" * 70)

    delta = b["primary"] - a["primary"]
    print()
    if delta >= -0.05:
        print("  VERDICT: ADOPT CASCADE. The causal reasoning costs little or no")
        print("  accuracy, and it prevents score inflation + gives far better insights.")
    else:
        print("  VERDICT: REJECT CASCADE. The richer schema degraded the model.")
        print("  Keep the SIMPLE prompt, and handle cascades in your scoring math:")
        print("    if physical is the top dimension and social rose the same day,")
        print("    discount social. Deterministic, free, and no model needed.")
    print()

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(out_rows[0].keys()))
        w.writeheader()
        w.writerows(out_rows)
    print(f"Written to {args.out} — the 'cascade_roles' column shows what it")
    print("called a driver vs a consequence. Read it on the multi-dimension cases.\n")


if __name__ == "__main__":
    main()
