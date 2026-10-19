"""
INSIGHTS LAYER  (deterministic, no AI, free tier)
=================================================

The scoring engine produces NUMBERS. This layer turns a history of those numbers
into the PLAIN-ENGLISH lines a user actually reads:

  "Your professional load has climbed over the past week, and it's been your
   heaviest area for six days running."

It is pure rules — no model, no network, no cost. It is the free-tier experience
and it stands entirely on its own; the LLM journal insights are a separate,
optional enrichment layered on top.

DESIGN PRINCIPLES (these are as important as the rules themselves)
  1. LOAD LANGUAGE, NOT IDENTITY. Always "your professional load is high", never
     "you are burnt out". We describe a state, we never label the person.
  2. NON-DIAGNOSTIC. This is a wellbeing tracker, not a clinical assessment. No
     rule ever implies a diagnosis.
  3. AGENCY, NOT PRESCRIPTION. Suggestions are offered as options ("one thing you
     could try"), never instructions. The user knows their life; we don't.
  4. DON'T ONLY REPORT DOOM. If something is genuinely improving, say so. A
     tracker that only ever surfaces problems trains people to dread opening it.
  5. HEDGE ON THIN DATA. With little history, we say we're still learning rather
     than pronounce confidently on noise.
  6. SAFETY IS PROPORTIONATE. If load stays high for a sustained stretch, surface
     a gentle, non-alarming pointer toward real-world support. Burnout is not a
     crisis, so the copy matches that — supportive, not a red alert.

TUNABLE CONSTANTS below are starting points, to be refined with real data.
"""

from statistics import mean


# ---------------------------------------------------------------------------
# TUNABLE CONSTANTS
# ---------------------------------------------------------------------------

WINDOW = 7             # days in the "recent" window for trend comparison
TREND_DELTA = 6        # overall must move at least this much to be "rising"/"easing"
HIGH_DIM = 50          # a single dimension at/above this is "elevated"
STREAK_DAYS = 5        # consecutive elevated days to call a dimension "persistent"
RISER_DELTA = 8        # a dimension climbing at least this much over the window is notable
LOW_CONF = 0.5         # below this confidence, hedge everything

SAFETY_BAND = 67       # "Burning out" overall
SAFETY_SUSTAINED = 10  # ...held for this many days -> gentle support pointer
SAFETY_ACUTE_LEVEL = 80
SAFETY_ACUTE_DAYS = 4  # ...or very high (>=80) for this many days

MAX_INSIGHTS_SHOWN = 3


# gentle, optional, non-medical micro-actions per dimension
ACTIONS = {
    "physical":     "protect a wind-down window before bed tonight",
    "mental":       "try one single-task stretch — close the other tabs",
    "emotional":    "give the feeling a name, out loud or on paper",
    "social":       "a low-effort message to one person you trust can help",
    "professional": "guard one small boundary tomorrow — even a no-meetings slot",
    "personal":     "carve out fifteen minutes that are only yours",
}


# ---------------------------------------------------------------------------
# small helpers over the history
# ---------------------------------------------------------------------------

def _series(history, key):
    """Pull one dimension's (or 'overall') score across all days, oldest->newest."""
    if key == "overall":
        return [h["overall"] for h in history]
    return [h["dims"][key] for h in history]


def _recent_prior(series, w=WINDOW):
    """Mean of the last w days, and of the w days before that."""
    if len(series) < 2:
        return None, None
    recent = mean(series[-w:])
    if len(series) >= 2 * w:
        prior = mean(series[-2 * w:-w])
    else:
        earlier = series[:-w] if len(series) > w else series[:1]
        prior = mean(earlier)
    return recent, prior


def _trailing_streak(series, threshold):
    """How many of the most recent consecutive days are >= threshold."""
    n = 0
    for v in reversed(series):
        if v >= threshold:
            n += 1
        else:
            break
    return n


# ---------------------------------------------------------------------------
# an insight is just a small dict: {priority, kind, text, dim}
# lower priority number = shown first
# ---------------------------------------------------------------------------

def _ins(priority, kind, text, dim=None):
    return {"priority": priority, "kind": kind, "text": text, "dim": dim}


# ---------------------------------------------------------------------------
# THE RULES  — each looks at the history and returns an insight or None
# ---------------------------------------------------------------------------

def rule_safety(history, labels):
    """Sustained high load -> a gentle, non-diagnostic pointer to real support."""
    overall = _series(history, "overall")
    sustained = _trailing_streak(overall, SAFETY_BAND)
    acute = _trailing_streak(overall, SAFETY_ACUTE_LEVEL)
    if sustained >= SAFETY_SUSTAINED or acute >= SAFETY_ACUTE_DAYS:
        return _ins(
            0, "safety",
            "Your load has stayed high for a while now. This app tracks patterns, "
            "not diagnoses — but if it's felt this heavy for a stretch, talking to "
            "a doctor or someone you trust can genuinely help.",
        )
    return None


def rule_learning(history, labels):
    """Thin data -> say we're still learning rather than over-claim on noise."""
    if len(history) < WINDOW:
        return _ins(
            1, "learning",
            "Still getting to know your baseline. A few more check-ins and the "
            "trends here will get more meaningful.",
        )
    if history[-1].get("confidence", 1.0) < LOW_CONF:
        return _ins(
            1, "learning",
            "Some areas haven't been checked in a while, so this is a rough read "
            "for now — the more regularly you check in, the sharper it gets.",
        )
    return None


def rule_overall_trend(history, labels):
    """Is the overall burnout level rising, easing, or steady?"""
    recent, prior = _recent_prior(_series(history, "overall"))
    if recent is None:
        return None
    delta = recent - prior
    if delta > TREND_DELTA:
        return _ins(2, "trend_up",
                    f"Your overall load has been climbing over the past week "
                    f"(up about {round(delta)} points).")
    if delta < -TREND_DELTA:
        return _ins(6, "trend_down",
                    f"Your overall load has eased over the past week "
                    f"(down about {round(-delta)} points) — worth noticing what helped.")
    return None


def rule_persistent_dimension(history, labels):
    """A single dimension stuck high for several days running."""
    best = None
    for dim in history[-1]["dims"]:
        streak = _trailing_streak(_series(history, dim), HIGH_DIM)
        if streak >= STREAK_DAYS and (best is None or streak > best[1]):
            best = (dim, streak)
    if best:
        dim, streak = best
        return _ins(3, "streak",
                    f"{labels[dim]} has been your heaviest area — elevated "
                    f"for {streak} days running.",
                    dim=dim)
    return None


def rule_riser(history, labels):
    """A dimension climbing fast over the window (catches emerging problems)."""
    if len(history) < 2:
        return None
    best = None
    for dim in history[-1]["dims"]:
        recent, prior = _recent_prior(_series(history, dim))
        if recent is None:
            continue
        delta = recent - prior
        if delta > RISER_DELTA and (best is None or delta > best[1]):
            best = (dim, delta)
    if best:
        dim, delta = best
        return _ins(4, "riser",
                    f"{labels[dim]} has been climbing lately "
                    f"(up about {round(delta)} points).",
                    dim=dim)
    return None


def rule_comovement(history, labels):
    """Two dimensions climbing together. Framed as a PATTERN to notice, never as
    a causal claim — the user knows their own life and can judge."""
    if len(history) < WINDOW:
        return None
    risers = []
    for dim in history[-1]["dims"]:
        recent, prior = _recent_prior(_series(history, dim))
        if recent is not None and (recent - prior) > RISER_DELTA:
            risers.append((dim, recent - prior))
    if len(risers) >= 2:
        risers.sort(key=lambda x: -x[1])
        a, b = risers[0][0], risers[1][0]
        ins = _ins(5, "comovement",
                   f"{labels[a]} and {labels[b]} have been rising together. "
                   f"They often move as a pair — it can be worth noticing what "
                   f"they share for you.",
                   dim=a)
        ins["dim2"] = b
        return ins
    return None


def rule_improvement(history, labels):
    """A dimension genuinely easing -> acknowledge it (don't only report doom)."""
    best = None
    for dim in history[-1]["dims"]:
        recent, prior = _recent_prior(_series(history, dim))
        if recent is None:
            continue
        delta = recent - prior
        if delta < -RISER_DELTA and (best is None or delta < best[1]):
            best = (dim, delta)
    if best:
        dim, delta = best
        return _ins(7, "improvement",
                    f"{labels[dim]} has eased off recently "
                    f"(down about {round(-delta)} points). Good to see.",
                    dim=dim)
    return None


def rule_dominant(history, labels):
    """Fallback: name the current heaviest area, so there's always something."""
    dims = history[-1]["dims"]
    dim = max(dims, key=dims.get)
    if dims[dim] >= HIGH_DIM:
        return _ins(8, "dominant",
                    f"{labels[dim]} is currently your heaviest area.",
                    dim=dim)
    return None


ALL_RULES = [
    rule_safety, rule_learning, rule_overall_trend, rule_persistent_dimension,
    rule_riser, rule_comovement, rule_improvement, rule_dominant,
]


# ---------------------------------------------------------------------------
# PUTTING IT TOGETHER
# ---------------------------------------------------------------------------

def analyze(history, labels):
    """Run every rule, then select what to show.

    Selection logic:
      - safety, if present, always shows (it's priority 0)
      - if we're in 'learning' mode (thin data), show that and stop — don't
        pile trend claims on top of an admission that data is thin
      - otherwise show the top MAX_INSIGHTS_SHOWN by priority, but try to
        include at least one positive (improvement) if one exists, so the read
        is never purely negative
    """
    fired = [r(history, labels) for r in ALL_RULES]
    fired = [i for i in fired if i]
    fired.sort(key=lambda i: i["priority"])

    safety = [i for i in fired if i["kind"] == "safety"]
    learning = [i for i in fired if i["kind"] == "learning"]
    others = [i for i in fired if i["kind"] not in ("safety", "learning")]

    selected = list(safety)

    if learning and len(history) < WINDOW:
        # genuinely thin data: hedge and stop (but keep safety if it fired)
        return selected + learning[:1]

    # DEDUPE: never show two lines about the same dimension. Walk in priority
    # order; skip an insight only if EVERY dimension it mentions is already used.
    used = set()
    deduped = []
    for i in others:
        dims_i = {i.get("dim"), i.get("dim2")}
        dims_i.discard(None)
        if dims_i and dims_i <= used:      # all its dims already spoken for
            continue
        deduped.append(i)
        used |= dims_i
    others = deduped

    # fill remaining slots by priority
    remaining = MAX_INSIGHTS_SHOWN - len(selected)
    picked = others[:remaining]

    # guarantee a positive if one exists and isn't already picked
    positive = next((i for i in others if i["kind"] == "improvement"), None)
    if positive and positive not in picked and picked:
        picked[-1] = positive  # swap the lowest-priority pick for the positive

    return selected + picked


def suggested_action(insights, labels):
    """Pick one gentle action, tied to the most important concern that has a dim."""
    for i in insights:
        if i["kind"] in ("safety", "improvement", "learning"):
            continue
        if i.get("dim") and i["dim"] in ACTIONS:
            return f"One small thing you could try: {ACTIONS[i['dim']]}."
    return None


def render(history, labels):
    """Produce the full user-facing insight card as plain text."""
    latest = history[-1]
    lines = [f"Overall: {round(latest['overall'])} · {latest['band']}", ""]

    insights = analyze(history, labels)
    for i in insights:
        lines.append(f"• {i['text']}")

    action = suggested_action(insights, labels)
    if action:
        lines.append("")
        lines.append(action)

    return "\n".join(lines)
