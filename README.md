# The Weather Within

A private, on-device Android app that tracks burnout across six areas of life
and turns it into a simple weather forecast: Clear, Overcast or Stormy.

> Status: personal project in active development. Not a medical device. It
> tracks patterns, not diagnoses.

## What it does
- **Daily check-in:** six taps a day across Body, Mind, Mood, People, Work and Self.
- **Weather score:** answers are smoothed over time (an exponentially weighted
  average), so one bad day doesn't define the forecast.
- **Journal:** write freely, and an on-device AI model suggests which areas the
  entry touches. You confirm or correct before anything is saved.
- **Insights:** trends, streaks and gentle prompts, worded according to how
  much data the app has.

## Privacy by design
- All data stays on the phone, in a local database encrypted with SQLCipher.
- The encryption key is generated on the device and protected by the Android Keystore.
- The journal AI (Phi-4-mini, via LiteRT-LM) runs on the device. The internet is
  used only for the one-time ~3.9 GB model download.

## Product decisions
- **On-device over cloud AI:** journal entries are the most sensitive data in
  the app, privacy was chosen over speed. The trade-off is slow extraction,
  roughly 2 to 3 minutes per entry on a phone CPU.
- **Model selection:** Compared several small language models with a test
  harness before choosing Phi-4-mini.
- **"Tapping always means how I feel":** Redesigned the answer scale so that
  Clear is always the good end, even for positively worded questions.
- **Distribution:** shared directly as an APK.

## Built with
Kotlin, Jetpack Compose, Room, SQLCipher, LiteRT-LM.
