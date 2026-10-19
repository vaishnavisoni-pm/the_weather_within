"""
BURNOUT SPIKE v5 — DOES THE CORRECTION LOOP ACTUALLY WORK?

THE IDEA BEING TESTED
  In the app, after each extraction the user confirms or corrects the tags:
      "Sounds like today was mostly PROFESSIONAL, with some PHYSICAL. Right?"
      [Yes]  [Change]

  Every correction gets stored on the phone. On future extractions we paste the
  user's own past corrections into the prompt as examples:

      Examples of how THIS user writes:
        "cant even. dead."          -> physical
        "the standup again"         -> professional

  The model still learns NOTHING permanently (its weights never change). But
  every call now arrives pre-loaded with that person's idiom. This is
  "personalized few-shot" — memory in the PROMPT, not in the model.

THE METHODOLOGICAL TRAP (and how this script avoids it)
  If you correct case 7 and then re-test case 7, of course it's right — you've
  stapled the answer to its desk. That proves nothing.

  So: cases are processed IN SEQUENCE, like a user living through days. Each
  case is SCORED FIRST using only corrections from EARLIER cases. Only then may
  it be added to memory. A case never sees its own answer.

  The question is not "can it repeat corrections back" (trivially yes).
  The question is "do corrections on days 1-20 improve accuracy on days 21-40?"
  That is generalization — learning your VOICE, not memorizing your answers.

THE CONTROL ARM
  The script runs the same cases, in the same order, TWICE:
      ARM A: no corrections at all (control)
      ARM B: corrections accumulate (treatment)
  Without the control, any improvement might just be luck of the ordering.

HOW TO READ THE RESULT
  Look at the SECOND HALF accuracy. That's where memory is full and the effect
  should show. If ARM B beats ARM A on the second half, the correction loop
  works and you should build it. If not, the loop is still worth having for UX
  (users like being able to fix things) but don't claim it makes the AI smarter.

HOW TO RUN
  python burnout_spike_v5.py --mock                      <- fast, no AI, checks plumbing
  python burnout_spike_v5.py --model qwen2.5:3b --out v5_qwen.csv
  python burnout_spike_v5.py --model gemma3:4b --memory 8 --out v5_gemma.csv
"""

import argparse
import csv
import json
import random
import re
import sys
import time

import requests

OLLAMA_URL = "http://localhost:11434/api/generate"
DIMENSIONS = ["physical", "mental", "emotional", "social", "professional", "personal"]
MAX_RETRIES = 2

BASE_PROMPT = """You are a strict labelling tool for a wellbeing app. You do not diagnose, advise, or chat.

You read a short description of someone's day and identify ONLY the types of depletion for which the text gives DIRECT evidence.

THE SIX TYPES
- physical: tiredness, poor sleep, aches, illness, appetite loss, bodily depletion
- mental: concentration problems, brain fog, forgetfulness, decision fatigue, inability to switch off
- emotional: low mood, irritability, tearfulness, numbness, feeling drained or overwhelmed
- social: withdrawing from people, loneliness, dreading contact, finding company draining
- professional: work exhaustion, dread of work, cynicism about work, feeling ineffective at work
- personal: no ownership of one's own life, lost hobbies, lost meaning or identity

THE EVIDENCE RULE
For every type you flag you MUST quote the exact words that prove it.
If you cannot quote proof, DO NOT FLAG IT.

WHAT IS NOT EVIDENCE
- Mentioning work is NOT professional depletion. A normal day at work is just a day at work.
- Mentioning sleep is NOT physical depletion unless the sleep was bad or they are tired.
- Mentioning people is NOT social depletion unless they withdrew, dreaded contact, or felt lonely.
- Being tired is NOT automatically mental depletion. Only flag mental if thinking is mentioned.

INFORMAL WRITING
People write tired, short, messy notes: slang, lowercase, missing words, mixed languages.
Read the meaning, not the grammar. Short does not mean severe.

NEUTRAL AND GOOD DAYS
Many days are fine. If there is no depletion, return an empty list and primary "none".
Do not hunt for problems that are not there.

Output ONLY a JSON object. No explanation, no markdown, no backticks.

SCHEMA
{"dimensions": [{"dimension": "<one of the six>", "evidence": "<exact words>", "intensity": <0.0-1.0>, "confidence": <0.0-1.0>}], "primary": "<one of the six, or none>"}

GENERAL EXAMPLES

Input: "Worked until midnight for the third night. My eyes are burning and I couldn't focus by the end."
Output: {"dimensions": [{"dimension": "professional", "evidence": "Worked until midnight", "intensity": 0.8, "confidence": 0.9}, {"dimension": "physical", "evidence": "My eyes are burning", "intensity": 0.7, "confidence": 0.8}, {"dimension": "mental", "evidence": "couldn't focus", "intensity": 0.6, "confidence": 0.8}], "primary": "professional"}

Input: "Slept well, finished my work on time, went for a run in the evening."
Output: {"dimensions": [], "primary": "none"}

Input: "ugh. 3 hours sleep. running on fumes. cant even"
Output: {"dimensions": [{"dimension": "physical", "evidence": "3 hours sleep. running on fumes", "intensity": 0.8, "confidence": 0.9}], "primary": "physical"}
"""


def build_memory_block(memory):
    """
    Turns the user's past corrections into extra few-shot examples.

    THIS is the whole mechanism. The model's weights never change — we simply
    re-staple the user's own corrected examples onto every future request.

    Keep it SHORT: Gemini Nano's context is only ~4000 tokens, and a long
    memory block crowds out the actual instructions. 5-8 examples is plenty.
    """
    if not memory:
        return ""
    lines = ["\n\nHOW THIS PARTICULAR USER WRITES",
             "These are real corrections this user made to your earlier labels.",
             "Their phrasing is idiosyncratic. Learn it. Weight these heavily.\n"]
    for m in memory:
        dims = ", ".join(sorted(m["dims"])) if m["dims"] else "none"
        lines.append(f'Input: "{m["text"]}"')
        lines.append(f'Correct answer: dimensions=[{dims}], primary={m["primary"]}\n')
    return "\n".join(lines)


def call_ollama(model, system, text):
    payload = {
        "model": model,
        "system": system,
        "prompt": f'Input: "{text}"\nOutput:',
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.0, "num_predict": 400},
    }
    r = requests.post(OLLAMA_URL, json=payload, timeout=300)
    r.raise_for_status()
    return r.json()["response"]


def fake_model(text, memory):
    """
    Mock model for --mock. It 'learns' crudely: if the incoming text closely
    resembles something in memory, it copies that answer. Otherwise it
    keyword-matches. Just enough to prove the plumbing and the scoring logic.
    """
    low = text.lower()
    for m in memory:
        shared = set(low.split()) & set(m["text"].lower().split())
        if len(shared) >= 3:
            return json.dumps({
                "dimensions": [{"dimension": d, "evidence": "", "intensity": 0.7,
                                "confidence": 0.9} for d in m["dims"]],
                "primary": m["primary"]})
    kw = {
        "physical": ["slept", "sleep", "tired", "exhaust", "ache", "sick", "yawn", "fumes"],
        "mental": ["focus", "concentrat", "forgot", "brain", "fog", "zoned", "decision"],
        "emotional": ["cried", "angry", "irritab", "snapped", "low", "numb", "flat"],
        "social": ["friend", "invit", "alone", "lonely", "cancel", "party"],
        "professional": ["work", "meeting", "manager", "deadline", "office", "standup"],
        "personal": ["hobby", "guitar", "paint", "myself", "resent"],
    }
    found = [{"dimension": d, "evidence": "", "intensity": 0.7,
              "confidence": round(random.uniform(0.7, 0.95), 2)}
             for d, ws in kw.items() if any(w in low for w in ws)]
    return json.dumps({"dimensions": found,
                       "primary": found[0]["dimension"] if found else "none"})


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
    dims = set()
    for e in parsed.get("dimensions", []) or []:
        if not isinstance(e, dict):
            continue
        name = str(e.get("dimension", "")).strip().lower()
        try:
            conf = float(e.get("confidence", 1.0))
        except (TypeError, ValueError):
            conf = 1.0
        if name in DIMENSIONS and conf >= floor:
            dims.add(name)
    primary = str(parsed.get("primary", "none")).strip().lower()
    if primary not in DIMENSIONS + ["none"]:
        primary = "none"
    if primary != "none" and primary not in dims:
        primary = "none"
    return {"dims": dims, "primary": primary}


def extract(model, text, memory, mock):
    system = BASE_PROMPT + build_memory_block(memory)
    for _ in range(MAX_RETRIES + 1):
        try:
            raw = fake_model(text, memory) if mock else call_ollama(model, system, text)
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


def run_arm(cases, model, mock, use_corrections, memory_size):
    """
    Walks the cases IN ORDER, like a user living through days.

    CRITICAL: each case is scored using ONLY corrections from EARLIER cases.
    A case never sees its own answer. That's what makes this an honest test of
    generalization rather than memorization.
    """
    memory = []
    results = []

    for i, c in enumerate(cases):
        # --- score FIRST, with whatever memory exists so far
        got = extract(model, c["text"], memory, mock)
        if got is None:
            continue

        correct = (got["primary"] == c["primary"])
        results.append({
            "idx": i, "id": c["id"], "text": c["text"],
            "expected_dims": set(c["expected_dims"]), "primary": c["primary"],
            "got_dims": got["dims"], "got_primary": got["primary"],
            "correct": correct,
            "memory_size_at_time": len(memory),
        })

        # --- THEN, if wrong, the user corrects it and it enters memory
        if use_corrections and not correct:
            memory.append({
                "text": c["text"],
                "dims": list(c["expected_dims"]),
                "primary": c["primary"],
            })
            if len(memory) > memory_size:
                memory.pop(0)   # FIFO: keep the most recent corrections

    return results


def summarise(results, name):
    n = len(results)
    if not n:
        return None
    half = n // 2
    first = results[:half]
    second = results[half:]
    acc = lambda rs: sum(r["correct"] for r in rs) / len(rs) if rs else 0
    return {
        "name": name,
        "overall": acc(results),
        "first_half": acc(first),
        "second_half": acc(second),
        "n": n,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="qwen2.5:3b")
    ap.add_argument("--mock", action="store_true")
    ap.add_argument("--cases", default="test_cases.json")
    ap.add_argument("--memory", type=int, default=6,
                    help="How many past corrections to carry in the prompt (context is limited!)")
    ap.add_argument("--seed", type=int, default=42,
                    help="Shuffle seed. The ORDER matters, so try a few seeds.")
    ap.add_argument("--out", default="v5_results.csv")
    args = ap.parse_args()

    with open(args.cases) as f:
        cases = [c for c in json.load(f) if "text" in c]

    # Shuffle so the case ordering isn't an artefact of how they were written.
    random.seed(args.seed)
    random.shuffle(cases)

    label = "MOCK" if args.mock else args.model
    print(f"\nSPIKE v5 — THE CORRECTION LOOP")
    print(f"Model: {label} | {len(cases)} cases | memory={args.memory} | seed={args.seed}")
    print("\nARM A (control): no corrections")
    print("ARM B (treatment): user corrects mistakes, corrections feed future prompts\n")

    t0 = time.time()
    print("Running ARM A (control)...")
    arm_a = run_arm(cases, args.model, args.mock, use_corrections=False,
                    memory_size=args.memory)
    print("Running ARM B (with corrections)...")
    arm_b = run_arm(cases, args.model, args.mock, use_corrections=True,
                    memory_size=args.memory)
    secs = time.time() - t0

    a, b = summarise(arm_a, "no corrections"), summarise(arm_b, "with corrections")

    print("\n" + "=" * 68)
    print(f"CORRECTION LOOP RESULT — {label}")
    print("=" * 68)
    print(f"{'primary accuracy':<26}{'ARM A':>10}{'ARM B':>10}{'delta':>12}")
    print("-" * 68)
    for key, name in [("first_half", "First half (memory ~empty)"),
                      ("second_half", "Second half (memory full)"),
                      ("overall", "Overall")]:
        d = b[key] - a[key]
        print(f"{name:<26}{a[key]:>9.0%}{b[key]:>10.0%}{d:>+12.0%}")
    print("-" * 68)

    lift = b["second_half"] - a["second_half"]
    print(f"\n  THE NUMBER THAT MATTERS: second-half lift = {lift:+.0%}")
    print("  (that's the effect of corrections on days the model had NEVER seen)")
    print()
    if lift >= 0.10:
        print("  VERDICT: THE LOOP WORKS. Personalized few-shot meaningfully improves")
        print("  accuracy on unseen entries. Build it — it turns your weakest link")
        print("  (the reality gap on messy real writing) into a self-healing system.")
    elif lift >= 0.03:
        print("  VERDICT: MODEST LIFT. Worth building, but sell it as a UX feature")
        print("  ('fix my tags') rather than claiming the AI learns you.")
    elif lift > -0.03:
        print("  VERDICT: NO MEASURABLE LIFT. Still build the correct/confirm step —")
        print("  it prevents wrong tags corrupting the score, and it harvests labels")
        print("  for a future fine-tune. Just don't claim it makes the model smarter.")
    else:
        print("  VERDICT: HARMFUL. The memory block is crowding out the instructions,")
        print("  or the examples are confusing the model. Try --memory 3, or drop it.")
    print()
    print(f"  NOTE: run a few seeds (--seed 1, 2, 3). With only {len(cases)} cases,")
    print("  a single ordering can mislead. Look for a consistent effect.")
    print(f"\n  Elapsed: {secs/60:.1f} min")
    print("=" * 68 + "\n")

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["arm", "order", "id", "text", "expected_primary",
                    "got_primary", "correct", "memory_size_at_time"])
        for arm, rs in [("A_control", arm_a), ("B_corrections", arm_b)]:
            for r in rs:
                w.writerow([arm, r["idx"], r["id"], r["text"], r["primary"],
                            r["got_primary"], r["correct"], r["memory_size_at_time"]])
    print(f"Written to {args.out} — compare the two arms row by row on the same id.\n")


if __name__ == "__main__":
    main()
