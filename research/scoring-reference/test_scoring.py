"""
PROVING THE SCORING ENGINE BEHAVES
==================================

The engine is only trustworthy if we can SHOW it does the right thing. So we
invent people whose burnout we already know, feed their days through the engine,
and assert the score behaves. If any assertion fails, the engine is wrong and we
find out now -- not after someone's real score misleads them.

Six proofs:
  1. STEADY LOW      -> score stays low, band "Steady"
  2. ESCALATING      -> score climbs into "Burning out"
  3. RECOVERING      -> score falls back down
  4. ONE BAD DAY     -> a single spike is ABSORBED, not mistaken for burnout
  5. REVERSE SCORING -> a consistent low person scores low (needs the flip to work)
  6. CARELESS        -> a straight-liner is flagged; an honest responder is not

Run:  python test_scoring.py
"""

from scoring import (
    load_bank, BurnoutState, item_contribution, detect_careless,
    select_daily_items,
)

bank, lookup, structure = load_bank("item_bank_v2.json")


# ---------------------------------------------------------------------------
# helper: simulate a person whose intended burnout level is `level` (0-100)
# by answering each selected item so its CONTRIBUTION lands near `level`,
# correctly flipping reverse items (i.e. an internally consistent person).
# ---------------------------------------------------------------------------

def answer_at_level(item_ids, level):
    """Return {item_id: raw 0-4} for a consistent person at this burnout level."""
    target_raw = round(level / 100 * 4)          # e.g. level 75 -> raw 3
    answers = {}
    for iid in item_ids:
        if lookup[iid]["reverse"]:
            answers[iid] = 4 - target_raw        # flip for reverse items
        else:
            answers[iid] = target_raw
    return answers


def run_trajectory(levels, seed_level=None):
    """Feed a sequence of daily burnout levels through the engine.
    Returns the list of overall scores, one per day, plus the final state."""
    state = BurnoutState(structure)

    # optional anchor seeding at the starting level (like onboarding)
    if seed_level is not None:
        anchor_ids = [i for i, m in lookup.items() if m["anchor"]]
        state.seed_from_anchor(answer_at_level(anchor_ids, seed_level), lookup, day=0)

    overalls = []
    for day, level in enumerate(levels):
        todays = select_daily_items(bank, structure, lookup, day)
        state.update(day, answer_at_level(todays, level), lookup)
        overalls.append(round(state.overall(), 1))
    return overalls, state


PASS, FAIL = [], []
def check(name, condition, detail=""):
    (PASS if condition else FAIL).append(name)
    mark = "PASS" if condition else "FAIL"
    print(f"  [{mark}] {name}" + (f"  — {detail}" if detail else ""))


print("\n" + "=" * 64)
print("SCORING ENGINE — BEHAVIOUR PROOFS")
print("=" * 64)

# --- direct unit checks on the reverse-scoring primitive -------------------
print("\nReverse-scoring primitive:")
check("normal 0 -> 0",   item_contribution(0, False) == 0)
check("normal 4 -> 100", item_contribution(4, False) == 100)
check("reverse 0 -> 100", item_contribution(0, True) == 100,
      "agreeing '0' with a positive item = max burnout")
check("reverse 4 -> 0",   item_contribution(4, True) == 0,
      "agreeing '4' with a positive item = min burnout")

# --- 1. STEADY LOW ---------------------------------------------------------
print("\n1. Steady low (60 days at level 15):")
o, st = run_trajectory([15] * 60, seed_level=15)
check("stays in Steady band", st.band() == "Steady", f"final overall={o[-1]}, band={st.band()}")
check("score stays low", o[-1] < 34, f"final={o[-1]}")

# --- 2. ESCALATING ---------------------------------------------------------
print("\n2. Escalating (60 days, level 15 -> 90):")
levels = [15 + (90 - 15) * d / 59 for d in range(60)]
o, st = run_trajectory(levels, seed_level=15)
check("ends in Burning out band", st.band() == "Burning out", f"final={o[-1]}, band={st.band()}")
check("score rose substantially", o[-1] - o[0] > 45, f"start={o[0]} -> end={o[-1]}")
check("rise is gradual, not instant", o[5] < 35, f"day5 overall={o[5]} (still absorbing)")

# --- 3. RECOVERING ---------------------------------------------------------
print("\n3. Recovering (60 days, level 85 -> 20):")
levels = [85 - (85 - 20) * d / 59 for d in range(60)]
o, st = run_trajectory(levels, seed_level=85)
check("starts high", o[0] > 65, f"start overall={o[0]}")
check("ends much lower", o[-1] < 45, f"end overall={o[-1]}")
check("net decline is large", o[0] - o[-1] > 35, f"drop={round(o[0]-o[-1],1)}")

# --- 4. ONE BAD DAY --------------------------------------------------------
print("\n4. One bad day (steady 20, single spike to 100 on day 30):")
levels = [20] * 60
levels[30] = 100
o, st = run_trajectory(levels, seed_level=20)
pre = o[29]
spike = o[30]
check("spike is absorbed, not 'Burning out'", spike < 45,
      f"day29={pre} -> day30={spike} (a full-100 day)")
check("spike moves score only modestly", spike - pre < 12,
      f"jump={round(spike-pre,1)} points from one terrible day")
check("score recovers afterwards", o[40] < spike, f"day40={o[40]} < spike {spike}")

# --- 5. REVERSE SCORING END-TO-END -----------------------------------------
print("\n5. Reverse scoring (a genuinely low-burnout, consistent person):")
# This person flips reverse items correctly. If the engine IGNORED reverse,
# their reverse answers would score ~100 and drag the overall toward 50.
o, st = run_trajectory([15] * 40, seed_level=15)
check("consistent low person scores low", st.overall() < 30,
      f"overall={round(st.overall(),1)} (would be ~50 if reverse were ignored)")

# --- 6. CARELESS DETECTION -------------------------------------------------
print("\n6. Careless-response detection:")
anchor_ids = [i for i, m in lookup.items() if m["anchor"]]
straight = {iid: 4 for iid in anchor_ids}                 # taps 4 for everything
honest = answer_at_level(anchor_ids, 30)                   # consistent, flips reverses
check("straight-liner is flagged", detect_careless(straight, lookup) is True,
      "same tap on opposing items = contradiction")
check("honest responder is NOT flagged", detect_careless(honest, lookup) is False)

# --- confidence sanity -----------------------------------------------------
print("\n7. Confidence behaves:")
_, st_new = run_trajectory([20] * 3, seed_level=20)
_, st_est = run_trajectory([20] * 30, seed_level=20)
c_new = st_new.confidence(current_day=2)
c_est = st_est.confidence(current_day=29)
check("thin data -> lower confidence", c_new < c_est, f"3 days={c_new} vs 30 days={c_est}")

# --- 8. FLEXIBLE CHECK-IN: extra taps must not distort the cadence -----------
print("\n8. Optional extra taps (unlimited input, stable measurement):")
from scoring import select_full_checkin, select_dimension_checkin

# 8a. Two taps on the SAME facet in one day = ONE EWMA step on their AVERAGE,
#     not two steps. Proven against a hand-computed value.
st = BurnoutState(structure)
phys_fatigue = [i for i, m in lookup.items()
                if m["dim"] == "physical" and m["facet"] == "fatigue" and not m["reverse"]]
i1, i2 = phys_fatigue[0], phys_fatigue[1]
st.facet_est["physical"]["fatigue"] = 20.0
st.update(0, {i1: 4, i2: 2}, lookup)                 # taps 4(=100) and 2(=50)
expected = 0.25 * 75 + 0.75 * 20                      # avg 75 -> one step -> 33.75
got = st.facet_est["physical"]["fatigue"]
check("two taps on one facet = one averaged step", abs(got - expected) < 0.01,
      f"got={round(got,2)}, expected={expected}")

# 8b. A full check-in covers all 18 facets in a single day.
st2 = BurnoutState(structure)
full = select_full_checkin(bank, structure, lookup, day=0)
st2.update(0, answer_at_level(full, 60), lookup)
covered = sum(1 for d in structure for f in structure[d] if st2.facet_seen[d][f] == 0)
check("full check-in covers all 18 facets", covered == 18, f"covered={covered}")

# 8c. A fuller check-in raises confidence faster than the light 6-tap one.
st_light, st_full = BurnoutState(structure), BurnoutState(structure)
light = select_daily_items(bank, structure, lookup, 0)
st_light.update(0, answer_at_level(light, 30), lookup)
st_full.update(0, answer_at_level(full, 30), lookup)
check("fuller check-in -> higher confidence",
      st_full.confidence(0) > st_light.confidence(0),
      f"full={st_full.confidence(0)} vs light={st_light.confidence(0)}")

# 8d. A single-dimension deep-dive touches only that dimension's 3 facets.
st3 = BurnoutState(structure)
deep = select_dimension_checkin(bank, structure, lookup, "professional", day=0)
st3.update(0, answer_at_level(deep, 70), lookup)
prof_fresh = sum(1 for f in structure["professional"] if st3.facet_seen["professional"][f] == 0)
other_fresh = sum(1 for d in structure if d != "professional"
                  for f in structure[d] if st3.facet_seen[d][f] == 0)
check("deep-dive updates only that dimension", prof_fresh == 3 and other_fresh == 0,
      f"professional fresh={prof_fresh}, others fresh={other_fresh}")

# ---------------------------------------------------------------------------
print("\n" + "=" * 64)
print(f"RESULT: {len(PASS)} passed, {len(FAIL)} failed")
if FAIL:
    print("FAILED: " + ", ".join(FAIL))
    print("The engine is WRONG. Do not build on it until these pass.")
else:
    print("All behaviour proofs pass. The scoring core is trustworthy.")
print("=" * 64 + "\n")

# A small illustrative print so you can SEE a trajectory
print("Illustration — an escalating month, overall score every 5 days:")
levels = [15 + (90 - 15) * d / 59 for d in range(60)]
o, _ = run_trajectory(levels, seed_level=15)
for d in range(0, 60, 5):
    bar = "#" * int(o[d] / 3)
    print(f"  day {d:>2}: {o[d]:>5.1f}  {bar}")
print()
