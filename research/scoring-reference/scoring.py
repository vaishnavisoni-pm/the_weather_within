"""
BURNOUT SCORING ENGINE
======================

This is the HEART of the product. It takes a person's tap answers and turns them
into a burnout number. It is deliberately:

  - PURE       : inputs in, numbers out. No screen, no database, no AI, no network.
  - DETERMINISTIC : same answers always give the same score. No black box.
  - TESTABLE   : because it's pure, test_scoring.py can prove it behaves correctly
                 on invented 60-day trajectories BEFORE any app is built.

Everything the LLM does is OPTIONAL enrichment on top. This file alone can power a
complete, working, tap-only burnout tracker. That is the whole point of the
architecture: the trustworthy core owes nothing to the AI.

WHAT IT DOES, IN ONE BREATH
  raw tap (0-4)
    -> item contribution (0-100), flipped if the item is reverse-scored
      -> per-facet running estimate (EWMA: gently forgets the past)
        -> dimension score (mean of its 3 facet estimates)
          -> overall score (0.7 * average + 0.3 * worst dimension)
            -> a band: Steady / Strained / Burning out

THE TUNABLE CONSTANTS BELOW ARE HYPOTHESES, NOT FACTS.
  ALPHA, the 0.7/0.3 split, the band cutoffs, and the dimension weights are all
  reasonable starting choices grounded in how burnout is framed clinically, but
  none is validated for THIS instrument yet. They are named and isolated here so
  the beta validation study can tune them against a real instrument (BAT / CBI)
  without touching the logic.
"""

import json
from statistics import mean


# ---------------------------------------------------------------------------
# TUNABLE CONSTANTS  (see the note above — these are starting points, not law)
# ---------------------------------------------------------------------------

ALPHA = 0.25
# The EWMA weight on today's answer. 0.25 means "today counts a quarter, the
# accumulated past counts three quarters" -> roughly a two-week memory, which
# matches how burnout is clinically framed (persistence, not a bad Tuesday).

MEAN_WEIGHT = 0.70   # how much the overall score reflects the AVERAGE dimension
MAX_WEIGHT = 0.30    # how much it reflects the SINGLE WORST dimension
# The max term exists so that concentrated burnout in one area still registers.
# Someone fine everywhere except work is still in trouble; a flat average hides it.

# Per-dimension weight in the overall average. Default = all equal.
# The literature suggests emotional exhaustion is the strongest single signal, so
# up-weighting 'emotional' is a plausible future change — but it is UNVALIDATED,
# so v1 keeps everything equal and leaves this as the obvious knob to turn later.
DIMENSION_WEIGHTS = {
    "physical": 1.0, "mental": 1.0, "emotional": 1.0,
    "social": 1.0, "professional": 1.0, "personal": 1.0,
}

# Band cutoffs on the 0-100 overall score.
BAND_STEADY_MAX = 34      # below this = Steady
BAND_STRAINED_MAX = 67    # below this = Strained; at/above = Burning out

# Neutral starting value for a facet we have no data for yet (mid-scale).
NEUTRAL = 50.0

# Confidence: a facet not sampled within this many days is considered "stale".
STALE_AFTER_DAYS = 6


# ---------------------------------------------------------------------------
# LOADING THE ITEM BANK
# ---------------------------------------------------------------------------

def load_bank(path="item_bank_v2.json"):
    """Reads the item bank JSON and returns (bank, lookup, structure).

    lookup:    item_id -> {"dim", "facet", "reverse", "daily_eligible", "anchor"}
    structure: dim -> [facet_key, facet_key, facet_key]   (in rotation order)
    """
    with open(path, encoding="utf-8") as f:
        bank = json.load(f)

    lookup = {}
    structure = {}
    for dim in bank["dimensions"]:
        dkey = dim["key"]
        structure[dkey] = []
        for facet in dim["facets"]:
            fkey = facet["key"]
            structure[dkey].append(fkey)
            for item in facet["items"]:
                lookup[item["id"]] = {
                    "dim": dkey,
                    "facet": fkey,
                    "reverse": item["reverse"],
                    "daily_eligible": item["daily_eligible"],
                    "anchor": item["anchor"],
                    "text": item["text"],
                }
    return bank, lookup, structure


# ---------------------------------------------------------------------------
# STEP 1 — one tap becomes a 0-100 contribution
# ---------------------------------------------------------------------------

def item_contribution(answer, reverse):
    """Turn a 0-4 tap into a 0-100 burnout contribution.

    Normal item : agreeing (high answer) = more burnout -> answer/4 * 100
    Reverse item: it's worded POSITIVELY, so agreeing = LESS burnout.
                  We flip it: (4 - answer)/4 * 100.

    This flip is why a lazy user who taps '4' down the whole list produces an
    incoherent profile -- a 4 on a reverse item scores 0, contradicting their 4s
    on the normal items. That contradiction is detectable (see detect_careless).
    """
    if answer is None:
        return None
    answer = max(0, min(4, answer))
    if reverse:
        return (4 - answer) / 4 * 100
    return answer / 4 * 100


# ---------------------------------------------------------------------------
# THE STATE — everything the app remembers about one person's scores
# ---------------------------------------------------------------------------

class BurnoutState:
    """Holds the running facet estimates for one user.

    In the real app this lives in SQLite, one row per facet. Here it's just a
    dict. The maths is identical either way.
    """

    def __init__(self, structure):
        self.structure = structure
        # facet_est[dim][facet] = current 0-100 estimate
        self.facet_est = {d: {f: NEUTRAL for f in fs} for d, fs in structure.items()}
        # when each facet was last updated (day index), for staleness/confidence
        self.facet_seen = {d: {f: None for f in fs} for d, fs in structure.items()}
        self.days_logged = 0

    # --- seeding -----------------------------------------------------------

    def seed_from_anchor(self, answers, lookup, day=0):
        """Initialise facet estimates from the onboarding anchor assessment.

        The anchor asks several items per facet, so we can set a solid starting
        value straight away instead of warming up from NEUTRAL for two weeks.
        A facet's seed = the mean contribution of its answered anchor items.
        """
        # gather contributions per (dim, facet)
        buckets = {}
        for item_id, raw in answers.items():
            meta = lookup.get(item_id)
            if not meta:
                continue
            c = item_contribution(raw, meta["reverse"])
            if c is None:
                continue
            buckets.setdefault((meta["dim"], meta["facet"]), []).append(c)

        for (d, f), vals in buckets.items():
            self.facet_est[d][f] = mean(vals)
            self.facet_seen[d][f] = day
        self.days_logged = max(self.days_logged, 1)

    # --- daily update ------------------------------------------------------

    def update(self, day, answers, lookup):
        """Apply one day's answers, advancing each touched facet by exactly ONE
        EWMA step -- no matter how many taps hit it.

        The default daily flow is 6 answers = one facet per dimension. But the
        user may optionally check in on MORE facets, or tap the same facet more
        than once. To keep the trend honest we:
          1. group every tap by the facet it belongs to,
          2. average the taps within each facet (more taps = a more reliable
             value for that facet today),
          3. advance each facet's EWMA by ONE step using that daily average.

        So a motivated day and a lazy day both advance the timeline by one step.
        Extra taps improve COVERAGE and PRECISION; they never change CADENCE.
        This is what stops "how much you tapped" from leaking into the score.
        """
        # 1 + 2: collect and average contributions per facet
        daily = {}   # (dim, facet) -> [contributions]
        for item_id, raw in answers.items():
            meta = lookup.get(item_id)
            if not meta:
                continue
            c = item_contribution(raw, meta["reverse"])
            if c is None:
                continue
            daily.setdefault((meta["dim"], meta["facet"]), []).append(c)

        # 3: one EWMA step per facet, using that facet's daily average
        for (d, f), contribs in daily.items():
            c = mean(contribs)
            prev = self.facet_est[d][f]
            self.facet_est[d][f] = ALPHA * c + (1 - ALPHA) * prev
            self.facet_seen[d][f] = day

        self.days_logged += 1

    # --- reading the scores out -------------------------------------------

    def dimension_score(self, dim):
        """A dimension's score is the mean of its three facet estimates."""
        return mean(self.facet_est[dim].values())

    def dimension_scores(self):
        return {d: self.dimension_score(d) for d in self.structure}

    def overall(self):
        """Blend the (weighted) average dimension with the single worst one."""
        scores = self.dimension_scores()
        wsum = sum(scores[d] * DIMENSION_WEIGHTS[d] for d in scores)
        wtot = sum(DIMENSION_WEIGHTS[d] for d in scores)
        weighted_mean = wsum / wtot
        worst = max(scores.values())
        return MEAN_WEIGHT * weighted_mean + MAX_WEIGHT * worst

    def band(self):
        o = self.overall()
        if o < BAND_STEADY_MAX:
            return "Steady"
        if o < BAND_STRAINED_MAX:
            return "Strained"
        return "Burning out"

    # --- confidence --------------------------------------------------------

    def confidence(self, current_day):
        """A 0-1 estimate of how much to trust the current score.

        Two things lower confidence:
          - too few days logged (needs ~2 weeks to stabilise)
          - stale facets (some facet hasn't been sampled in a long time)
        The app should SHOW this, so a thin-data score never masquerades as solid.
        """
        # data sufficiency: ramps 0 -> 1 over the first 14 days
        sufficiency = min(1.0, self.days_logged / 14)

        # freshness: fraction of facets sampled recently
        total = fresh = 0
        for d, fs in self.facet_seen.items():
            for f, seen in fs.items():
                total += 1
                if seen is not None and (current_day - seen) <= STALE_AFTER_DAYS:
                    fresh += 1
        freshness = fresh / total if total else 0

        conf = 0.5 * sufficiency + 0.5 * freshness
        return round(conf, 2)


# ---------------------------------------------------------------------------
# CARELESS-RESPONSE DETECTION  (data quality guard)
# ---------------------------------------------------------------------------

def detect_careless(answers, lookup):
    """Flag an obvious 'straight-line' response: the same number tapped for every
    item, INCLUDING items that are worded in opposite directions.

    If a session contains at least one reverse and one normal item and every raw
    answer is identical, the person cannot be answering honestly -- agreeing
    equally with 'I felt drained' and 'I felt steady' is a contradiction. Such a
    session should be stored but marked low-quality, not fed to the score as-is.
    """
    has_reverse = has_normal = False
    raws = []
    for item_id, raw in answers.items():
        meta = lookup.get(item_id)
        if not meta or raw is None:
            continue
        raws.append(raw)
        if meta["reverse"]:
            has_reverse = True
        else:
            has_normal = True
    if len(raws) < 2 or not (has_reverse and has_normal):
        return False
    return len(set(raws)) == 1   # every answer identical across opposing items


# ---------------------------------------------------------------------------
# DAILY ITEM ROTATION  (which 6 questions to ask today)
# ---------------------------------------------------------------------------

def select_daily_items(bank, structure, lookup, day):
    """Pick one item per dimension for a given day.

    Facet rotates on a 3-day cycle (facet_index = day % 3). Within a facet, the
    specific item rotates across cycles so the whole bank is covered over ~12
    days. Only daily_eligible items are ever chosen (contaminated/behaviour items
    are excluded here, by construction).
    """
    facet_slot = day % 3
    cycle = day // 3
    chosen = []
    for dim in bank["dimensions"]:
        dkey = dim["key"]
        fkey = structure[dkey][facet_slot % len(structure[dkey])]
        # daily-eligible items in this facet
        facet = next(f for f in dim["facets"] if f["key"] == fkey)
        eligible = [it["id"] for it in facet["items"] if it["daily_eligible"]]
        if not eligible:
            # fall back to any daily-eligible item in the dimension
            eligible = [i for i, m in lookup.items()
                        if m["dim"] == dkey and m["daily_eligible"]]
        chosen.append(eligible[cycle % len(eligible)])
    return chosen


def select_full_checkin(bank, structure, lookup, day):
    """One daily-eligible item for EVERY facet (all 18) -- the optional
    "check in on everything today" path. Covers the whole construct in one
    sitting instead of waiting for the 3-day rotation.
    """
    chosen = []
    for dim in bank["dimensions"]:
        for facet in dim["facets"]:
            eligible = [it["id"] for it in facet["items"] if it["daily_eligible"]]
            if eligible:
                chosen.append(eligible[day % len(eligible)])
    return chosen


def select_dimension_checkin(bank, structure, lookup, dim_key, day):
    """Daily-eligible items for all facets of ONE dimension -- the optional
    "go deeper on my work / my sleep" deep-dive path.
    """
    chosen = []
    dim = next(d for d in bank["dimensions"] if d["key"] == dim_key)
    for facet in dim["facets"]:
        eligible = [it["id"] for it in facet["items"] if it["daily_eligible"]]
        if eligible:
            chosen.append(eligible[day % len(eligible)])
    return chosen
