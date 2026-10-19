# Choosing the on-device model

Four candidate models were run through the same 40-case, hand-labelled test (the
v2 prompt with the evidence rule and negative examples) using Ollama on a laptop.
This is a fair test of model quality but not a perfect stand-in for the real
on-device runtime (LiteRT-LM), which uses different quantization and a
different engine.

Two measures matter most:
- **F1**: overall balance of catching real signals and avoiding false ones.
- **Good days left alone**: on ordinary or good days, how often the model
  correctly flags nothing. A model that invents problems on fine days erodes trust.

A simple keyword-matching baseline scored 70% F1.

| Model | Size | F1 | Good days left alone | Verdict |
|---|---|---|---|---|
| DeepSeek-R1-Distill-Qwen | 1.5B | 49% | 17% | Rejected. Worse than the keyword baseline. Reasoning-style training appears to push it into inventing problems, even on fine days. |
| Qwen2.5-Instruct | 1.5B | 59% | 50% | Rejected. Better than DeepSeek but still below the baseline, and a coin flip on the metric that matters most. |
| Phi-3.5-mini-instruct | 3.8B | 80% | 83% | Best raw scores, but not usable for v1: not available in the `.litertlm` format the on-device runtime needs. A conversion path exists but needs about 64 GB of RAM, so it is deferred. |
| **Phi-4-mini-instruct** | 3.8B | 72% | **100%** | **Chosen for v1.** Left every good day alone, matching the earlier Gemma 3 4B result. It is more cautious and misses roughly a third of real signals. |

## Why a cautious model won

A missed signal is caught anyway by the deterministic scoring layer underneath. A
fabricated problem has no such safety net and risks the user's trust in the app. So
the project deliberately traded some recall for never inventing a problem on a good
day.

## Not yet tested

- Qwen 2.5 3B, a candidate for a higher-end device tier. It predates the "good days
  left alone" measure, so it would need the same validation before being trusted.
- Llama-family models, which the on-device runtime lists as supported.
