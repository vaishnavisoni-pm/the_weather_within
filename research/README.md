# Research and design-phase prototypes

Before writing any Android code for The Weather Within, I tested the two riskiest
ideas in Python: (1) can a small language model running locally read a journal
entry and tag the right areas of life, and (2) does a simple, transparent scoring
engine turn daily taps into a trustworthy burnout "weather" reading?

Everything here is reference material. The Android app in this repo is the source
of truth.

## spikes/ — can a small local model read a day and tag it?

Each script is one experiment, run against hand-labelled test cases through a
local Ollama server.

| Script | The question it answers |
|---|---|
| `burnout_spike.py` | Can a small local model tag which kinds of burnout a day description touches, scored against 40 hand-labelled cases? |
| `burnout_spike_v2.py` | Does adding negative examples and a "quote your evidence" rule stop the model over-tagging? (Also supports optional cloud providers; API keys are read from environment variables, never stored.) |
| `burnout_spike_v3.py` | Reality check: how much worse does the model do on messy, real-voice entries than on tidy, AI-written ones? Needs your own `my_cases.json` (format below), which is not included because it is private. |
| `burnout_spike_v4.py` | Cascade test: if the model also labels each area as a driver or a consequence, does accuracy hold, or does asking for more break a small model? |
| `burnout_spike_v5.py` | Does a correction loop (feeding the user's own past corrections back as examples) help, without "stapling the answer to its desk"? |

`MODEL_SELECTION.md` records the head-to-head comparison of candidate models and
why Phi-4-mini was chosen.

To try it: `pip install requests`, then `python burnout_spike.py --mock` checks the
script works with no AI. A real run needs Ollama: `python burnout_spike.py --model <name>`.

### Adding your own test cases (`my_cases.json`)

`burnout_spike_v3.py` compares the model on tidy, AI-written cases against messy
entries written in a real person's voice. The messy set is personal, so it is not
included. To run v3, create a file called `my_cases.json` in the same folder as the
script (or point to it with `--my-cases path/to/file.json`).

It is a JSON list. Each entry needs:

| Field | What to put |
|---|---|
| `text` | The entry, written the way you would really type it when tired: short, lowercase, half-sentences, any language mix. |
| `expected_dims` | Your own label: every area genuinely present, chosen from `physical`, `mental`, `emotional`, `social`, `professional`, `personal`. Use `[]` for an ordinary or good day. |
| `primary` | The single strongest area from the same list, or `"none"` for an ordinary or good day. |
| `id` | A unique number. Starting at 101 keeps it clear of the built-in cases. |

Include at least five ordinary or good days. They are where small models fail most,
by inventing problems that are not there. Any entry whose text starts with
`WRITE YOUR OWN` is skipped, so you can leave placeholders in the file.

Two placeholder examples to copy and replace with your own:

```json
[
  {
    "id": 101,
    "text": "slept badly again. head foggy, snapped at my roommate over the dishes",
    "expected_dims": ["physical", "mental", "emotional"],
    "primary": "physical"
  },
  {
    "id": 102,
    "text": "fine day tbh. finished my tasks, called mum, early night",
    "expected_dims": [],
    "primary": "none"
  }
]
```

## scoring-reference/ — the scoring engine, prototyped in Python first

A pure, deterministic engine (no AI, no network) so that the trustworthy core of the
app owes nothing to the model:

- Each facet keeps a running estimate that gently forgets the past:
  `new = 0.25 * today + 0.75 * previous`
- A dimension score is the average of its facets.
- The overall score is `0.7 * average + 0.3 * worst dimension`, so one heavy area is
  not hidden by good ones.
- `insights.py` turns score history into plain-English, non-diagnostic lines.
- `test_scoring.py` and `test_insights.py` run invented people with known burnout
  trajectories (steady, escalating, recovering, one bad day) and assert the engine
  behaves.

Run from inside the folder: `python test_scoring.py` and `python test_insights.py`.

**Where the shipped app differs from this prototype:** the Python bands are named
Steady / Strained / Burning out, while the app uses the weather metaphor
(Clear / Overcast / Stormy) with the same cutoffs at 34 and 67. The app also
handles reverse-worded questions by flipping the answer labels on screen, so
tapping always means "how I feel", instead of flipping inside the scoring math, and
it no longer includes the careless-response detection tested in the prototype.
