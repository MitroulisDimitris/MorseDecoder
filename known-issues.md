# Known issues

Open defects and gaps, with the evidence behind each. An item leaves this file when it is
fixed or when a decision record supersedes it.

Numbers quoted as "measured" come from a real 72-second phone recording of on-air CW
(1882 Hz, ~17 WPM, moderate fading) unless stated otherwise. It is the only real-world
sample the project has, which is itself issue **G-1**.

Every defect below has at least one test asserting the behaviour we want, marked
`@KnownFailure("D-n")`. Those tests fail today and are counted in a summary at the end of
each test run, so this file and the suite cannot drift apart: fix a defect and its test
starts passing, which **fails the build** until the annotation and the entry here are both
removed.

Severity is about what a user experiences, not how hard the fix is:

| | Meaning |
|---|---|
| **High** | Produces wrong output that looks correct, or blocks a real use case |
| **Medium** | Degrades a real use case; user can see something is wrong |
| **Low** | Cosmetic, or affects developers only |
| **FIXED** | Closed; kept briefly for the record, with its regression test named |

---

## D-1 — A unit-scale error scores a perfect fit · High

`modelFitQuality` reports **1.00** — maximum confidence — on wholly incorrect output.

Measured on the live path against the real recording: 67 characters emitted, observation
window fully populated (`fitSamples = 54`), observed fit `p10 = 0.91, p50 = 1.00`, while
the speed estimate read 30.6 WPM against a true 17.7 and the transcript was
`IEE ETI E TEE ETEE HT TE...` — nothing but single-element letters.

The cause is structural, not a scoring bug. `fitQuality` is `qSep × qRatio × qVar`
(`RobustTimingDecoder.kt:437`): cluster separation, dash/dot ratio plausibility, and
within-cluster tightness. An estimate anchored to the wrong *scale* preserves all three
perfectly — halve the unit and true dots read as 2 units, dashes as 6, still two tight
clusters at 3:1. The model fits its own measurements beautifully; the measurements are
just referred to the wrong unit.

**Reproducible synthetically** — no longer only visible on the one real clip. Two bench
scenarios (`BENCH=1 gradlew :core:test --tests "*DecoderBench*" -i`) exhibit it:

| Scenario | Path | True speed | Reported | Chars vs ref | CER | Gate |
|---|---|---|---|---|---|---|
| `hand-13-rough` | live | 13 WPM | **53 WPM** | 145 vs 62 | 251 % | never engaged |
| `qsb-impulse` | file | 18 WPM | **60 WPM** (clamped) | 121 vs 62 | 168 % | n/a |

The bench's starkest result is negative space: **`outputSuppressed` was false in all 16
scenarios**, including those two. The output gate is inert against scale error across the
entire matrix, which is what makes this a capability gap rather than a tuning problem.

This is D-8's stated scope ("it never inspects whether the input is Morse") meeting a
consequence D-8 does not record: **an octave-type unit error is invisible to every
quality signal in the system.** The output gate keys off `fitQuality`, so it cannot
suppress this; the UI shows `fit 1.00`; nothing warns.

**Its only reproducer is gone, and the gap is not.** D-17 removed the seed error that
produced the 4× scale error, so no scenario in the synthetic matrix now exhibits one — the
live path reports 21.5 WPM on `hand-13-rough` and plausible speeds everywhere else, and a
probe over dash-heavy and dot-heavy openings could not construct a replacement. The
`@KnownFailure("D-1")` test therefore passes and its annotation has been removed; it now
guards D-17 instead.

Nothing about the capability changed. What remains as evidence:

- the real recording, which still reads 30.6 WPM against a true 17.7 on the live path —
  uncommitted, so blocked on **G-1**;
- `fitQuality` reporting **1.00 on decodes measured at 13–23 % CER** (dash-heavy and
  dot-heavy openings, +10 dB, 13 and 20 WPM). Confidence is still uncorrelated with
  correctness even where the unit is roughly right.

A defect with no reproducer is one nobody will notice regressing. Restoring one — from the
real corpus, or by injecting a known-wrong unit directly — should come before any further
work that assumes scale errors are visible.

Candidate directions, none yet chosen:

- Cross-check the unit against gap structure. Gaps are excluded from *estimating* the
  unit for good reasons (D-2), but a 2× unit error predicts inter-character gaps at 6
  units and word gaps at 14, which is checkable without using gaps as an estimator.
- Score the decoded *alphabet distribution*. A transcript that is 90 % `E`/`T`/`I`/`M`
  is prima facie a scale error — real text is not. Cheap, and independent of timing.
- Treat a large disagreement between the streaming unit and a periodic batch re-fit as a
  degraded state.

---

## D-2 — Live path decodes real hand-sent audio far worse than file import · High

Same audio, same tone, same build:

| Path | Result |
|---|---|
| File import (two-pass) | 1881 Hz, 17.7 WPM, `- A L L BEST...U+ G4WQ<?> ...DL4...NCR<?> MQSA` |
| Live mic (streaming) | 1882 Hz, 30.6 WPM, `IEE ETI E TEE ETEE HT TE...` |

Tone selection is not the problem — both find 1882 Hz since D-13. The difference is the
warmup: file mode fits duration clusters over the whole recording and then decodes with
locked parameters (FM-1), while the live path bootstraps from the first 8 marks it sees
and tracks from there. On a fading real signal those first marks are unrepresentative,
and the resulting unit is wrong by roughly a factor of two — which then presents as
**D-1** rather than as an obvious failure.

Feeding only the clean section, or force-locking the correct tone, produces partially
legible output (`D L 4 ... C R M Q S RE O K`), so the streaming decoder is not broken —
it is fragile about where it starts.

Likely direction: re-bootstrap rather than commit once. A periodic batch re-fit over a
recent window, adopted when it disagrees materially with the tracked unit, would recover
from a bad start without abandoning the streaming architecture.

---

## D-3 — The mains veto costs tone-readout accuracy, not decoding · Low

**Downgraded from High after measurement. The original claim below was wrong**, and it is
kept because the correction is the useful part.

`isMainsHarmonic` (`LiveDecoderEngine.kt:915`) rejects ±5 Hz around every multiple of
both 50 Hz and 60 Hz. Across 300–1200 Hz that is **325 of 901 integer frequencies (36 %)**,
and it includes essentially every pitch an operator actually chooses:

```
400  VETOED     700  VETOED     1000 VETOED
500  VETOED     750  VETOED
550  VETOED     800  VETOED
600  VETOED     900  VETOED
```

FR-2.5 names 600 Hz explicitly ("600 Hz is a harmonic of both"), so this is implemented
as specified rather than by accident — but the consequence is that a radio set to a round
sidetone can never be auto-locked. Widening the band to 2000 Hz (D-13) added more vetoed
multiples, not fewer.

It has stayed invisible because every synthetic fixture uses an off-round tone — 588,
612, 685, 704, 742, 890, 1462, 1882 Hz. Nothing in the suite sends on 600.

### The correction

FR-2.5's own comment claims a keyed signal at a vetoed frequency "locks via its sidebands
and tracks in". I argued that was doubtful — 3.9 Hz bins and a Hann main lobe about four
bins wide should merge ±7 Hz keying sidebands into the vetoed main peak — and flagged it
untested. The bench tested it, and **the spec was right**:

| Scenario | Path | Tone error | CER |
|---|---|---|---|
| `tone-700-mains` (700 Hz, a 50 **and** 60 Hz multiple) | live | **0.1 Hz** | 2.5 % |
| `tone-700-mains` | file | 0.1 Hz | 0.0 % |

Acquisition happens at a nearby non-vetoed peak and the tracking EMA then walks the lock
onto the true frequency, which the veto does not prevent — it only forbids *acquiring*
within ±5 Hz, not *arriving* there. Decoding at a round sidetone is unaffected.

### What is actually wrong

A real but much smaller cost: the tone **readout** can settle off-target. `tone-420`
(420 Hz, a 60 Hz multiple) locked **19.5 Hz off** while still decoding at 2.5 % CER. The
lock is good enough to decode and the displayed frequency is wrong by enough to matter if
the user is using it to tune a radio.

Fix, if it is worth one: restrict the veto to low-order harmonics (≤ 8th, so below roughly
500 Hz) and tighten to ±3 Hz, letting D-13's keying screen reject steady hum on its merits
above that. Amends FR-2.5, so it needs a decision. At Low severity there is no hurry.

---

## D-4 — Out-of-range flag computed from a different number than the one displayed · FIXED

`MorseDecoder` derived `outOfCalibratedRange` from `u0`, the pass-1 bootstrap unit, while
the displayed `wpm` comes from the pass-2 Kalman. The two could disagree arbitrarily, and
did: the field recording decoded at **58.95 WPM with no "outside 5–40 WPM" warning**,
because the bootstrap that seeded it sat inside the band.

Now derived from the reported unit, after pass 2. The ~6 % margin is unchanged, so a
signal sent at exactly 5 or 40 WPM is still not flagged by measurement noise alone.

Covered by three regression tests in `HonestFailureTest`: out-of-band is flagged, in-band
is not, and both edges of the band are not.

---

## D-5 — "No data" and "perfect fit" rendered identically · FIXED

`ObservationFit.value()` returned a neutral `1.0` until twelve marks had been observed, and
the UI rendered that as `fit 1.00` — the same thing a flawless decode shows. A lock
producing nothing at all therefore displayed maximum confidence, and measurements found
characters being emitted with *zero* fit samples behind them.

`LiveDecoderEngine.fitQuality` is now `Double?`, null until the window is ready, and the
readout shows a muted `—`. Nullable rather than a sentinel on purpose: it forces every
call site to decide which case it is holding, where a NaN or a 1.0 lets the ambiguity
travel silently. Internal gates already guarded on `obsFit.ready` and are unaffected.

Covered by `HonestFailureTest`: unknown before any marks, reported once the window fills.
The API change also strengthened AC-10, which now requires the fit to be *known* as well as
above 0.45 after a recovery.

---

## D-6 — A rough fist at 13 WPM is decoded badly · High

**Downgraded from catastrophic by D-17, not closed.** The cause is now measured rather
than suspected: a single 25 ms fragment of a dash formed the entire lower cluster of the
bootstrap seed and set the timebase 4× short. `decisions.md` D-17 has the diagnosis and the
guard; the numbers below are the current state.

`hand-13-rough` (25 % jitter, 12 % per-character bias, 2.1:1 dash ratio, +5 dB) is still
the worst scenario in the bench, and still fails differently in each path:

- **file** — emits **nothing at all** (0 characters). Unchanged by D-17, which is live-path
  only; the `fitQuality < 0.12` guard still declines outright.
- **live** — **65.9 % CER**, 99 characters against 99 sent, at a reported 21.5 WPM against a
  true 13. The character count is now right and the timebase is out by 1.65× rather than 4×.

| Commit | chars emitted (99 sent) | CER |
|---|---|---|
| `ad5479d` … `cb358e7` (pre-D-13) | 66 | 69 % |
| `6c250a3` (D-13) … `92fd11e` | 274 | 308 % |
| `9b52819` (D-16) | 194 | 207 % |
| **D-17 seed guard** | **99** | **65.9 %** |

Below the first commit at last, and the first change in the project to improve the worst
case rather than the mean. What remains is ordinary inaccuracy rather than a broken
timebase: two thirds of the characters are still wrong, which is a decode-quality problem
on the hardest legitimate fixture, not a capability gap.

The two paths still fail for different reasons, and the file path's is untouched: its
`fitQuality < 0.12` guard declines the whole transmission, and `MorseDecoder.twoMeansSeed`
has the same unguarded 2-means that D-17 fixed on the live side. Porting the guard there is
the obvious next move and has not been done.

**What the history says about D-13.** The pre-D-13 decoder under-emitted here (66 of 99).
D-13 made it lock reliably, and a reliable lock on a signal whose unit estimate is 4×
wrong produced a flood of wrong characters instead of a trickle — 274, a 4× regression that
went unnoticed for three commits. D-16 recovered a third of it and D-17 removed the cause.
The lesson stands independently of the fix: **better tone acquisition is worthless, and
actively harmful, without a working unit estimate.**

The impairment levels are aggressive but not unrealistic — this is what a beginner on a
straight key in a noisy room looks like, which is squarely inside the intended use.

The remaining 65.9 % is no longer attributable to the bootstrap. Where it goes next is an
open question the diagnostic can answer: `BootstrapDiagnostic` reports the seed is now
sound, so the error is accumulating in tracking, segmentation under 25 % jitter, or the
lattice. Worth measuring before assuming the soft-decision front end
(`prior-art.md` idea 1) is the answer — that argument rested on the 4× error, which is
gone.

---

## D-7 — Below the SNR floor the decoder invented text rather than declining · FIXED

At −5 dB the live path emitted **167 characters against 99 sent** (CER 108 %). Accuracy
targets are void below 0 dB so being *wrong* was allowed; being confidently verbose was
not (P-4).

Closed by D-16: characters computed from a speed estimate pinned at the physical clamp are
no longer emitted. Measured, live path:

| | chars (99 sent) | CER |
|---|---|---|
| +10 dB, before / after | 98 / 98 | 1.6 % / 1.6 % |
| 0 dB, before / after | 98 / 98 | 1.6 % / 1.6 % |
| **−5 dB, before / after** | **167 / 80** | **107.9 % / 38.1 %** |

Nothing changes at or above 0 dB — the gate only engages where the estimator has stopped
measuring. Bench mean fell from 29.0 % to 17.9 % on the strength of this scenario and
`hand-13-rough`.

Covered by `HonestFailureTest`; a genuine 45 WPM sender still decodes and is merely
flagged, so FR-5.7's best-effort band is untouched.

---

## D-8 — Speech and RTTY decode as Morse · High

Audio that is not Morse produces invented text. Measured over 20 s fixtures
(`NonMorseTest`):

| Input | live chars | file chars |
|---|---|---|
| speech (syllabic rhythm, no keying) | 26 | 31 |
| RTTY (45 baud FSK) | **70** | **124** |
| overloaded/clipping input | 10 | 19 |
| speech + mains hum | 22 | 0 |
| music + noise | 50 | 0 |
| knocking / handling noise | 0 | 30 |

Silence, white and pink noise, a steady carrier, mains hum alone, and music alone all
correctly produce nothing, so the low-signal guard and D-13's keying screen are doing
their share. What gets through is anything with *keyed-looking envelope structure*.

**This cannot be fixed by tuning the output gate, and that is now measured rather than
argued.** Over the same fixtures, `modelFitQuality` scores:

| | p10 | p50 | p90 |
|---|---|---|---|
| RTTY (not Morse) | 0.70 | 0.75 | 0.76 |
| hand-sent Morse at +10 dB (legitimate) | 0.64 | 0.78 | 0.88 |

RTTY's distribution sits *entirely inside* the legitimate one. No threshold admits real
hand-sent Morse and rejects RTTY. `NonMorseTest` pins this as an explicit negative
assertion so nobody concludes the gate merely needs a better number.

Why RTTY is so convincing: runs of *k* identical bits produce marks of *k* × 22 ms, so the
durations really are multi-modal and a two-cluster fit lands near 3:1 — the exact structure
`fitQuality` is looking for. It is not being fooled; it is answering the question D-8 says
it answers ("does my model fit my measurements?"), which is not the question being asked.

### Measured: a structure test is partly viable, and synthetic data alone would mislead

`StructureBench` (`BENCH=1 … --tests "*StructureBench*"`) measures four cheap statistics
over the timing distribution, looking for one that separates where `fitQuality` cannot.

The strongest is **gapErr**: fit the dot unit from *marks only* (D-2), then measure how far
the *gaps* fall from the 1/3/7-unit lattice they should occupy. Marks and gaps sharing one
unit is a property of Morse that nothing else has reason to satisfy, and nothing in the
decoder currently checks the two against each other.

| | gapErr | spread (p95/p5 of marks) | r2 (two-cluster residual) |
|---|---|---|---|
| synthetic Morse, 16 fixtures | 0.015 – 0.263 | 2.94 – 4.75 | 0.004 – 0.267 |
| **real recording, hand-sent** | **0.384 – 0.452** | 3.67 – 3.71 | 0.084 – 0.112 |
| speech, 4 seeds | 0.663 – 0.802 | 2.61 – 3.67 | 0.161 – 0.205 |
| RTTY, 4 seeds | 0.291 – 0.313 | 5.50 – 6.63 | 0.311 – 0.334 |

**The load-bearing row is the real recording.** Tuned on the synthetic rows alone, gapErr
lands on a threshold near 0.40 — which **rejects genuine hand-sent Morse (0.45) and accepts
RTTY (0.29)**, the precise inversion of what is wanted. Genuine hand-sent code is
*further* off the lattice than a teleprinter is. This is G-1 stated as a concrete
near-miss: synthetic fixtures alone would have produced a confidently wrong gate.

With the real recording included, the statistics turn out to be complementary rather than
redundant, and no single one suffices:

- `gapErr > 0.60` catches speech, music, knocking and the mixes. It cannot catch RTTY.
- `spread > 5.0` or `r2 > 0.29` catches RTTY. Neither can catch speech, which sits inside
  the Morse range on both.

Combined, that rejects **6 of 7** non-Morse fixtures while accepting **all 16** Morse
fixtures *and* the real recording. `clipping` escapes.

**Not implemented, deliberately.** The margins are 1.05–1.13× on a single real recording.
A gate that wrong-foots one genuine transmission is worse than the defect it fixes, and
one recording cannot establish where hand-sent Morse actually sits — the sample that
mattered most is the sample we have exactly one of. This becomes shippable when G-1 does.

The original hypothesis in `prior-art.md` — that occupancy at 2 units is forbidden and
therefore diagnostic — is **refuted** and recorded there. Real fists run 2:1 to 2.5:1
(decisions.md D-3), so legitimate dashes land squarely in the supposedly forbidden zone;
measured, speech scores 0.19 on that statistic against 0.28–0.36 for a rough fist, i.e.
*better* than genuine Morse.

Two further holes in the same area, visible in the measurements:

- **Emission begins before the fit window is ready.** `clipping` and `speech+hum` emitted
  10 and 22 characters with *zero* fit samples recorded — the gate was never consulted
  (see **D-5**). Requiring positive evidence before emitting would close this, at the cost
  of roughly the first 6–7 characters of every genuine transmission.
- The rejected "is this Morse?" classifier (**D-8** in `decisions.md`) was cut as not worth
  the complexity. This evidence is an argument to revisit that decision; a modality test
  over the duration histogram is what separates RTTY's flat/multi-modal spread from Morse's
  bimodal one.

---

## D-9 — A tone change mid-session strands the lock permanently · High

The operator moves frequency, or the user tunes the radio. Measured: a jump from 612 Hz to
1150 Hz mid-session yields **2 characters** for the whole second transmission, and the lock
never leaves 612 Hz. Only a manual Re-lock recovers it.

It is a deadlock between two guards that are each individually correct:

- the automatic re-lock needs `poorElements` to accumulate (FR-2.10.1, counting elements
  not seconds — D-7), but a lock on a now-empty frequency produces *no elements*, so the
  counter never advances;
- FR-2.10.2 suspends the check entirely while `lowSignal` holds, which it does.

D-13's dead-end recovery breaks exactly this tie, but was deliberately scoped to locks that
had **never** decoded anything. This lock had, so it is excluded.

The fix has a real trade-off, which is why it is not already done: extending recovery to
proven locks means a lock could be taken away during a legitimate pause if another station
is active elsewhere — precisely the asymmetry D-7 chose to avoid. A defensible shape is
"re-lock only if the current lock has produced nothing for N seconds **and** a keyed peak
exists elsewhere in the band", which stays safe for AC-9 because silence offers no keyed
peak. Needs a decision on N and on whether to accept the D-7 trade at all.

---

## D-10 — Tone readout stays off after recovering from a wrong lock · Medium

After the decoder recovers from a lock taken on room noise, the tracked frequency settles
well away from the true tone and stays there: measured **~76 Hz low** on a 612 Hz signal.

Decoding is unaffected — the 10 ms analysis window is roughly 100 Hz wide, so the keying
envelope survives the offset, and the tail of that decode is clean. But the `tone` readout
and the spectrum lock marker are simply wrong, which matters to anyone using them to tune a
radio, and the margin against a genuinely lost lock is much thinner than it looks.

Related to **D-3**, where a mains-vetoed frequency settles ~20 Hz off for a different
reason. Both suggest the tracking EMA has no mechanism to re-centre once it is off by more
than the hysteresis window (`trackWindowHz` = 15 Hz).

---

## G-1 — No real recordings in the test corpus · High (as a risk)

Every fixture is synthetic. The requirements have flagged this since v1 (§7, "Real
corpus"), and this session showed concretely why it matters: **synthetic audio cannot
reproduce the failures real audio produces.**

A noiseless synthetic keyed tone stays decodable from *outside* the search band, because
raised-cosine key transitions scatter skirt energy across the spectrum and that skirt
carries the full timing envelope. Real recordings do not behave that way — their in-band
content is room noise uncorrelated with the keying (measured correlation 0.26–0.30 against
the true envelope). Two regression tests had to have noise added before they exercised the
bug they were written for.

The one real clip that exists is not committed. Blocked on a decision: a ~20 s excerpt as
16 kHz mono WAV is about 640 kB, and a useful CER assertion needs a ground-truth
transcript that has not been established — only fragments are legible (`ALL BEST`, a
callsign, `DL4`). A weaker tone-lock-plus-character-count assertion is possible today.

---

## G-2 — Never run on a physical device · High (as a risk)

Everything is verified by unit test and compilation only. No AVD and no `cmdline-tools`
are configured, so no build of this app has ever executed.

Entirely unexercised as a result: microphone capture and `AudioRecord` buffer sizing,
the foreground service and its wakelock, runtime permissions, SAF import through
`MediaCodec`, audio-focus handling, Bluetooth mic routing, and every Compose layout at
real screen sizes.

`AudioFileImporter` in particular is unproven against real device output. It assumes
16-bit PCM from `MediaCodec` (`out.asShortBuffer()`); a device that emits float PCM would
produce silence rather than an error.

---

## G-3 — Release builds are unsigned · Low

`app/build.gradle.kts` has a `release` block with no `signingConfig`, so
`assembleRelease` yields `app-release-unsigned.apk`, which will not install. Debug builds
are fine and are what sideloading has used.

Needs a keystore plus a `signingConfigs` block reading credentials from a gitignored
properties file. Deliberately not done — where the keystore and its passwords live is the
owner's call.

---

## Accepted, not defects

Recorded so they are not re-reported.

- **FR-7.4 (assist master switch) is not implemented.** Deliberate, see D-12.
- **`CLAUDE.md` still describes the repo as a specification handover with no code.** True
  at the first commit, false since. Rewriting it changes how agents work in this repo, so
  it is the owner's call.
- **§8 of the requirements is still open** — export-with-audio, practice mode.
- **First launch prompts for the microphone with no preamble.** A consequence of opening
  straight into listening; the rationale banner only appears after a denial.
- **`LocalClipboardManager` is deprecated** (`MainActivity.kt:125`). Build warning only;
  `LocalClipboard` is the replacement.
