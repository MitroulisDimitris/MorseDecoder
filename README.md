# Morse Decoder

An Android app that decodes audible Morse code from the microphone or an imported audio
file. It auto-detects the tone frequency and the sending speed, and is built to tolerate
room noise and human timing variation rather than assume a clean machine-sent signal.

Fully offline. `INTERNET` is deliberately absent from the manifest and must stay absent.

## What it does

- Locks onto a CW tone anywhere in 300–1200 Hz and tracks it as it drifts
- Estimates the sender's speed continuously over 5–40 WPM, no manual setting required
- Decodes hand-sent code with wide timing jitter, Farnsworth spacing, and hesitation
- Says when it cannot hear properly instead of emitting plausible-looking garbage
- Exports the session as `.txt` or `.json`, with a live tap-to-copy transcript

## The principles it is built around

These are load-bearing. Each traces to §1 of the requirements, and breaking one produces
something that looks like it works and isn't the app that was specified.

- **Append-only transcript.** Nothing emitted is ever rewritten or retracted — including
  on the `HH` error prosign and on tone re-lock. Annotate in place, keep appending.
- **Raw output by default.** Every assist ships off. The default decode is what was
  measured, not what was probably meant.
- **Honest failure.** Bad input produces a warning that replaces the output, not a
  decode that quietly degrades. Low signal, an incoherent timing fit, and an implausible
  speed each have their own visible state.
- **Estimate the unit from marks only, never gaps**, and never assume a 3:1 dash/dot
  ratio — it is a weak prior, not a constraint.
- **Re-lock guards count elements, not seconds**, so silence can never trigger a re-lock.

## Layout

| Module | What lives there |
|---|---|
| `:core` | Pure JVM. The entire DSP and decoding pipeline — Goertzel front end, mixtures, Kalman unit tracking, Viterbi lattice, the live engine, synthetic generator, export. No Android dependencies, ever. |
| `:app` | The Android shell only: `AudioRecord`, SAF import, the foreground service, permissions, Compose UI. |

The split is what makes the acceptance criteria runnable as plain JUnit on the desktop —
no emulator, no device, deterministic in CI.

Kotlin, Compose, JVM toolchain 21. `minSdk` 26, `compileSdk` 36.

## Build and test

```
gradlew :core:test          # 65 tests, includes all 10 acceptance criteria
gradlew :app:assembleDebug  # debug APK
```

To generate a WAV for on-device acoustic testing:

```
DEMO_WAV_OUT=demo.wav DEMO_WAV_WPM=20 gradlew :core:test --tests "*DemoWavTest*"
```

## Acceptance criteria

Stated in §6 of the requirements as character error rate at a given SNR and speed. They
apply only within 5–40 WPM and in raw mode with all assists off. AC-1 through AC-10 each
have a test in `:core`; the two worth watching in review are **AC-9** (a good lock
survives 30 s of silence with zero automatic re-locks) and **AC-5** (a drifting stream
costs no more than 2 percentage points of CER against the static baseline).

Synthetic audio drives the suite, with drift models rather than static WPM plus jitter —
ramps, wobble, hesitation, per-character bias. That still does not capture how humans
actually send: **real hand-sent recordings remain the gap in the test corpus.**

## Documentation

| File | What it is |
|---|---|
| `v1-requirements.md` | The spec. Numbered requirements, referenced from everywhere else |
| `pipeline.md` | Signal chain architecture, the three clocks, failure modes |
| `decisions.md` | Why the non-obvious choices are what they are |
| `export-formats.md` | `.txt` and `.json` schemas |
| `ui-spec.md` | Screen layout and annotation vocabulary |
| `main-screen.html` | Visual reference; open in a browser |
| `handover/` | The original specification handover, kept as delivered |

Read `decisions.md` before changing anything in the estimator. Most of what looks like an
arbitrary constant is defending against a specific failure mode recorded there.

## Status

Version 0.3.0. All six milestones of §7 are implemented: harness, robust timing decoder,
live path, UI, export, assists.

Known gaps:

- Not yet exercised on physical hardware — no AVD or `cmdline-tools` configured, so
  everything is verified by unit test and build only
- No real hand-sent recordings in the corpus (see above)
- §8 of the requirements still lists open questions
- `CLAUDE.md` still describes the repo as a specification handover with no code; that
  was true at the first commit and is not any more
