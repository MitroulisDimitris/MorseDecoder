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

To hear what the suite is actually asserting against — all 27 fixtures as WAV, plus an
`index.txt` describing each:

```
FIXTURE_WAV_DIR=fixtures gradlew :core:test --tests "*FixtureWavTest*"
```

Worth doing occasionally. A fixture named `speech` that does not sound like speech makes
every assertion resting on it weaker than it reads, and no number of green ticks will
reveal that — only ears will. It cuts the other way too: if `hand-13-rough` sounds like
something no operator would ever send, the 250 % error rate it produces is not the
indictment it appears to be.

## Assessing the decoder

The acceptance criteria are pass/fail at ten fixed points — good regression guards, poor
instruments. They cannot tell you whether a change made the decoder broadly better or
worse, only whether it crossed one of ten lines. A change can degrade every scenario by
four points and still show ten green ticks.

The bench sweeps the space instead, and prints character error rate with no thresholds and
no verdicts:

```
BENCH=1 gradlew :core:test --tests "*DecoderBench*" -i
BENCH=1 BENCH_CSV=bench.csv gradlew :core:test --tests "*DecoderBench*" -i
```

Sixteen scenarios — speed 7 to 35 WPM, SNR +20 to −5 dB, rough fists, drift, wobble,
Farnsworth, hesitation, fading with static, and tones across the band — run against both
the file and live paths. Roughly 40 seconds.

Each column is a **single-change ablation**, so a regression can be attributed rather than
merely detected. Every behaviour added in D-13 is config-gated for exactly this reason:
"was it better before?" is answered by flipping a flag in the current build, not by
checking out an old commit. `preD13` reverts them all at once.

Read two runs side by side; that is the whole point. Note that CER above 100 % is
meaningful — it means the decoder emitted more characters than were sent.

## How the suite is organised

Roughly 92 tests in `:core`, in four layers. The split matters: most real-world failures
are not "CER was 3 % too high at 20 WPM", they are whole categories the point-tests never
visit.

| Layer | File | What it protects |
|---|---|---|
| Accuracy at fixed points | `AcceptanceTest`, `RobustDecoderTest` | The §6 criteria — regression guards |
| **Declining to decode** | `NonMorseTest` | Speech, RTTY, music, hum, knocking, clipping, noise. Most of what a microphone hears is not Morse, so declining is most of the job |
| **Properties of every input** | `InvariantTest` | Determinism, independence from the caller's chunk size, append-only, no crash on NaN/Inf/DC/clipping |
| **Whole sessions** | `SessionSequenceTest` | Noisy lead-in, speed step, tone jump, 30 s pause, truncation, interference arriving mid-transmission |

A fifth file, `HonestFailureTest`, gathers the P-4 propositions — declining rather than
inventing — because they share one root cause and read better together than scattered.

### Two signals, kept separate

A suite where every known defect is written as a test of *current* behaviour is green
while the product is broken. A suite where they all fail outright is red permanently, and
a real regression hides in the noise. Both throw the signal away, so this project keeps
two:

- **Green / red** answers *did this change break something that used to work.*
- **The known-failure count** answers *how are we actually doing.*

Tests marked `@KnownFailure("D-9", …)` assert the behaviour we **want and do not have**.
They fail today; the failure is counted and printed in a summary at the end of every run,
and the build stays green. A typical run ends:

```
KNOWN FAILURES — 13 test(s) across 8 open defect(s)
  D-8   RTTY decodes as Morse            NonMorseTest.RTTY decodes to nothing
  D-9   a tone change strands the lock   SessionSequenceTest.a tone jump ...
  ...
```

The load-bearing half is the other direction: **if a `@KnownFailure` test passes, the
build fails.** Someone fixed the defect, and the annotation has to come off or the list
goes stale and starts lying. A known-failure list nobody prunes is worse than no list.

So "93 tests, 0 failures" is never the whole story — read the known-failure count with it.

### Vacuity guards

Fixtures that could pass for the wrong reason carry an explicit check: `NonMorseTest`
asserts its own non-Morse audio is not accidentally silent, and the ambient, band and
recovery fixtures each assert they genuinely reproduce the failure they were written for.

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
| `known-issues.md` | Open defects and gaps, with the evidence behind each |
| `prior-art.md` | How other Morse decoders solve this, and what we should borrow |
| `version-history.md` | Every commit benchmarked with one ruler — is the decoder improving? |
| `progress.md` | What each commit bought, what is still broken, and the plan from here |
| `export-formats.md` | `.txt` and `.json` schemas |
| `ui-spec.md` | Screen layout and annotation vocabulary |
| `main-screen.html` | Visual reference; open in a browser |
| `handover/` | The original specification handover, kept as delivered |

Read `decisions.md` before changing anything in the estimator. Most of what looks like an
arbitrary constant is defending against a specific failure mode recorded there.

## Status

Version 0.3.0. All six milestones of §7 are implemented: harness, robust timing decoder,
live path, UI, export, assists.

Open defects and gaps are tracked in `known-issues.md` — read it before trusting a decode.
The two that matter most: the live path decodes real hand-sent audio considerably worse
than file import does, and a unit-scale error currently scores a perfect `fit`, so wrong
output can present as confident output.

