# Morse decoder — project context

Android app that decodes audible Morse code from a microphone or an imported audio file,
auto-detecting tone frequency and sending speed, tolerant of environmental noise and
human timing variation.

**Status: working app, 12 commits.** Both decode paths are implemented and tested, the
Compose UI is built, and the whole decoder is covered by a JVM test suite. It has **never
run on a physical device** — see `known-issues.md` G-2, the largest open risk.

## Read first

| File | What it is |
|---|---|
| `progress.md` | **Start here.** What each commit bought, what is broken, and the plan |
| `v1-requirements.md` | The spec. Numbered requirements, referenced everywhere else |
| `known-issues.md` | Open defects and the evidence behind each |
| `decisions.md` | Why the non-obvious choices are what they are (D-1 … D-17) |
| `version-history.md` | Every commit benchmarked with one ruler — is the decoder improving? |
| `pipeline.md` | Signal chain architecture, the three clocks, failure modes |
| `prior-art.md` | How other Morse decoders solve this, and what we should borrow |
| `export-formats.md` | `.txt` and `.json` schemas |
| `ui-spec.md` | Screen layout and annotation vocabulary |
| `main-screen.html` | Visual reference, open in a browser |
| `handover/` | The original specification snapshot. Historical — do not edit |

**Numbering trap:** `decisions.md` and `known-issues.md` each have their own D-series and
they are *not* the same sequence. Write `decisions D-16` or `known-issues D-6`, never a
bare "D-6".

## Layout

```
core/   pure Kotlin/JVM, zero Android dependencies — all DSP and decoding
        dsp/     Goertzel, Spectrum, RollingPercentiles, KeyingSpread, Bootstrap
        decode/  FrontEnd, MorseDecoder (file), RobustTimingDecoder, MorseTrie
        live/    LiveDecoderEngine — the streaming path and lock state machine
        model/   GaussianMixture, KalmanUnit, ObservationFit
        synth/   SyntheticMorseGenerator — drift, jitter, fading, impulses
        export/  transcript and session formats, cut numbers
app/    Android — Compose UI, foreground service, mic capture, SAF import
```

Kotlin, Compose, `minSdk 26`, `compileSdk`/`targetSdk 36`. **`core` is a plain JVM module
on purpose**: every decoder test runs without an emulator, which is what makes the suite
usable at all given G-2.

Two decode paths, and they fail differently — always check both:

- **File** — `MorseDecoder.decodeFile`, two-pass (FM-1). Fits duration clusters over the
  whole recording, then decodes with locked parameters. No warmup.
- **Live** — `LiveDecoderEngine`, streaming. Bootstraps from the first 8 marks and tracks
  from there. Fragile about where it starts (`known-issues.md` D-2).

## Invariants

These are load-bearing. Violating one produces something that looks like it works and
isn't the app that was specified. Each traces to a principle in §1 of the requirements.

- **Append-only transcript.** Nothing emitted is ever rewritten or retracted — including
  on the `HH` error prosign and on tone re-lock. Annotate in place, keep appending.
- **Raw output by default.** All assists (lexicon, cut numbers) ship off. The default
  decode is what was measured, not what was probably meant.
- **Estimate the unit from marks only.** Never from gaps. Three independent reasons in
  `decisions.md` D-2.
- **Never assume a 3:1 dash/dot ratio.** Weak prior only — real fists run 2:1 to 2.5:1.
- **Lattice decoding is always on.** It is not a "correction" — see `decisions.md` D-5.
  Disabling it to satisfy the raw-output principle would be a misreading.
- **Re-lock guards count elements, not seconds.** Silence must never trigger a re-lock.
- **No network permission.** Fully offline, no exceptions.

## Testing

```sh
./gradlew :core:test                                             # full suite, ~106 tests
BENCH=1 ./gradlew :core:test --tests "*DecoderBench*" -i         # scenario matrix + ablations
BENCH=1 ./gradlew :core:test --tests "*StructureBench*" -i       # timing stats (the D-8 question)
DIAG=1  ./gradlew :core:test --tests "*BootstrapDiagnostic*" -i  # what the live seed actually sees
DIAG=1  ./gradlew :core:test --tests "*FileGateDiagnostic*" -i   # which file-path guard declined, and what it cost
DIAG=1  ./gradlew :core:test --tests "*ResidualDiagnostic*" -i   # which stage the remaining error comes from
```

Benches assert nothing and are skipped unless their env var is set. They are instruments,
not guards — reading two runs side by side is the point.

**The `@KnownFailure` harness is the core convention.** Every open defect has at least one
test asserting the behaviour we want, annotated `@KnownFailure("D-n")`. Those tests fail
today and are listed in a summary at the end of each run. Fix a defect and its test starts
passing, which **fails the build** until the annotation and the `known-issues.md` entry are
both removed. This is what stops the docs and the suite drifting apart.

`HistoryBench` is deliberately **not** in the repo — it is a portable probe written against
only the API that has existed since the first commit, so it drops into any checkout and
re-measures history with one ruler. Procedure at the bottom of `version-history.md`.

Acceptance criteria are §6 of the requirements, stated as character error rate at given SNR
and speed. They only apply within 5–40 WPM and in raw mode (assists off). Two guards worth
keeping green: **AC-9** (a good lock survives 30 s of silence with zero automatic re-locks)
and **AC-5** (a drifting stream costs no more than 2 points of CER versus the static
baseline).

## How to work on this

Each of these is a measured result, not a preference.

- **Measure before and after**, with `HistoryBench` or `DecoderBench`. Numbers quoted from
  different sessions are not comparable — that is precisely why `version-history.md` exists.
- **Mean CER alone is not an acceptance signal.** `cd034af` made the mean worse and was
  correct. Read the worst-case row and the non-Morse column too.
- **Never tune a threshold on synthetic fixtures alone.** Fitted to the 16 synthetic rows,
  the D-8 structure gate lands on a threshold that rejects genuine hand-sent Morse and
  accepts RTTY — the exact inversion of what was wanted. See `known-issues.md` D-8.
- **A test that starts passing is not automatically a fix.** `decisions.md` D-17 made the
  D-1 known-failure test pass by removing the input that triggered it, not by fixing the
  defect. Establish *why* before closing anything.
- **Synthetic jitter does not capture how humans send.** Real hand-sent recordings remain
  the gap in the corpus (`known-issues.md` G-1), and that is a High risk, not a
  nice-to-have.

## Where it stands

Live path mean CER **12.3 %** across 16 scenarios on `DecoderBench` (worst decodable scenario
`hand-13-rough` **65.8 %**), since D-20 made the live decode chunk-invariant and D-21 added the
periodic re-bootstrap. File path mean
**6.6 %** on `DecoderBench` since D-19 locked the pass-2 decode unit (`hand-13-rough`
**36.7 %**). The older `HistoryBench` live figures (mean 13.1 %, worst 65.9 %) **predate D-20
and would move if re-run**; `HistoryBench` is not in the repo. Rulers and text lengths are not
interchangeable — see the caveat in `version-history.md`. All six milestones of §7 of the
requirements are implemented, assists included — except FR-7.4's master switch, which is
deliberately absent (`decisions.md` D-12).

Open High defects: `known-issues.md` D-1, D-2, D-6, D-8, D-9, plus risks G-1 and G-2. The
ordered plan — what is cheap, what is blocked, and what changed the priorities — is in
`progress.md`.

## Undecided

§8 of the requirements is still open: export-with-audio and practice mode. Several other
decisions are waiting on the owner and are collected at the end of `progress.md`. Ask
rather than guessing on those; some of them change the architecture.
