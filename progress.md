# Progress and implementation plan

What has been built, what each change actually bought, and what to do next.

This file is the narrative. The other three are its sources, and where a number appears
here it comes from one of them:

| File | Role |
|---|---|
| `version-history.md` | Every commit benchmarked with one ruler — the measurements |
| `known-issues.md` | Open defects and the evidence behind each |
| `decisions.md` | Why the non-obvious choices are what they are |

Note on numbering: `decisions.md` and `known-issues.md` each have their own D-series and
they are **not** the same sequence. Decision records are written `decisions D-16`, defects
`known-issues D-6`.

---ts recovery", with the D-17 row completing the time series 69 % → 308 % → 207 % → 65.9 %. Finding 1 became "attributable to two commits" and the mean updated to 13.1 %.
- prior-art.md — this one mattered more than it looks. Idea 1 (soft-decision front end) was argued on a worked example: "a marginal threshold decision becomes an element-boundary error becomes a 4× unit error." That chain
  turned out to be shorter than claimed — the threshold did fragment one dash, but the 4× came from the seed accepting the fragment, not from the architecture. The example is withdrawn and the principle kept, with a note that
  the idea needs a failure surviving contact with the cheap fixes before it becomes the largest change in the project. Idea 2's stale "reports 53 WPM" is now marked half-done, half-measured-wrong.
- decisions.md — D-17 record: the diagnosis, why membership was rejected as the test, the measurements, and both consequences.
- known-issues.md — D-6 downgraded from "catastrophically" to "badly" with the new numbers and history table; the answered "worth checking whether the two-means bootstrap..." line removed. D-1 gained the reproducer-lost
  section.
- progress.md — step 1 marked done with its measurements; new steps 1b (port the guard to the file path) and 4a (restore a D-1 reproducer); step 6 re-scoped; headline figures updated.
- README.md — doc table rows for both new files.

Not touched: CLAUDE.md, which still describes the repo as a specification handover with no code. known-issues.md lists that as deliberately the owner's call since rewriting it changes how agents work here.

Two things worth carrying forward from this commit. The plan's largest item lost its main justification — that's a good outcome, but it means step 6 shouldn't start until someone measures where the remaining 65.9 %
accumulates. And D-1, a High defect, now has nothing failing to hold it; the honest fix is a new reproducer, not a closed entr

## Where we are — 2026-08-03

Eleven commits over six days. Both decode paths exist and work, the app builds, and the
live path handles machine-sent code across the whole bench matrix at low single-digit
error. Three things are true at the same time:

- **Machine-sent code is solved.** 11 of 16 live scenarios sit at or below 4 % CER.
- **A rough hand-sent fist is not.** The worst scenario decodes at 65.9 % — much improved
  by D-17, still two thirds wrong, and nothing in the system warns that it is wrong.
- **Nothing has ever run on a phone.** Every result is from unit tests.

| | first commit | now | source |
|---|---|---|---|
| Live path, mean CER | 89.7 % | **13.1 %** | `version-history.md`, D-17 |
| Live path, worst scenario | 227 % | **65.9 %** | `version-history.md`, D-17 |
| File path, mean CER | 21.8 % | **15.6 %** | `version-history.md` |
| Non-Morse fixtures emitting nothing | 1 of 5 | 3 of 5 | `version-history.md` |

The mean improved 7×. The worst case improved 3.4×, almost all of it in the last change
(D-17), which was the first to target the worst case rather than the mean.

---

## The commits

Two commits (`4fd2154`, `dddb819`) are README and UI copy only and are omitted.

| # | Commit | Date | Shorthand | Live mean CER after |
|---|---|---|---|---|
| 1 | `ad5479d` | 07-29 | init | 89.7 % |
| 2 | `cd034af` | 07-29 | gate+cap | 95.0 % |
| 3 | `cb358e7` | 07-29 | thr+launch | 95.0 % |
| 4 | `6c250a3` | 07-30 | D-13 band2k | **32.7 %** |
| 5 | `bddb514` | 07-31 | test harness | — (tests only) |
| 6 | `65c1f56` | 07-31 | UI + bench | — (UI only) |
| 7 | `8d571ed` | 07-31 | D-4/D-5 | 32.7 % |
| 8 | `92fd11e` | 08-03 | FR-2.7 window | 32.6 % |
| 9 | `9b52819` | 08-03 | clamp-suppress | **21.9 %** |
| 10 | `7d1a2b5` | 08-03 | D-17 seed guard | **13.1 %** |

### 1 · `ad5479d` — init

**Problem.** Nothing existed. The repo was a specification handover.

**What changed.** The whole decoder in one commit: file path (two-pass, FM-1) and live path
(streaming), Goertzel envelope, FFT tone search, Gaussian-mixture duration clustering,
Kalman unit tracking, Viterbi lattice decoding, the Compose UI, export, and the assists.

**Measured.** Live mean 89.7 %, file mean 21.8 %. Ten of sixteen live scenarios sat at
100 % CER — decoding nothing usable at all — because the tone search topped out at 1200 Hz
and picked peaks by raw power.

### 2 · `cd034af` — gate incoherent input, cap the speed estimate

**Problem.** Ambient room noise decoded as text and drove the speed estimate past 100 WPM.

**What changed.** An output gate keyed off `modelFitQuality`, plus the 60 WPM ceiling
(`decisions.md` D-11): a bootstrap implying more than 60 WPM is discarded, and the Kalman
unit is clamped so tracking cannot walk past it.

**Measured.** Ambient-tone noise fell from 311 characters to 14. Live mean got **worse** —
89.7 → 95.0 — because the gate also suppressed real output on `wobble` (46.8 → 100) and
`qsb-impulse` (68.2 → 100).

**Worth remembering.** This commit was correct and the mean said it was a regression. It is
the reason mean CER is not used alone as an acceptance signal here.

### 3 · `cb358e7` — keying threshold, launch into listening

**Problem.** The user had to press a button to start, and the keying threshold was
invisible so a bad decode gave no diagnostic.

**What changed.** Threshold telemetry published for the UI; the app opens straight into
listening; emission suppressed on a *run* of poor-fit marks rather than a single one;
re-lock guards counted in marks, never seconds (`decisions.md` D-7).

**Measured.** Live mean flat at 95.0 %. This was plumbing and UX, not decode quality.

### 4 · `6c250a3` — search to 2000 Hz, pick peaks by keying (D-13)

**Problem.** A field recording at 1882 Hz could not be locked at all: the band stopped at
1200 Hz, and within the band the loudest peak won, which is often hum or hiss.

**What changed.** Band widened to 300–2000 Hz; peak selection now prefers peaks that are
*keying* over peaks that are merely loud; dead-end recovery abandons a lock that has never
produced a character.

**Measured.** The single biggest change in the project. Live mean **95.0 → 32.7**. Eleven
scenarios that were producing nothing at all started decoding.

**What it also did.** Made `hand-13-rough` **4× worse** — 69 % → 308 % — and this was not
noticed for three commits. The pre-D-13 decoder could barely lock onto that signal, so it
under-emitted. D-13 gave it a reliable lock on a signal whose unit estimate is 4× wrong,
and a reliable lock plus a wrong unit produces a flood of wrong characters instead of a
trickle. See `known-issues.md` D-6.

### 5 · `bddb514` — test harness

**Problem.** Defects were being described in prose with nothing failing in CI to hold them.

**What changed.** The `@KnownFailure` harness — every open defect gets a test asserting the
behaviour we want, which fails today, is counted at the end of each run, and **fails the
build** once it starts passing until the annotation and the `known-issues.md` entry are
both removed. Plus `NonMorseTest`, `InvariantTest`, `SessionSequenceTest`, the non-Morse
audio fixtures, and a WAV dumper so fixtures can be listened to.

**Measured.** No decoder change. This is the reason the file and the suite cannot drift
apart, which is what makes every number in `known-issues.md` trustworthy.

### 6 · `65c1f56` — draggable search range, decoder bench

**Problem.** No instrument. The §6 acceptance criteria are ten pass/fail points, which
cannot tell you whether a change made the decoder broadly better or worse.

**What changed.** `DecoderBench` — a 15-scenario sweep with per-behaviour ablation
variants, so a regression can be *attributed* rather than merely detected. UI: the spectrum
strip became the range control (`decisions.md` D-14).

**Measured.** No decoder change. Every subsequent commit's numbers come from this tool.

### 7 · `8d571ed` — report speed range and fit honestly (D-4, D-5)

**Problem.** Two ways the UI displayed confidence it did not have. The out-of-range flag
was computed from the pass-1 bootstrap while the displayed speed came from the pass-2
Kalman, so a 58.95 WPM decode showed no warning. And `fitQuality` returned a neutral `1.00`
before any data existed, rendering "no measurement" identically to "perfect fit".

**What changed.** The flag derives from the reported unit. `fitQuality` is `Double?`, null
until the window fills, shown as a muted `—`. Nullable rather than a sentinel on purpose:
it forces every call site to decide which case it holds.

**Measured.** Live mean unchanged at 32.7 %. Correctness of *reporting*, not of decoding.

### 8 · `92fd11e` — the analysis window follows the speed (FR-2.7, D-15)

**Problem.** FR-2.7 always specified a window that adapts to the sending speed. It never
did — `windowMs` was a fixed 10 ms from the first commit, which is a 100 Hz-wide filter.
Correct at 40 WPM, 17× wider than necessary at 7 WPM.

**What changed.** Window is now a quarter of a dot, clamped 10–40 ms, rebuilt on a 25 %
move. Live path only; the file path fits over the whole recording and reaches the same
place another way.

**Measured.** Live mean 32.7 → 32.6 — i.e. nothing, on the mean. The real shape: **nothing
at all above −3 dB**, up to 27 CER points at −6 dB, the best being 10 WPM at −6 dB going
38.4 % → 11.4 %. It does not make good conditions better; it moves the edge of the
envelope. Also added `StructureBench` and `prior-art.md`.

### 9 · `9b52819` — stop emitting from a clamped estimate (D-16)

**Problem.** Below the SNR floor the decoder invented text — 167 characters against 99
sent. Being wrong below 0 dB is allowed; being confidently verbose is not (P-4).

**What changed.** Output is suppressed while the unit is pinned at the physical clamp,
extending D-11's "a clamped estimate is a bound, not a measurement" from the readout to the
characters. Deliberately narrower than the planned "suppress outside 5–40 WPM", which
measurement showed is unimplementable: a synthetic 50 WPM signal decodes at 0 % CER while
RTTY at 52.9 WPM is garbage, so speed carries no information about correctness there.

**Measured.** Live mean **32.6 → 21.9**. Nothing changes at or above 0 dB. At −5 dB, 167
chars → 80 and CER 108 % → 38 %. `hand-13-rough` 308 % → 207 %. Closes `known-issues` D-7.

**Note.** On `hand-13-rough` this did not decode better, it emitted less. The remaining 194
characters are as wrong as the 274 were.

---

## What the measurements taught us

Five things that should shape every decision from here.

**1. Progress is real but concentrated.** One commit (D-13) accounts for two-thirds of the
total improvement. Three commits moved the mean by less than 0.2 points between them.

**2. Mean CER alone is not an acceptance signal.** `cd034af` made the mean worse and was
correct. A worst-case column and a non-Morse column are both required to see what a change
actually did.

**3. Better acquisition without a working unit estimate is actively harmful.** D-13 is the
proof: it improved every measure of lock quality and made the worst scenario 4× worse,
because everything a better lock buys is spent emitting more wrong characters.

**4. The file path has been essentially static** — it has changed once in the project's
history, D-13's band widening, which fixed `tone-1882`. All
engineering has gone into the live path, which is where the app runs, but it means
`hand-13-rough` (57 %) and `qsb-impulse` (151 %) have never been addressed on the path that
handles them best.

**5. Tuning on synthetic data alone inverts.** The gap-lattice statistic in `StructureBench`
lands on a threshold near 0.40 when fitted to the 16 synthetic fixtures — which *rejects*
genuine hand-sent Morse (0.45) and *accepts* RTTY (0.29), the exact opposite of what is
wanted. Real hand-sent code is further off the lattice than a teleprinter is. This is why
G-1 (no real corpus) is a High risk and not a nice-to-have.

---

## What is still broken

Open items, with the file that carries the evidence.

| ID | Severity | Summary |
|---|---|---|
| `known-issues` D-1 | High | A unit-scale error scores a perfect `fit 1.00`; no quality signal can see it — **and it now has no reproducer** |
| `known-issues` D-2 | High | Live path decodes real hand-sent audio far worse than file import — a bad bootstrap it never revisits |
| `known-issues` D-6 | High | A rough 13 WPM fist decodes at 65.9 % (live) / declines outright (file) |
| `known-issues` D-8 | High | Speech and RTTY decode as Morse; the fit score cannot separate them |
| `known-issues` D-9 | High | A tone change mid-session strands the lock permanently |
| `known-issues` D-10 | Medium | Tone readout stays ~76 Hz off after recovering from a wrong lock |
| `known-issues` D-3 | Low | Mains veto costs tone-readout accuracy (~20 Hz), not decoding |
| ~~**N-1**~~ | FIXED | Bootstrap seed had no outlier protection — closed by `decisions.md` D-17 |
| **N-2** (new) | High | The decode depends on the caller's audio buffer size — see below |
| `G-1` | High risk | No real recordings in the test corpus |
| `G-2` | High risk | Never run on a physical device |
| `G-3` | Low | Release builds are unsigned |

### N-1 — One glitch sets the whole timebase · FIXED by D-17

Kept for the record; the fix and its measurements are `decisions.md` D-17.

Measured 2026-08-03 by `BootstrapDiagnostic` (`DIAG=1 gradlew :core:test --tests
"*BootstrapDiagnostic*" -i`), which reconstructs the exact marks the seed sees and
attributes each against the generator's ground truth.

The live path fixes its dot length once, from the first 8 marks, by 2-means clustering:
short group = dots, long group = dashes, dot length = mean of the short group. On
`hand-13-rough`:

| # | measured | what it really was | coverage |
|---|---|---|---|
| 1 | **25.0 ms** | **fragment of a 129.8 ms dash** | **0.19** |
| 2 | 235.0 ms | dash | 0.99 |
| 3 | 85.0 ms | dot | 0.98 |
| 4–8 | … | 2 dots, 3 dashes | ≥ 0.98 |

The clustering put the lone 25 ms fragment in the "dots" group **by itself**, and all seven
genuine marks — dots and dashes together — in the "dashes" group. Dot length therefore
became 25 ms against a true 92.3 ms: **48 WPM believed, 13 WPM sent**. Every dash then
reads as a run of dots and characters shatter into `E`/`T`/`I`.

Everything else is exonerated by the same run. Segmentation is good (210 mark runs against
214 true marks, 0.98×; seven of the eight seed marks at ≥ 0.98 coverage). The algorithm is
good — fed the *true* durations of those same eight elements it returns 105.8 ms / 11.3 WPM
/ 2.08:1, near-correct. The controls are good: `clean-20` splits 3 dots / 5 dashes at
exactly 3.00:1, and `hand-18` survives a fragment of its own because at 97.5 ms it lands
inside the normal spread instead of below it.

Two guards were in range and both missed. The bootstrap floor rejects an implied dot under
20 ms — the fragment was 25 ms. The glitch filter deletes marks under 30 % of a dot
(27.7 ms here, which would have killed it) — but it needs the dot length the bootstrap is
computing, so pre-bootstrap it runs at a fixed 10 ms. Circular.

This is the mechanism behind `known-issues` D-6 and D-2, and the direct cause of the D-1
symptom. It answers the question D-6 left open — the seed is splitting on **neither** a
dot/dash boundary **nor** the interior of the dot cluster, but on a single outlier.

### N-2 — The decode depends on the caller's buffer size

Found while validating N-1. Same audio, same build, same config; only the length of the
slices handed to `feed()` changes:

| chunk | chars | CER | seeded unit | reported WPM |
|---|---|---|---|---|
| 512 | 33 | 70.9 % | 80.9 ms | 23.0 |
| 1024 | 36 | 70.9 % | 108.1 ms | 21.8 |
| 2048 | 76 | 117.7 % | 30.2 ms | 19.8 |
| **4096** | **87** | **138.0 %** | 23.9 ms | 53.2 |
| 8192 | 87 | 138.0 % | 23.9 ms | 53.2 |

`clean-20` moves too, 2.5 % → 6.3 % at 2048. Cause: while unlocked, `feed` resets the
analysis frame grid to the chunk boundary (`LiveDecoderEngine.kt:364`), and the sweep loop
is evaluated off `nowMs()` only once the whole chunk has landed — so both the framing phase
and the acquisition instant are functions of the caller's buffer size.

Two consequences. `DecoderBench` feeds 4096 and therefore reports this scenario's worst
column; at 512 the error is half. And `AudioRecord` chooses its buffer size from device
hardware — G-2 lists buffer sizing as unexercised, and this says it is not a performance
detail but something that changes the transcript.

---

## Plan

Ordered by leverage per unit of effort, with the reason for the position. Steps 1–3 are
cheap and unblock measurement; 4–6 are the substantial work.

### Step 1 — Reject a degenerate bootstrap seed · **DONE**, `decisions.md` D-17

An upper bound on the seed's dash:dot ratio (`bootstrapMaxRatio` = 4.5:1), rejecting into
the existing discard-and-keep-listening path. Cluster *membership* was considered and
rejected as the test: a singleton lower cluster is normal on a dash-heavy sample, so seven
dashes and one dot would have been thrown away with the glitch splits.

Measured with `HistoryBench`, one of 42 rows changed:

| | before | after |
|---|---|---|
| `hand-13-rough`, live | 194 chars, 207.1 % | 99 chars, **65.9 %** |
| Live mean | 21.9 % | **13.1 %** |
| Live worst case | 207.1 % | **65.9 %** |
| File mean | 15.6 % | 15.6 % |

Everything else byte-identical, non-Morse fixtures included. **The first change in the
project to improve the worst case rather than the mean**, and the scenario is finally below
its first-commit value of 69 %, so D-13's regression is recovered.

Two follow-ups it created, both listed below: the file path has the same unguarded 2-means
and did not benefit (step 1b), and D-1 lost its only reproducer (step 4a).

### Step 1b — Port the seed guard to the file path · small · `known-issues` D-6 (new)

D-17 is live-path only. `MorseDecoder.twoMeansSeed` has the same unguarded 2-means, and the
file path still emits **nothing at all** on `hand-13-rough` — its `fitQuality < 0.12` guard
declines the whole transmission. The file path has changed once in the project's history;
this is a cheap chance to change it a second time, and it is the path that handles rough
fists best everywhere else.

### Step 2 — Decouple the decode from the buffer size · small · fixes N-2

Frame the envelope on an absolute sample grid rather than resetting to the chunk boundary,
and drive the sweep clock off frame positions rather than post-chunk `nowMs()`. Then assert
it: decode one fixture at 512/1024/2048/4096/8192 and require the transcripts to match.

This is worth doing before any further tuning, because until it is done every bench number
carries an unknown component that is an artefact of the harness rather than the decoder.

### Step 3 — Re-bootstrap rather than commit once · medium · `known-issues` D-2

Steps 1 and 2 make a bad start less likely; they do not make it recoverable. A periodic
batch re-fit over a recent window, adopted when it disagrees materially with the tracked
unit, would recover from a bad start without abandoning the streaming architecture. This is
also `known-issues` D-1's third candidate direction — a large streaming-vs-batch
disagreement is itself the degraded-state signal the system currently lacks.

### Step 4a — Restore a reproducer for D-1 · small · **new, and it should come first**

D-17 removed the only input that produced a scale error, so `@KnownFailure("D-1")` started
passing — **not because anything detected the error, but because the error stopped
happening**. The annotation has been removed and the test now guards D-17. A probe over
dash-heavy and dot-heavy openings could not construct a replacement.

D-1 is a High defect that now has nothing failing to hold it. Restore one by injecting a
known-wrong unit directly, or from the real corpus (G-1) — the real recording still reads
30.6 WPM against a true 17.7. Until then the capability gap is real and unguarded, and
`fitQuality` still reports **1.00 on decodes measured at 13–23 % CER**.

### Step 4 — Make a scale error visible · medium · `known-issues` D-1

Today an octave-type unit error is invisible to every quality signal: `fitQuality` reads
1.00, and `outputSuppressed` was false in all 16 bench scenarios. Cheapest candidate is
scoring the decoded alphabet distribution — a transcript that is 90 % `E`/`T`/`I`/`M` is
prima facie a scale error, real text is not — which is independent of timing and cannot be
fooled by the clusters looking clean. Even if steps 1–3 fix the cause, the system should be
able to say when it has one.

### Step 5 — The tone-jump deadlock · small, but blocked · `known-issues` D-9

Fix is understood and contained: re-lock only if the current lock has produced nothing for
N seconds **and** a keyed peak exists elsewhere in the band. Safe for AC-9 because silence
offers no keyed peak. **Needs an owner decision** on N and on whether to accept the D-7
trade — extending recovery to proven locks means a lock could be taken away during a
legitimate pause if another station is active. Not started for that reason, not for
difficulty.

### Step 6 — Soft-decision front end · large · `prior-art.md` idea 1

Stop making hard mark/gap decisions early; carry likelihoods into the lattice.

**Its main argument has weakened and the step should be re-justified before it is
started.** That argument was the 4× unit error on `hand-13-rough`, which D-17 removed for a
few lines of guard. What is left on that scenario is 65.9 % CER with a sound seed and a
correct character count — ordinary inaccuracy under 25 % jitter, not a structural failure.
Measure where that error now accumulates (tracking, segmentation, or the lattice) before
committing to the largest item on this list.

### Step 7 — Real corpus · blocked on a decision · `G-1`

Every fixture is synthetic, and lesson 5 above shows synthetic-only tuning produces
confidently inverted thresholds. One real recording exists and is not committed; a ~20 s
16 kHz mono excerpt is about 640 kB. **Needs a decision** on committing binary audio, and a
ground-truth transcript that does not yet exist — only fragments are legible (`ALL BEST`, a
callsign, `DL4`). A weaker tone-lock-plus-character-count assertion is possible today and
would be better than nothing. This gates the D-8 structure gate, which is measured, works
on 6 of 7 non-Morse fixtures, and is deliberately unimplemented until its margins can be
established against more than one real transmission.

### Step 8 — Run it on a device · blocked on toolchain · `G-2`

No `cmdline-tools` and no AVD are configured, so no build of this app has ever executed.
Entirely unexercised: microphone capture and `AudioRecord` buffer sizing (see N-2), the
foreground service and wakelock, runtime permissions, SAF import through `MediaCodec`,
audio focus, Bluetooth routing, and every Compose layout at a real screen size.
`AudioFileImporter` assumes 16-bit PCM and would return silence, not an error, on a device
emitting float PCM.

### Step 9 — Signing config · small, owner's call · `G-3`

`assembleRelease` produces an unsigned APK that will not install. Needs a keystore and a
`signingConfigs` block reading from a gitignored properties file. Where the keystore and
its passwords live is the owner's decision.

---

## Decisions needed

These block work that is otherwise ready. Listed so they can be answered together.

1. **D-9 re-lock trade** (step 5) — value of N, and whether to accept that a proven lock can
   be taken away during a legitimate pause.
2. **Committing real audio** (step 7) — binary in the repo, and how to establish ground
   truth for a recording that is only partly legible.
3. **Toolchain for device runs** (step 8) — installing `cmdline-tools` and an AVD, or
   sideloading to a physical handset.
4. **Keystore location** (step 9).
5. **§8 of the requirements is still open** — export-with-audio and practice mode remain
   unspecified.
6. **`CLAUDE.md` still describes the repo as a specification handover with no code.** True
   at the first commit, false since. Rewriting it changes how agents work in this repo, so
   it is deliberately left to the owner.
