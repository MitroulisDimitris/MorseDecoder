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

---

## Where we are — 2026-08-07

Eighteen commits over ten days. Both decode paths exist and work, the app builds, and the
live path handles machine-sent code across the whole bench matrix at low single-digit
error. Four things are true at the same time:

- **Machine-sent code is solved.** 11 of 16 live scenarios sit at or below 4 % CER.
- **The file path is now as good as this architecture gets.** Step 6b (D-19) locked the
  decode unit and the worst file scenario `hand-13-rough` fell 64.6 % → **36.7 %**, with
  `qsb-impulse` recovering from a decline to 45.6 % as a second, unplanned win. The file mean
  is **6.6 %**. The floor 6a measured with every stage handed the truth is 32.9 %, so there
  is little left on this path without a better lattice.
- **A rough hand-sent fist is still not solved on the live path.** Steps 2 (D-20) and 3
  (D-21) took its `DecoderBench` figure 89.9 → 69.6 → **65.8 %** — chunk-invariance then a
  periodic re-bootstrap — and corrected its reported speed from 21.9 WPM to 15.1 (true 13),
  but it is still not a decode. The live path cannot reach the file's 36.7 % because the
  freeze that buys it (D-19) does not transfer here (N-5).
- **Nothing has ever run on a phone.** Every result is from unit tests.

| | first commit | now | source |
|---|---|---|---|
| Live path, mean CER | 89.7 % | **12.3 %** | `DecoderBench`, D-21 |
| Live path, `hand-13-rough` | 227 % | **65.8 %** | `DecoderBench`, D-21 |
| File path, mean CER | 21.8 % | **6.6 %** | `DecoderBench`, D-19 |
| File path, `hand-13-rough` | — | **36.7 %** | `DecoderBench`, D-19 |
| Non-Morse fixtures emitting nothing | 1 of 5 | 3 of 5 | `version-history.md` |

**Quote the instrument with the number, always.** The live rows above are `DecoderBench`,
re-measured after step 2 made the path chunk-invariant. The `HistoryBench` figures this file
used to headline (live mean 13.1 %, worst 65.9 %) **predate step 2 and would move if
re-run** — `HistoryBench` is not in the repo, so they cannot be, which is itself *Decisions
needed* 6. `DecoderBench` and `HistoryBench` also use different text lengths and disagree on
`hand-13-rough` (D-18 found this; D-19 saw it again on `hand-18`). See lesson 6 and the
caveat in `version-history.md`.

The live mean improved 7×. The file mean is down to a third of the first commit. The recent
worst-case wins came from **removing machinery — or artefacts — that were doing harm**: D-17
discarded an outlier seed, D-18 retired an inverted veto, D-19 stopped pass 2 re-opening the
unit pass 1 had locked, and D-20 stopped the transcript depending on the caller's buffer
size. None added a model; each deleted an assumption.

---

## The commits

Eighteen commits in all. Two (`4fd2154`, `dddb819`) are README and UI copy only and are
omitted; the rest are below, including the four that changed no decoder behaviour.

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
| 11 | `953baf5` | 08-03 | CLAUDE.md rewrite | — (docs only) |
| 12 | `d08fadb` | 08-04 | D-18 file gate | 13.1 % (file path only) |
| 13 | `5956a7e` | 08-05 | 6a residual | — (diagnostic only) |
| 14 | `5212505` | 08-05 | 6b unblocked | — (docs only) |
| 15 | `d0d2082` | 08-06 | D-19 6b decode lock | 13.1 % (file path only) |
| 16 | `efdfb02` | 08-06 | N-2 sample-grid (step 2) | **12.7 %** (`DecoderBench`) |
| 17 | `7ec06c3` | 08-07 | D-21 re-bootstrap (step 3) | **12.3 %** (`DecoderBench`) |
| 18 | *(this commit)* | 08-07 | 4a D-1 reproducer | — (tests only) |

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

### 10 · `7d1a2b5` — discard a bootstrap seed claiming an impossible ratio (D-17)

**Problem.** The live path fixed its timebase from the first eight marks. On
`hand-13-rough`, one of those was a 25 ms fragment of a 130 ms dash, and 2-means put it in
the lower cluster *by itself* — seeding 48 WPM against a true 13.

**What changed.** An upper bound on the seed's dash:dot ratio (4.5:1), rejecting into the
existing discard-and-keep-listening path. Membership was rejected as the test: a singleton
lower cluster is normal on a dash-heavy sample.

**Measured.** One of 42 `HistoryBench` rows changed. `hand-13-rough` live 207.1 % → **65.9 %**
at the right character count; live mean 21.9 % → **13.1 %**. The first change in the project
to improve the worst case rather than the mean. Full record: `decisions.md` D-17, step 1.

### 11 · `953baf5` — rewrite CLAUDE.md for a repo that has code in it

**Problem.** `CLAUDE.md` still described the repo as a specification handover with no code,
true at the first commit and false for eleven after it.

**What changed.** Docs only. Resolves what was *Decisions needed* 6.

### 12 · `d08fadb` — retire the file path's fit veto (D-18)

**Problem.** The plan said the file path declined `hand-13-rough` because
`MorseDecoder.twoMeansSeed` had the same unguarded 2-means D-17 fixed. It does not — it
clusters all 214 marks rather than the first eight, and fits `u0` to within 1.04× of truth.

**What changed.** One line. The `fitQuality < 0.12` veto became `<= 0.0`, because that score
ranks a genuine rough fist (0.0987) *below* speech (0.4741), knocking (0.4033) and clipping
(0.7386), all of which the file path decoded. Also added `FileGateDiagnostic`.

**Measured.** `DecoderBench`: file `hand-13-rough` 100 % → **64.6 %**, file mean 13.8 % →
**11.6 %**, one row changed in all four variants, live path byte-identical. `HistoryBench`:
**all 42 rows unchanged** — see N-4. Full record: `decisions.md` D-18, step 1b.

**Note.** Two of the last three changes were *deletions of a wrong assumption*, not new
machinery, and both were found by building a diagnostic before writing a fix.

### 13 · `5956a7e` — attribute the residual error (step 6a)

**Problem.** After D-17 and D-18 both paths reached `hand-13-rough` with a sound unit
estimate and both still got two thirds of it wrong. The plan's largest item (step 6, the
soft-decision front end) was aimed at that residual without anyone knowing where it lived.

**What changed.** No behaviour. `ResidualDiagnostic` runs a counterfactual ladder on the
file path, handing each stage the generator's ground truth in turn, plus a whole-recording
segmentation attribution.

**Measured.** Tracking is worth **27.9 points** (64.6 % → 36.7 % with the Kalman frozen);
segmentation is worth **1.3** (64.6 % → 63.3 % with a perfect element timeline), because it
is already exact at 214/214 with zero defects. Freezing costs **0.0** on `drift-ramp` and
`wobble`.

**Consequences.** Step 6 is closed as unjustified on current evidence and `prior-art.md`
idea 1 is annotated with its measured 1.3-point ceiling. N-5 and step 6b are opened in their
place: the largest measured win on the board.

### 14 · *(this commit)* — unblock 6b; AC-6 answers what FM-2's track is for

**Problem.** N-5 was filed asking the owner whether FM-2's speed track is a reporting
feature or something the decode is entitled to consume. The repo already answered.

**What changed.** Docs only. **AC-6** decodes a 15 → 25 WPM ramp and asserts every
`wpmTrack` point after the convergence window is within **10 %** of the generator's own
track, over at least 20 points. A frozen tracker reports one speed for the whole ramp and
cannot satisfy that — so "freeze the Kalman" was never a candidate fix, only the measurement
device row B of the ladder always was. FM-2's wording is "**Report** a global WPM plus a
per-segment WPM track", and AC-6 requires the report to be accurate.

That fixes 6b's shape without an owner decision: **separate the two jobs.** Decode against
the locked pass-1 unit — which is what FM-1 already specifies and pass 2 re-opens — and
derive the reported track alongside it without feeding it back. `DecodedChar.unitMsLocal`,
the per-character shrunk least-squares fit, is the natural source and already exists.

**Consequence.** 6b moves from blocked to ready and is the next step. The decisions list
loses its newest entry, which is the right outcome for a question the specification had
already settled.

### 15 · *(this commit)* — D-19: pass 2 decodes with the unit locked (step 6b)

**Problem.** N-5: on the file path pass 2 re-opened the unit character by character and
followed `hand-13-rough`'s 12 % per-character bias, costing 27.9 CER points for nothing on
the drift scenarios. FM-1 says file mode decodes with parameters already locked.

**What changed.** `RobustTimingDecoder.freezeDecodeUnit` locks the decode unit at pass 1's
`u0` while the Kalman tracks on **only to report** FM-2's speed track. Two measurements shaped
it, both refuting the plan's stated approach:

- Reporting the track from `unitMsLocal` (N-5's proposal) **fails AC-6 at 42 %**, and so does
  every other internal speed signal — with the decode frame frozen the online model absorbs
  the sender's drift into `means[0]`, poisoning anything measured against it. The report is
  therefore measured against the **frozen pass-1 mark centres** (`refMean0/refMean1`), which
  restores the tracked `unitMsGlobal` — what `wpmTrack` already reads — to 8.0 % on the ramp.
- The freeze is **raw mode only** (`prior == null`). It improves the raw decode, but on
  `hand-18` at the AC-4 text length it strands errors the lexicon cannot flip (assisted 5.6 %
  → 7.9 %, which fails AC-4). With the prior on, pass 2 tracks. `decisions.md` D-19.

**Measured.** `DecoderBench` file path: `hand-13-rough` **64.6 → 36.7 %**; `qsb-impulse`
**declines → 45.6 %** at a sane 19.2 WPM (the tracker no longer runs away to the ceiling on
the static crashes — an unplanned second win, and the D-18 `atSpeedCeiling` note is
superseded); `hand-18` **8.9 → 11.4 %** (the cost). File mean **11.6 → 6.6 %**. Live path
**byte-identical**, AC-3/AC-4/AC-6 green, same nine known failures. `known-issues` D-6 stays
open — 36.7 % is a third right, and the floor for this fixture is 32.9 %.

### 16 · *(this commit)* — D-20: the transcript stops depending on the buffer size (step 2)

**Problem.** N-2: `feed()` reset the frame grid to the chunk boundary and evaluated the sweep
loop off post-chunk `nowMs()`, so acquisition timing, the framing phase, and thus the whole
transcript were functions of the caller's buffer size.

**What changed.** `feed()` now processes frames and sweeps as events at absolute sample
positions, each reading the window that ends at its own position, driven by a `curSample`
processing cursor rather than the write head. The decode is now a pure function of the sample
stream — asserted by a test that decodes two fixtures at 512…8192 and requires identical
transcripts.

**Measured.** Not the neutral cleanup the plan predicted. `DecoderBench` fed 4096, often the
*worst* chunk, so the honest deterministic numbers are mostly better: live mean **14.9 →
12.7 %**, `hand-13-rough` **89.9 → 69.6 %** (helps D-6's live figure), `noise-20@-5` **43.0 →
1.3 %**. `hesitation` went **13.9 → 43.0 %** — but it was already 41.8 % at 2048, so 43 % is
the truth the 4096 fluke hid, not a regression. Two `SessionSequenceTest` noise-recovery
tests were recalibrated off the artefact; one surfaced that D-10's tone offset is common
across seeds, not rare, so its known-failure was re-pointed off a seed that now coincidentally
re-centres. `decisions.md` D-20.

---

## What the measurements taught us

Six things that should shape every decision from here.

**1. Progress is real but concentrated.** One commit (D-13) accounts for two-thirds of the
total improvement. Three commits moved the mean by less than 0.2 points between them.

**2. Mean CER alone is not an acceptance signal.** `cd034af` made the mean worse and was
correct. A worst-case column and a non-Morse column are both required to see what a change
actually did.

**3. Better acquisition without a working unit estimate is actively harmful.** D-13 is the
proof: it improved every measure of lock quality and made the worst scenario 4× worse,
because everything a better lock buys is spent emitting more wrong characters.

**4. The file path has been essentially static** — until D-18 it had changed once in the
project's history, D-13's band widening, which fixed `tone-1882`. All engineering has gone
into the live path, which is where the app runs. D-18 is the second change and it was a
*removal*: the path was declining a transmission it could decode. `qsb-impulse` (151 %)
still has not been addressed, and the diagnostic shows its veto — `atSpeedCeiling` — is the
correct one, since ungated it emits 121 characters against 62 sent.

**5. Tuning on synthetic data alone inverts.** The gap-lattice statistic in `StructureBench`
lands on a threshold near 0.40 when fitted to the 16 synthetic fixtures — which *rejects*
genuine hand-sent Morse (0.45) and *accepts* RTTY (0.29), the exact opposite of what is
wanted. Real hand-sent code is further off the lattice than a teleprinter is. This is why
G-1 (no real corpus) is a High risk and not a nice-to-have.

D-18 found this had already happened *in shipped code*: the file path's `fitQuality < 0.12`
veto ranked a genuine fist below speech, knocking and clipping, and had been declining the
one real transmission in the corpus since the first commit. The lesson is not only "do not
tune on synthetics" but "**go and check the thresholds already in the tree**".

**6. Two benches can disagree at one moment, and nothing was checking.** `DecoderBench` and
`HistoryBench` reported 100 % and 57.1 % for `hand-13-rough` on the file path — the same
generator config over different text lengths, straddling that threshold. `version-history.md`
exists to stop numbers drifting across *time*; nothing was stopping them drifting across
*instruments*. When two benches disagree on a scenario name, they are measuring different
audio.

---

## What is still broken

Open items, with the file that carries the evidence.

The last column points at the plan step that addresses each, where the expected improvement
is stated and labelled with what it rests on.

| ID | Severity | Summary | Addressed by |
|---|---|---|---|
| `known-issues` D-1 | High | A unit-scale error scores a perfect `fit 1.00`; no quality signal can see it — **reproducer restored by 4a** (a pinned 2× unit: 297.5 % CER at `fit 1.00`), so it is held by a failing test again | 4 |
| `known-issues` D-2 | High | Live path decodes real hand-sent audio worse than file import — **narrowed** by D-21's re-bootstrap (speed corrected, CER 69.6 → 65.8 %), not closed: the gap needs the D-19 freeze, which cannot transfer to live | 3 (done) |
| `known-issues` D-6 | High | A rough 13 WPM fist decodes at 65.9 % (live) / **36.7 % (file, since D-19)** | live path — 6b took the file path as far as it goes |
| ~~**N-5**~~ | FIXED | On the file path the pass-2 tracker cost 27.9 points and bought nothing — closed by `decisions.md` D-19 | 6b |
| `known-issues` D-8 | High | Speech and RTTY decode as Morse; the fit score cannot separate them | 7 (blocked) |
| `known-issues` D-9 | High | A tone change mid-session strands the lock permanently | 5 (blocked) |
| `known-issues` D-10 | Medium | Tone readout stays ~76 Hz off after recovering from a wrong lock | — none planned |
| `known-issues` D-3 | Low | Mains veto costs tone-readout accuracy (~20 Hz), not decoding | — accepted |
| ~~**N-1**~~ | FIXED | Bootstrap seed had no outlier protection — closed by `decisions.md` D-17 | 1 |
| ~~**N-2**~~ | FIXED | The decode depended on the caller's audio buffer size — closed by `decisions.md` D-20 (step 2) | 2 |
| ~~**N-3**~~ | FIXED | File path declined a fit it could decode, on an inverted score — closed by `decisions.md` D-18 | 1b |
| **N-4** (new) | Medium | `DecoderBench` and `HistoryBench` disagreed about `hand-13-rough`; nothing compared them — see lesson 6 | — undecided, see *Decisions needed* 6 |
| `G-1` | High risk | No real recordings in the test corpus | 7 (blocked) |
| `G-2` | High risk | Never run on a physical device | 8 (blocked) |
| `G-3` | Low | Release builds are unsigned | 9 |

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

### N-2 — The decode depends on the caller's buffer size · FIXED by step 2

**Closed by the sample-position rewrite (`decisions.md` D-20).** `feed()` now processes
frames and sweeps as events at absolute sample positions, each reading the window that ends
at its own position, so the transcript is a pure function of the sample stream. The
invariance is asserted: `LiveEngineTest."decode is invariant to feed chunk size"` decodes two
fixtures at 512/1024/2048/4096/8192 and requires byte-identical transcripts. The evidence
below is kept for the record.

The fix was **not** the neutral cleanup this plan predicted. `DecoderBench` had been feeding
4096 — often, but not always, the *worst* chunk — so removing the dependence moved the live
numbers, and mostly for the better: live mean **14.9 → 12.7 %**, `hand-13-rough` **89.9 →
69.6 %** (the honest fine-grained value, which also improves `known-issues` D-6's live
figure), `noise-20@-5` **43.0 → 1.3 %**. Two scenarios went the other way and were exposed
rather than caused: `hesitation` **13.9 → 43.0 %** was already 41.8 % at 2048 — the 13.9 % at
4096 was the fluke — and two `SessionSequenceTest` noise-recovery cases had their bars
recalibrated off the artefact (one of them surfaced that D-10's tone offset is seed-common,
not seed-rare). Full account in `decisions.md` D-20.

Found while validating N-1. Same audio, same build, same config; only the length of the
slices handed to `feed()` changes. On `hand-13-rough`, re-measured 2026-08-04 with
`BootstrapDiagnostic`:

| chunk | chars | CER | seeded unit | reported WPM |
|---|---|---|---|---|
| 512 | 33 | 70.9 % | 80.9 ms | 23.0 |
| 1024 | 36 | 70.9 % | 108.1 ms | 21.8 |
| 2048 | 48 | 74.7 % | 64.4 ms | 19.1 |
| **4096** | **64** | **89.9 %** | 64.4 ms | 21.5 |
| 8192 | 61 | 86.1 % | 64.4 ms | 23.5 |

**These numbers are a third of what this entry originally recorded, and the difference is
D-17.** As first measured — before the seed guard landed — the same sweep read 117.7 % at
2048 and **138.0 % at 4096 and 8192**, a 67-point spread, with the seeded unit collapsing to
23.9 ms (53.2 WPM believed, 13 WPM sent) on the three largest chunks. Those were degenerate
bootstraps, and D-17 now rejects them: the seed reads a consistent 64.4 ms from 2048 up, and
the spread is **19 points rather than 67**.

So the entry was overstating the defect by ~3.5× for two commits, in the same way step 1b's
premise was wrong for two commits — **a defect written up once and not re-measured after the
code beneath it changed.** Both were caught by re-running the instrument rather than
re-reading the file.

**The mechanism is untouched, and that is the point.** The seeded unit still varies with
chunk size on every fixture — `clean-20` reads 53.3 / 67.6 / 57.5 / 60.0 / 60.0 ms across
the sweep and still moves 2.5 % ↔ 6.3 % at 2048; `hand-18` spans 74.3–91.6 ms and 8.9–13.9 %.
While unlocked, `feed` resets the analysis frame grid to the chunk boundary
(`LiveDecoderEngine.kt:364`), and the sweep loop is evaluated off `nowMs()` only once the
whole chunk has landed — so both the framing phase and the acquisition instant remain
functions of the caller's buffer size. D-17 removed the worst *consequence*, not the cause.

Two consequences still stand. `DecoderBench` feeds 4096 and therefore reports this
scenario's worst column; at 512 the error is a third lower. And `AudioRecord` chooses its
buffer size from device hardware — G-2 lists buffer sizing as unexercised, and this says it
is not a performance detail but something that changes the transcript.

### N-5 — On the file path, the pass-2 tracker is pure loss · FIXED by D-19

**Closed by step 6b (`decisions.md` D-19).** Pass 2 now decodes with the unit locked and the
Kalman tracks only to report FM-2's speed track. The evidence below stands; the fix and the
two measurements that shaped it (the `unitMsLocal` track failing AC-6 at 42 %, the raw-mode
gating) are in D-19. Kept for the record.

Found by step 6a. FM-1 says file mode fits its parameters over the whole recording and then
decodes with them **locked**; the code then runs a Kalman in pass 2 anyway, with the comment
"Kalman still tracks drift (FM-2)". Measured with `ResidualDiagnostic`, freezing that
tracker at the pass-1 unit:

| scenario | shipped | frozen | change |
|---|---|---|---|
| **`hand-13-rough`** (25 % jitter, 12 % per-char bias) | **64.6 %** | **36.7 %** | **−27.9** |
| `hand-18` (20 % jitter) | 8.9 % | 8.9 % | 0.0 |
| `clean-20` | 0.0 % | 0.0 % | 0.0 |
| `drift-ramp` (15 → 25 WPM over 60 s) | 0.0 % | 0.0 % | **0.0** |
| `wobble` (±15 % at 0.05 Hz) | 0.0 % | 0.0 % | **0.0** |

The last two rows are the ones that make this a defect rather than a trade-off. They exist
precisely to exercise drift tracking, and the tracker contributes **nothing** on either —
pass 1's global fit plus the lattice's per-character shrinkage (FR-5.6) already absorb the
drift. So on the file path the tracker is not buying drift tolerance at the cost of jitter
tolerance. It is buying nothing and costing 27.9 points on the worst scenario.

The mechanism is straightforward once stated: `hand-13-rough` has 12 % *per-character* bias,
so each character's local unit estimate is genuinely biased, and a tracker whose job is to
follow the local unit follows the bias. Pass 1 already knew the right answer.

**Not simply "freeze it", and the constraint is sharper than it first looked.** Three things
depend on that Kalman beyond the decode:

- `wpmTrack` — FM-2's per-segment speed track, built from `unitMsGlobal` per character
- `unitMs` / `wpm` on `FileResult`, which the UI displays (`MainActivity.kt:185`)
- `atSpeedCeiling`, which is D-16's honest-failure guard on this path

**`wpmTrack` is guarded by an acceptance criterion.** AC-6 decodes a 15 → 25 WPM ramp and
asserts every track point after the convergence window is within **10 %** of the generator's
own `wpmTrack`, over at least 20 points. A frozen tracker reports one constant speed for
that whole ramp and cannot satisfy it. So "freeze the Kalman" is not a candidate fix — it is
only a *measurement device*, which is all row B of the ladder ever claimed to be.

That settles the question this entry originally posed. FM-2 says **"Report** a global WPM
plus a per-segment WPM track", and AC-6 requires that report to be accurate — so the track
is a reporting obligation that must stay correct, not something the decode is entitled to
consume. The fix has to **separate the two jobs**: decode against the locked pass-1 unit
(FM-1's "parameters already locked", which pass 2 currently re-opens), and derive the
reported track alongside it without feeding it back.

The material for that already exists — `DecodedChar.unitMsLocal` is the per-character
shrunk least-squares fit, which is what a speed track should be built from anyway. Whether a
track derived that way holds AC-6 within 10 % is the one thing 6b must measure before
anything else.

**This does not apply to the live path**, where the Kalman is the only unit estimate that
exists after bootstrap.

### N-3 — The file path declined a fit it could decode · FIXED by D-18

Kept for the record; the diagnosis and its measurements are `decisions.md` D-18.

`decodeFile` vetoed any transmission scoring `fitQuality < 0.12`. Ranked by that score, a
genuine rough fist (0.0987) sat *below* speech (0.4741), knocking (0.4033) and clipping
(0.7386), all of which it decoded — so the guard rejected the one real transmission in the
corpus and passed four impostors. It had been doing that since the first commit.

Reproduce with `DIAG=1 gradlew :core:test --tests "*FileGateDiagnostic*" -i`.

### N-4 — Two benches disagreed, and nothing compared them

`DecoderBench` reported `hand-13-rough` on the file path at 100 % (0 characters);
`version-history.md`, built from `HistoryBench`, reported 57.1 % (120 characters). Same
generator config, same seed, same impairments — different **text length**, 79 characters
against 126, straddling the threshold N-3 describes.

Both were correct about their own fixture, and nothing compared them. The disagreement was
possible from `65c1f56`, when `DecoderBench` arrived, and unmissable from `7d1a2b5`, when
`HistoryBench` published a 57.1 % for a scenario `DecoderBench` was reporting at 100 % —
and the two numbers still went into the same document without anyone noticing.
`version-history.md` exists to stop numbers drifting as the bench changes over *time*;
there was no equivalent protection against two benches disagreeing at one moment.

Not obviously a defect to fix so much as a practice to adopt: **quote the instrument with
the number**. Whether to go further and commit `HistoryBench.kt` so the two can be run
together is *Decisions needed* 6.

---

## Plan

Ordered by leverage per unit of effort, with the reason for the position. Steps 1–3 are
cheap and unblock measurement; 4–6 are the substantial work.

### Summary — and what each step is expected to buy

**Read the last column as a claim to be tested, not a forecast to be trusted.** Step 1b
carried a confident expectation in this file for two commits ("the file path has the same
unguarded 2-means") and it was wrong on every count — the mechanism, the fix, and the
implied margin. Each row below is therefore labelled with what its expectation actually
rests on:

- **measured** — the number has been produced by an instrument on this codebase
- **derived** — follows from a measurement, but the fix itself has not been run
- **guess** — plausible, untested; treat a surprise here as normal

**Status** is what has actually happened, not what is planned:

- **done** — landed and measured, with the commit named
- **ready** — nothing in the way; someone can start it today
- **blocked** — and by what: an owner decision, a toolchain gap, or another step

**Seven of thirteen are done, one is closed unstarted.** Of the five still open, **one is
ready** to start today (4) and **four are blocked** — three on an owner decision rather than
on effort (5, 7, 9) and one on the toolchain (8). The decisions are collected at the foot of
this file.

**Step 4a is done** and D-1 is held by a failing test again. The reproducer had to be
*injected* — no synthetic fixture bootstraps a scale error any more — so it pins the unit an
octave fast (FR-9.5) on a clean 20 WPM signal and measures what notices: nothing does.
202 characters for 62 sent, **297.5 % CER**, 98.5 % of it `E`/`T`/`I`/`M`, `fit 1.00` over a
full window, `outputSuppressed` false, lock LOCKED. **Step 4 is next** — make that state
visible — and it now has a fixture to be judged against.

| Step | What it is | Status | Effort | Expected change / improvement | Basis |
|---|---|---|---|---|---|
| 1 | Reject a degenerate bootstrap seed | **done** · `7d1a2b5` | — | Live worst case 207.1 % → 65.9 %, live mean 21.9 % → 13.1 % | measured |
| 1b | Port the seed guard to the file path | **done** · this commit | — | **Premise refuted.** The port itself: *no change on any fixture*. The real fix (D-18) gave file `hand-13-rough` 100 % → 64.6 %, file mean 13.8 % → 11.6 % | measured |
| 2 | Decouple the decode from buffer size | **done** · this commit (D-20) | small | **Not neutral after all.** Absolute-sample-grid processing; transcripts now byte-identical across 512…8192. `DecoderBench` fed the worst chunk, so the honest numbers improved: live mean **14.9 → 12.7 %**, `hand-13-rough` **89.9 → 69.6 %**, `noise-20@-5` **43.0 → 1.3 %**. Exposed `hesitation`'s true 43 % (was a 4096 fluke at 13.9 %) and re-pointed a D-10 known-failure | measured (D-20) |
| 4a | Restore a reproducer for D-1 | **done** · this commit | small | **Restored by injection, not by waiting.** A unit pinned an octave fast on a clean 20 WPM signal: 202 chars for 62 sent, CER **297.5 %**, 98.5 % of it `E`/`T`/`I`/`M`, at `fit 1.00` with nothing suppressed and the lock LOCKED. No behaviour change; known failures 9 → 10 across 5 defects | measured |
| 3 | Re-bootstrap rather than commit once | **done** · this commit (D-21) | medium | **Modest, not the 36.7 % the ceiling implied.** Batch re-fit every 16 marks, adopted in place on a material disagreement. Corrects reported speed (`hand-13-rough` 21.9 → 15.1 WPM) and CER 69.6 → **65.8 %**, `hesitation` 43.0 → 39.2 %, live mean 12.7 → **12.3 %**; fires on nothing healthy. Capped short of the file's 36.7 % because the D-19 freeze cannot transfer to live (N-5) — the tracker resumes chasing bias | measured (D-21) |
| 4 | Make a scale error visible | ready | medium | No CER improvement. Turns a silent wrong answer into a flagged one; today `fitQuality` reads 1.00 on decodes measured at 13–23 % CER and `outputSuppressed` was false in all 16 scenarios | measured |
| 6a | Locate the residual error | **done** · `5956a7e` | — | **Answered: tracking, 27.9 points.** Segmentation is exact (214/214, zero defects) and worth 1.3. Produced N-5 and step 6b, and closed step 6 | measured |
| 6b | Stop the pass-2 tracker re-opening what pass 1 locked | **done** · this commit (D-19) | small–medium | **Landed: `hand-13-rough` file 64.6 % → 36.7 %; `qsb-impulse` declines → 45.6 %; file mean 11.6 % → 6.6 %.** Live path byte-identical, AC-6 held (8.0 %). Cost 2.5 pts on `hand-18` (short text), so gated to raw mode to keep AC-4. The `unitMsLocal` track the plan named fails AC-6 at 42 % — the report is measured against the frozen pass-1 centres instead | measured (D-19) |
| 5 | The tone-jump deadlock | **blocked** · owner decision on N and the D-7 trade | small | Recovers a session that is currently dead until restarted. One `SessionSequenceTest` known failure (D-9) should start passing. Risk: AC-9 must stay green | derived |
| 6 | Soft-decision front end | **closed** · not justified on current evidence | large | **Ceiling measured at 1.3 points.** The front end produces 214 marks against 214 true with zero defects; perfect runs move CER 64.6 % → 63.3 %. Reopen only if G-1 shows a real recording where the front end is at fault | measured |
| 7 | Real corpus | **blocked** · owner decision on committing binary audio | medium | No immediate CER change; the numbers may get *worse* and more honest. Unblocks the D-8 structure gate, which is measured, works on 6 of 7 fixtures, and cannot be trusted on synthetics alone | measured (lesson 5) |
| 8 | Run it on a device | **blocked** · no `cmdline-tools`, no AVD | large | Unknown, and the largest unknown in the project. Buffer sizing alone changes the transcript (N-2); `AudioFileImporter` returns silence rather than an error on float PCM | derived |
| 9 | Signing config | **blocked** · owner decision on keystore location | small | No decode change. `assembleRelease` currently produces an APK that will not install | measured |

**No step here closes `known-issues` D-6.** 6b took the file path to 36.7 %, which is a
large improvement and still not "decoded", and left the live path untouched at 89.9 %. 6a
measured the floor this architecture reaches on that fixture with every stage handed the
truth: **32.9 %**. Below that needs a better lattice or better mark/gap models under jitter,
and nobody has proposed either.

And **no step improves the mean much**, because the mean is already carried by eleven
scenarios at or below 4 %. Everything left is worst-case and honesty work.

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

Three follow-ups, all listed below. D-1 lost its only reproducer (step 4a). The file path
did not benefit, which was written up at the time as "it has the same unguarded 2-means" —
**that turned out to be wrong**, and step 1b records what was actually happening. And it
quietly shrank N-2 from a 67-point spread to 19 by removing the degenerate seeds that
produced the worst chunk sizes, which was not noticed until N-2 was re-run on 08-04.

### Step 1b — Port the seed guard to the file path · **DONE, and the premise was wrong** · `decisions.md` D-18

The step was written on the assumption that `MorseDecoder.twoMeansSeed` fails the same way
D-17's did. It does not, and the new `FileGateDiagnostic` measures why: `decodeFile`
clusters **every mark in the recording** rather than the first eight, so one glitch cannot
form a cluster by itself. The split lands at 2.19:1 with `u0` = 89.2 ms against a true 92.3
— an error of 1.04×. Applying `bootstrapMaxRatio` = 4.5 to the file seed changes nothing on
any fixture, Morse or non-Morse. The port was measured, not argued away, and the table stays
in the diagnostic so it is not proposed again.

What was actually declining the transmission is the `fitQuality < 0.12` veto, and that score
is **inverted** on this corpus — a genuine rough fist (0.0987) ranks below speech (0.4741),
knocking (0.4033) and clipping (0.7386), all of which the file path decoded. The same fist
at three lengths scores 0.3258 / 0.0987 / 0.3312 while `u0` holds within 4 % of truth, so
the veto was a coin flip on identical signal quality. The gate now declines only a
structurally impossible fit; see `decisions.md` D-18 for the full argument.

Measured with `DecoderBench`, one scenario changed in all four file variants:

| | before | after |
|---|---|---|
| `hand-13-rough`, file | 0 chars, **100 %** | 79 chars, **64.6 %** at 17.9 WPM |
| File mean, 16 scenarios | 13.8 % | **11.6 %** |
| Live path, every column | — | byte-identical |

`HistoryBench` is unchanged on all 42 rows, because its `hand-13-rough` uses a longer text
and was already on the decoding side of the threshold. **That disagreement is the finding
worth carrying forward** — two benches reported different verdicts on the same scenario name
across two benches without being noticed. See the caveat added to `version-history.md`.

Three things this did not do: the live path is untouched, `known-issues` D-6 stays open at
64.6 % file / 89.9 % live, and D-8 is no closer — the impostors above were never caught by
this gate and still are not.

### Step 2 — Decouple the decode from the buffer size · **DONE** · `decisions.md` D-20

`feed()` processes frames and sweeps as events at absolute sample positions, each reading the
window ending at its own position, driven by a `curSample` cursor. The transcript is now a
pure function of the sample stream — `LiveEngineTest."decode is invariant to feed chunk size"`
decodes two fixtures at 512…8192 and requires them byte-identical. The measured consequence
(the live path shifted off the 4096 artefact, mostly better) and the two recalibrated
recovery tests are in D-20. Done before step 3 precisely so step 3's before/after means
something.

### Step 3 — Re-bootstrap rather than commit once · **DONE** · `decisions.md` D-21

A periodic batch re-fit (every 16 marks, 2-means over the last 48), adopted **in place** on a
material disagreement with the tracked unit. The clustering is segmentation-free, so it sees a
correction the streaming tracker — mis-segmenting under a wrong unit — cannot.

The ceiling the plan quoted (the file's 36.7 %) turned out unreachable, and the reason is
instructive: the file path only reaches 36.7 % by *freezing* the unit (D-19), and N-5 already
established the freeze cannot transfer to the live path. So the re-bootstrap corrects the unit,
the tracker resumes chasing the fist's per-character bias, and the decode re-degrades. Measured
result: reported speed 21.9 → 15.1 WPM, CER 69.6 → **65.8 %**, `hesitation` 43.0 → 39.2 %, live
mean 12.7 → **12.3 %**, with **zero** firings on any healthy scenario (`clean-20`, `hand-18`,
`drift-ramp`, `wobble`, the tone cases). A real but modest win, and the mechanism's real test —
a recording worse than any synthetic — is G-1, still blocked. Full record in D-21.

Also `known-issues` D-1's third candidate direction: a large streaming-vs-batch disagreement is
itself the degraded-state signal the system lacked, and the re-fit now computes it every 16
marks.

### Step 4a — Restore a reproducer for D-1 · **DONE** · this commit

D-17 removed the only input that produced a scale error, so `@KnownFailure("D-1")` started
passing — **not because anything detected the error, but because the error stopped
happening**. A probe over dash-heavy and dot-heavy openings could not construct a
replacement, so the reproducer is now *injected* rather than waited for, through FR-9.5's
WPM pin: a clean 20 WPM signal decoded against a unit pinned at 40.

| | pinned 40 WPM | unpinned |
|---|---|---|
| characters emitted (62 sent) | **202** | 61 |
| CER | **297.5 %** | 2.5 % |
| transcript is `E`/`T`/`I`/`M` | **98.5 %** | 21 % |
| `fitQuality` | **1.00** (64 samples, p10 = p50 = 1.00) | 1.00 |
| `outputSuppressed` / `lockState` | **false** / LOCKED | false / LOCKED |

No behaviour changed; the known-failure count goes 9 → **10 across 5 defects**. Two tests,
separated on purpose: one asserts the **premise** (CER > 0.5, so it survives any legitimate
fix and fails loudly if the scale error stops happening — which is exactly how the last
reproducer evaporated unnoticed), the other is the `@KnownFailure("D-1")` asserting the
**defect** (the gate fires, or `fitQuality` falls to at least its own 0.35 suppression
threshold).

Three things the measurement added beyond restoring the guard:

- **The pin is a vehicle, not the defect.** `fitQuality` is scale-invariant *by
  construction* — `ObservationFit` clusters normalised durations `ln(duration / unit)`, and
  separation × ratio × tightness are all unchanged by a uniform `ln 2` shift. Where the
  wrong unit came from cannot matter.
- **The error is asymmetric.** Pinned slow the lattice's shrinkage absorbs it (10 WPM
  against a true 20 gives 15.2 % CER; 13.3 WPM gives 2.5 %, identical to unpinned); pinned
  fast it shatters (30 → 38.0 %, 40 → 297.5 %, 53 → 310.1 %). Only 6.7 WPM trips the gate,
  and on a shape error rather than a scale one.
- **A pin disables D-21's re-fit** (FR-9.5 owns the unit while pinned), so the streaming-vs-batch
  disagreement — D-1's third candidate direction, and the one step 3 made available for free —
  is not computed in precisely the state that needs it.

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

### Step 6a — Locate the residual error · **DONE** · `ResidualDiagnostic`

Both paths reached `hand-13-rough` with a sound unit estimate and both got roughly two
thirds of it wrong. `ResidualDiagnostic` hands each stage the generator's ground truth in
turn, on the file path, where there is no warmup, no bootstrap and no chunk dependency:

| | `hand-13-rough` | isolates |
|---|---|---|
| **A** shipped file path | **64.6 %** | baseline |
| **B** + frozen Kalman | **36.7 %** | **tracking** |
| C + truth models | 74.7 % | the fitted model |
| **D** + TRUE runs | **63.3 %** | **segmentation** |
| E + TRUE runs, truth models, frozen | 32.9 % | everything but the lattice |

Row A reproduces `DecoderBench`'s file column exactly on all five scenarios, which is what
validates the replication.

**The answer is tracking, and it is not close.** Freezing the pass-2 Kalman is worth
**27.9 points**; handing the decoder a perfect element timeline is worth **1.3**.

Segmentation is not merely adequate, it is exact:

| | `hand-13-rough` |
|---|---|
| measured marks against true | **214 / 214, 1.00×** |
| clean / fragmented / merged / spurious / dropped | **214 / 0 / 0 / 0 / 0** |
| median leading-edge error | 0.7 ms — **1 % of a dot** |
| median duration error | 1.4 % |

Two further results worth keeping:

- **Row C is *worse* than baseline** (74.7 % against 64.6 %). The "true" model — a 2.1:1
  ratio at σ 0.25 — describes this fist less well than the model fitted from its own marks
  at σ 0.33. The EM fit is doing its job; idealised parameters are not an improvement.
- **The tracker earns nothing on the file path.** Freezing it costs **0.0 points** on both
  `drift-ramp` (15 → 25 WPM over 60 s) and `wobble` (±15 %), the two scenarios that exist
  to exercise it. So on this path it is not a drift/jitter trade-off being struck badly —
  it is pure loss on the fixtures measured. See N-5.

What remains after every stage is given the truth is **32.9 %**, which is the lattice and
the mark/gap models under 25 % jitter. That is the floor this architecture reaches on this
fixture, and it is where any further D-6 work has to go once N-5 is dealt with.

### Step 6b — Stop the pass-2 tracker re-opening what pass 1 locked · **DONE** · `decisions.md` D-19

`RobustTimingDecoder.freezeDecodeUnit` locks the decode unit at pass 1's `u0` while the
Kalman tracks on only to report FM-2's speed track. The full argument, and the two
measurements that both refuted the approach this plan had written down, are in D-19:

- **The `unitMsLocal` track fails AC-6 at 42 %** — and so does every other signal the decoder
  produces, because with the frame frozen the online model absorbs the drift into `means[0]`.
  The report is measured against the frozen pass-1 mark centres instead, restoring the tracked
  `unitMsGlobal` (what `wpmTrack` already reads) to 8.0 % on the ramp.
- **Raw mode only.** The freeze strands errors the lexicon cannot flip on `hand-18`, failing
  AC-4; with the prior on, pass 2 tracks. `Ac6TrackDiagnostic` is the instrument.

Measured: file `hand-13-rough` 64.6 → 36.7 %, `qsb-impulse` declines → 45.6 % (an unplanned
second win — the tracker no longer runs away to the ceiling on the static crashes), file mean
11.6 → 6.6 %, live path byte-identical. `known-issues` D-6 stays open at the 32.9 % floor.

### Step 6 — Soft-decision front end · large · `prior-art.md` idea 1 · **not justified**

Stop making hard mark/gap decisions early; carry likelihoods into the lattice.

**Step 6a measured its ceiling and it is 1.3 points.** The argument was always that the
Schmitt trigger's hard decisions corrupt everything downstream; on `hand-13-rough` the front
end produces 214 marks against 214 true, none fragmented, merged, spurious or dropped, with
a median leading-edge error of 1 % of a dot. Handing the lattice the generator's own
timeline — the best any soft front end could converge to — moves CER 64.6 % → 63.3 %.

Its first justification (the 4× unit error) was removed by D-17 for a few lines of guard.
Its second (the residual after a correct timebase) is now measured and belongs to tracking,
not to the front end. **Do not start this on synthetic evidence.** It may still be right on
a real recording, where the envelope is not a clean +5 dB synthetic — but that is G-1's
question, and this step should stay closed until G-1 can pose it.

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
6. **Commit `HistoryBench.kt`?** (new) It is documented as living "in the session
   scratchpad", but scratchpads are per-session, so that is not a location. The D-18 run
   recovered it by searching an earlier session's directory by name. If it is lost this
   file's whole premise goes with it, because its own rule forbids re-baselining history
   with a newer probe. Committing it changes what the repo claims about itself — it would
   become a second bench sitting beside `DecoderBench` and disagreeing with it — which is
   why it is the owner's call and not a tidy-up.

*(Resolved: `CLAUDE.md` was rewritten for a repo that has code in it, `953baf5`.)*
