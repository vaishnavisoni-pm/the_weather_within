"""
INSIGHTS LAYER — DEMO + CHECKS
==============================

Runs realistic trajectories through the REAL scoring engine, feeds the day-by-day
scores into the insights layer, and prints what a user would actually see. Also
asserts the important rules fire when they should (and, crucially, that the safety
rule does NOT fire on ordinary days).

Run:  python test_insights.py
"""

from scoring import load_bank, BurnoutState, select_daily_items
import insights as I

bank, lookup, structure = load_bank("item_bank_v2.json")
LABELS = {d["key"]: d["label"] for d in bank["dimensions"]}


# --- build a per-day history by running the real engine --------------------

def answer_at(item_ids, level):
    raw = round(level / 100 * 4)
    return {iid: (4 - raw if lookup[iid]["reverse"] else raw) for iid in item_ids}


def build_history(daily_levels, seed=None):
    """daily_levels: either a single int per day (all dims same), or a dict
    {dim: level} per day for dimension-specific control."""
    state = BurnoutState(structure)
    if seed is not None:
        anchor = [i for i, m in lookup.items() if m["anchor"]]
        state.seed_from_anchor(answer_at(anchor, seed), lookup, day=0)

    history = []
    for day, spec in enumerate(daily_levels):
        todays = select_daily_items(bank, structure, lookup, day)
        if isinstance(spec, dict):
            # dimension-specific: each item answered at its dimension's level
            answers = {}
            for iid in todays:
                lvl = spec.get(lookup[iid]["dim"], 20)
                raw = round(lvl / 100 * 4)
                answers[iid] = 4 - raw if lookup[iid]["reverse"] else raw
        else:
            answers = answer_at(todays, spec)
        state.update(day, answers, lookup)
        history.append({
            "day": day,
            "overall": state.overall(),
            "band": state.band(),
            "dims": state.dimension_scores(),
            "confidence": state.confidence(day),
        })
    return history


PASS, FAIL = [], []
def check(name, cond):
    (PASS if cond else FAIL).append(name)
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}")


def kinds(history):
    return {i["kind"] for i in I.analyze(history, LABELS)}


print("\n" + "=" * 66)
print("INSIGHTS LAYER — CHECKS")
print("=" * 66)

# 1. thin data -> learning message, no confident trend claims
h = build_history([25] * 3, seed=25)
check("thin data shows 'learning'", "learning" in kinds(h))
check("thin data makes no trend claim", "trend_up" not in kinds(h))

# 2. a clearly escalating person (whole-person ramp) -> rising trend + a flag
levels = [min(88, 20 + d * 2.5) for d in range(24)]
h = build_history(levels, seed=20)
k = kinds(h)
check("escalation shows rising trend", "trend_up" in k)
check("escalation flags a persistent/rising dimension", bool({"streak", "riser", "dominant"} & k))
check("no safety flag on a normal escalation", "safety" not in k)

# 3. sustained very high -> safety fires
h = build_history([95] * 30, seed=80)
check("sustained high load triggers safety", "safety" in kinds(h))

# 4. ordinary steady day -> NO safety (the critical false-positive check)
h = build_history([25] * 40, seed=25)
check("steady low load does NOT trigger safety", "safety" not in kinds(h))

# 5. recovering -> improvement acknowledged
levels = [max(20, 85 - d * 1.6) for d in range(45)]
h = build_history(levels, seed=85)
check("recovery is acknowledged positively", "improvement" in kinds(h) or "trend_down" in kinds(h))

print("\n" + "-" * 66)
print(f"{len(PASS)} passed, {len(FAIL)} failed" +
      ("  FAILED: " + ", ".join(FAIL) if FAIL else ""))
print("-" * 66)


# ---------------------------------------------------------------------------
# WHAT THE USER ACTUALLY SEES  — three real scenarios
# ---------------------------------------------------------------------------

def show(title, history):
    print("\n" + "=" * 66)
    print(title)
    print("=" * 66)
    print(I.render(history, LABELS))


# Scenario A: work-driven escalation, physical rising alongside
levels = [{"professional": 22 + d * 1.3, "physical": 20 + d * 0.9,
           "emotional": 20 + d * 0.6} for d in range(40)]
show("SCENARIO A — a hard month at work", build_history(levels, seed=22))

# Scenario B: recovering after a rough patch
levels = [max(22, 80 - d * 1.5) for d in range(40)]
show("SCENARIO B — coming back from a rough patch", build_history(levels, seed=80))

# Scenario C: sustained high (safety pathway)
show("SCENARIO C — sustained heavy load", build_history([90] * 28, seed=78))

# Scenario D: brand new user, only a few days in
show("SCENARIO D — first few days", build_history([30] * 4, seed=30))

print()
