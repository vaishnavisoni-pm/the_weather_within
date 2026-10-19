"""
BURNOUT SPIKE 1 — Can a small local LLM read a person's day and tag which
kinds of burnout it touched?

WHAT THIS SCRIPT DOES
  1. Loads 40 hand-labelled "day descriptions" from test_cases.json
  2. Sends each one to a model running in Ollama on your own laptop
  3. Forces the model to reply ONLY as JSON, e.g.
        {"dimensions": [{"dimension": "professional", "intensity": 0.8}], "primary": "professional"}
  4. Compares the model's answer to your hand labels
  5. Prints a scorecard and writes results to a CSV you can open in Excel

WHY IT MATTERS
  If the model can't do this reliably, the whole "LLM-as-sensor" idea is dead
  and you build the simpler tap-only app instead. Better to learn that today
  than after three months of app development.

HOW TO RUN (details in the chat, short version here)
  python burnout_spike.py --mock                 <- no AI, just checks the script works
  python burnout_spike.py --model gemma3:4b      <- the real test
  python burnout_spike.py --model gemma3:1b      <- the phone-sized model
"""

import argparse       # lets you pass options like --model when you run the script
import csv            # for writing the results file you'll open in Excel
import json           # for reading/writing JSON (the structured format the model must reply in)
import random         # only used by the fake model in --mock mode
import re             # text pattern matching, used to rescue slightly-messy model replies
import sys            # lets the script exit with an error message
import time           # to measure how many seconds each call takes

import requests       # the library that sends the request to Ollama over your local network


# ---------------------------------------------------------------------------
# CONFIGURATION
# ---------------------------------------------------------------------------

# Ollama runs a little server on your own machine at this address.
# "localhost" means "this computer". 11434 is the door number (port) Ollama listens on.
OLLAMA_URL = "http://localhost:11434/api/generate"

# The six kinds of burnout we're asking the model to detect.
DIMENSIONS = ["physical", "mental", "emotional", "social", "professional", "personal"]

# How many times to retry if the model returns something that isn't valid JSON.
MAX_RETRIES = 2

# We ask the model the SAME question twice per case to measure "stability" —
# a model that gives different answers to identical input is not trustworthy.
STABILITY_RUNS = 2


# ---------------------------------------------------------------------------
# THE PROMPT
# ---------------------------------------------------------------------------
# This is the instruction we send the model with every day-description.
# Three things make small models behave:
#   (a) a very explicit output schema (the exact shape of JSON we demand)
#   (b) few-shot examples (worked examples it can imitate)
#   (c) a blunt "output nothing else" instruction
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """You are a labelling tool for a wellbeing app. You do NOT give advice, diagnose, or reply conversationally.

You read a short description of someone's day and identify which types of depletion it shows evidence of.

The six types:
- physical: tiredness, poor sleep, aches, illness, appetite, bodily depletion
- mental: concentration, brain fog, forgetfulness, decision fatigue, can't switch off
- emotional: mood, irritability, tearfulness, numbness, feeling drained or overwhelmed
- social: withdrawing from people, loneliness, dreading contact, cancelling on others
- professional: work exhaustion, dread of work, cynicism about work, feeling ineffective at work
- personal: no time or energy for own life, loss of hobbies, meaning, identity outside work

RULES
- Only include a type if the text gives real evidence for it. Do not guess.
- If the day sounds fine or neutral, return an empty list and primary "none".
- intensity is 0.0 to 1.0 (how strong the evidence is).
- confidence is 0.0 to 1.0 (how sure you are).
- primary is the single strongest type, or "none".
- Output ONLY a JSON object. No explanation, no markdown, no backticks.

OUTPUT SCHEMA
{"dimensions": [{"dimension": "<one of the six>", "intensity": <0.0-1.0>, "confidence": <0.0-1.0>}], "primary": "<one of the six, or none>"}

EXAMPLES

Input: "Worked until midnight for the third night. My eyes are burning and I couldn't focus by the end."
Output: {"dimensions": [{"dimension": "professional", "intensity": 0.8, "confidence": 0.9}, {"dimension": "physical", "intensity": 0.7, "confidence": 0.8}, {"dimension": "mental", "intensity": 0.6, "confidence": 0.7}], "primary": "professional"}

Input: "Turned down two invitations this week. I'd rather just be by myself."
Output: {"dimensions": [{"dimension": "social", "intensity": 0.7, "confidence": 0.8}], "primary": "social"}

Input: "Slept well, finished my work on time, went for a run in the evening."
Output: {"dimensions": [], "primary": "none"}
"""


def build_user_prompt(text):
    """Wraps one day-description in the format the model expects."""
    return f'Input: "{text}"\nOutput:'


# ---------------------------------------------------------------------------
# TALKING TO OLLAMA
# ---------------------------------------------------------------------------

def call_ollama(model, text, temperature=0.0):
    """
    Sends one day-description to the model and returns its raw text reply.

    temperature=0.0 means "be as deterministic as possible" — we want the same
    answer every time for the same input, not creative variety.
    """
    payload = {
        "model": model,
        "system": SYSTEM_PROMPT,
        "prompt": build_user_prompt(text),
        "stream": False,        # don't dribble the answer out word by word; give it all at once
        "format": "json",       # Ollama's built-in nudge to force valid JSON out
        "options": {
            "temperature": temperature,
            "num_predict": 300,  # cap the reply length; we only need a small JSON object
        },
    }
    response = requests.post(OLLAMA_URL, json=payload, timeout=180)
    response.raise_for_status()          # blow up loudly if Ollama returned an error
    return response.json()["response"]   # Ollama wraps the model's text in a "response" field


def fake_model(text):
    """
    A pretend model used by --mock so you can confirm the script itself works
    before you involve any AI. It just keyword-matches, badly, on purpose.
    """
    keywords = {
        "physical": ["slept", "sleep", "tired", "exhaust", "ache", "headache", "sick", "body", "yawn", "appetite"],
        "mental": ["focus", "concentrat", "forgot", "brain", "fog", "zoned", "decision", "switch off"],
        "emotional": ["cried", "tears", "angry", "irritab", "snapped", "low", "numb", "flat", "guilty"],
        "social": ["friend", "invit", "alone", "lonely", "people", "cancel", "flatmate", "party"],
        "professional": ["work", "meeting", "manager", "deadline", "office", "client", "team", "standup"],
        "personal": ["hobby", "guitar", "paint", "myself", "my own", "resent", "purely because"],
    }
    lowered = text.lower()
    found = []
    for dim, words in keywords.items():
        if any(w in lowered for w in words):
            found.append({
                "dimension": dim,
                "intensity": round(random.uniform(0.5, 0.9), 2),
                "confidence": round(random.uniform(0.5, 0.9), 2),
            })
    primary = found[0]["dimension"] if found else "none"
    return json.dumps({"dimensions": found, "primary": primary})


# ---------------------------------------------------------------------------
# MAKING SENSE OF THE MODEL'S REPLY
# ---------------------------------------------------------------------------

def parse_reply(raw):
    """
    Turns the model's raw text into a Python dictionary.

    Small models sometimes wrap JSON in ```backticks``` or add a stray sentence.
    We first try to read it straight; if that fails we hunt for the {...} block.
    Returns None if it's unsalvageable — which we count as a failure.
    """
    if not raw:
        return None

    # Attempt 1: it's clean JSON already.
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass

    # Attempt 2: strip markdown fences and grab the outermost {...}
    cleaned = raw.replace("```json", "").replace("```", "").strip()
    match = re.search(r"\{.*\}", cleaned, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            return None
    return None


def normalise(parsed):
    """
    Takes whatever the model gave us and forces it into a clean, predictable shape:
    a set of dimension names, plus the single 'primary' dimension.

    Anything the model invented that isn't one of our six dimensions is discarded.
    This is the guardrail that stops a sloppy model from polluting your data.
    """
    if not isinstance(parsed, dict):
        return None

    dims = set()
    for entry in parsed.get("dimensions", []) or []:
        if isinstance(entry, dict):
            name = str(entry.get("dimension", "")).strip().lower()
        else:
            name = str(entry).strip().lower()   # tolerate a plain list of strings
        if name in DIMENSIONS:
            dims.add(name)

    primary = str(parsed.get("primary", "none")).strip().lower()
    if primary not in DIMENSIONS + ["none"]:
        primary = "none"

    return {"dims": dims, "primary": primary}


def extract(model, text, mock):
    """
    One full extraction attempt, with retries.
    Returns (result_or_None, seconds_taken, number_of_json_failures)
    """
    failures = 0
    start = time.time()

    for attempt in range(MAX_RETRIES + 1):
        try:
            raw = fake_model(text) if mock else call_ollama(model, text)
        except requests.exceptions.ConnectionError:
            print("\nERROR: Could not reach Ollama at localhost:11434.")
            print("Is Ollama running? Open a terminal and run:  ollama serve")
            sys.exit(1)
        except Exception as exc:
            print(f"  ! call failed ({exc}); retrying")
            failures += 1
            continue

        result = normalise(parse_reply(raw))
        if result is not None:
            return result, time.time() - start, failures

        failures += 1   # the model produced something we couldn't read

    return None, time.time() - start, failures


# ---------------------------------------------------------------------------
# SCORING THE MODEL AGAINST YOUR LABELS
# ---------------------------------------------------------------------------

def score_case(expected_dims, expected_primary, got):
    """
    Compares one model answer to your hand label.

    We measure, for the set of dimensions:
      hits   = it correctly flagged a dimension you flagged      (true positives)
      misses = it failed to flag one you flagged                 (false negatives)
      extras = it flagged one you did NOT flag                   (false positives)

    And separately whether it picked the right 'primary' dimension.
    """
    expected = set(expected_dims)
    actual = got["dims"]

    hits = len(expected & actual)        # & means "in both"
    misses = len(expected - actual)      # - means "in the first but not the second"
    extras = len(actual - expected)
    primary_ok = (got["primary"] == expected_primary)

    return hits, misses, extras, primary_ok


def main():
    parser = argparse.ArgumentParser(description="Burnout extraction spike")
    parser.add_argument("--model", default="gemma3:4b",
                        help="Ollama model name, e.g. gemma3:4b, gemma3:1b, qwen2.5:3b")
    parser.add_argument("--mock", action="store_true",
                        help="Run with a fake model — no AI, just proves the script works")
    parser.add_argument("--cases", default="test_cases.json")
    parser.add_argument("--limit", type=int, default=0,
                        help="Only run the first N cases (handy for a quick trial)")
    parser.add_argument("--out", default="spike_results.csv")
    args = parser.parse_args()

    with open(args.cases) as f:
        cases = json.load(f)
    if args.limit:
        cases = cases[:args.limit]

    label = "MOCK (fake model)" if args.mock else args.model
    print(f"\nRunning {len(cases)} cases against: {label}")
    print("(each case is run twice to test consistency)\n")

    # Running totals
    total_hits = total_misses = total_extras = 0
    primary_correct = 0
    json_failures = 0
    unusable = 0
    stable_count = 0
    durations = []
    rows = []

    for case in cases:
        # --- Run 1: the answer we score
        got, secs, fails = extract(args.model, case["text"], args.mock)
        json_failures += fails
        durations.append(secs)

        # --- Run 2: same input again, only to check the model is consistent
        got2, secs2, fails2 = extract(args.model, case["text"], args.mock)
        json_failures += fails2
        durations.append(secs2)

        if got is None:
            unusable += 1
            print(f"[{case['id']:>2}] UNUSABLE — model never returned readable JSON")
            rows.append({
                "id": case["id"], "text": case["text"],
                "expected_dims": "|".join(case["expected_dims"]),
                "expected_primary": case["primary"],
                "got_dims": "", "got_primary": "",
                "hits": 0, "misses": len(case["expected_dims"]), "extras": 0,
                "primary_ok": False, "stable": False, "seconds": round(secs, 1),
                "verdict": "UNUSABLE",
            })
            continue

        hits, misses, extras, primary_ok = score_case(
            case["expected_dims"], case["primary"], got
        )
        stable = (got2 is not None and got2["dims"] == got["dims"]
                  and got2["primary"] == got["primary"])

        total_hits += hits
        total_misses += misses
        total_extras += extras
        primary_correct += 1 if primary_ok else 0
        stable_count += 1 if stable else 0

        # A simple traffic-light verdict per case, in the spirit of your usual
        # colour-coded Excel outputs.
        if misses == 0 and extras == 0 and primary_ok:
            verdict = "GOOD"
        elif misses == 0 or primary_ok:
            verdict = "PARTIAL"
        else:
            verdict = "POOR"

        flag = "✓" if verdict == "GOOD" else ("~" if verdict == "PARTIAL" else "✗")
        print(f"[{case['id']:>2}] {flag} expected={sorted(case['expected_dims']) or ['none']} "
              f"got={sorted(got['dims']) or ['none']} "
              f"primary {case['primary']}→{got['primary']} "
              f"({secs:.1f}s{'' if stable else ', UNSTABLE'})")

        rows.append({
            "id": case["id"], "text": case["text"],
            "expected_dims": "|".join(sorted(case["expected_dims"])),
            "expected_primary": case["primary"],
            "got_dims": "|".join(sorted(got["dims"])),
            "got_primary": got["primary"],
            "hits": hits, "misses": misses, "extras": extras,
            "primary_ok": primary_ok, "stable": stable,
            "seconds": round(secs, 1), "verdict": verdict,
        })

    # -----------------------------------------------------------------------
    # THE SCORECARD
    # -----------------------------------------------------------------------
    n = len(cases)
    scored = n - unusable

    # Precision = of the dimensions it flagged, how many were right?
    # Recall    = of the dimensions it SHOULD have flagged, how many did it catch?
    # F1        = a single number balancing the two (the usual summary metric).
    precision = total_hits / (total_hits + total_extras) if (total_hits + total_extras) else 0
    recall = total_hits / (total_hits + total_misses) if (total_hits + total_misses) else 0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0

    primary_acc = primary_correct / scored if scored else 0
    stability = stable_count / scored if scored else 0
    json_ok_rate = 1 - (unusable / n) if n else 0
    avg_secs = sum(durations) / len(durations) if durations else 0

    print("\n" + "=" * 60)
    print(f"SCORECARD — {label}")
    print("=" * 60)
    print(f"  JSON usable            {json_ok_rate:6.0%}   (want > 95%)")
    print(f"  Dimension precision    {precision:6.0%}   (of what it flagged, how much was right)")
    print(f"  Dimension recall       {recall:6.0%}   (of what it should flag, how much it caught)")
    print(f"  Dimension F1           {f1:6.0%}   (want > 70%)")
    print(f"  Primary correct        {primary_acc:6.0%}   (want > 60%)")
    print(f"  Stable on repeat       {stability:6.0%}   (want > 80%)")
    print(f"  Avg seconds per call   {avg_secs:6.1f}s   (want < 10s on a phone)")
    print("=" * 60)

    # The go / no-go call
    if json_ok_rate > 0.95 and f1 > 0.70 and stability > 0.80:
        print("  VERDICT: GO — the hybrid LLM-as-sensor approach holds up.")
    elif f1 > 0.55:
        print("  VERDICT: BORDERLINE — try a bigger model or improve the prompt,")
        print("           then re-run before committing.")
    else:
        print("  VERDICT: NO-GO — build the tap-only app with templated insights.")
    print("=" * 60 + "\n")

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"Per-case results written to: {args.out}")
    print("Open it in Excel and read the POOR rows — they tell you what to fix.\n")


if __name__ == "__main__":
    main()
