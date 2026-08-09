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

## D-1 — A unit-scale error scores a perfect fit · FIXED

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

**It used to arise on its own**, on two bench scenarios (`BENCH=1 gradlew :core:test
--tests "*DecoderBench*" -i`) — **both since closed**, which is how the reproducer came to
be lost:

| Scenario | Path | True speed | Reported | Chars vs ref | CER | Gate | Since |
|---|---|---|---|---|---|---|---|
| `hand-13-rough` | live | 13 WPM | **53 WPM** | 145 vs 62 | 251 % | never engaged | 15.1 WPM (D-17, D-21) |
| `qsb-impulse` | file | 18 WPM | **60 WPM** (clamped) | 121 vs 62 | 168 % | n/a | 19.2 WPM (D-19) |

The bench's starkest result was negative space: **`outputSuppressed` was false in all 16
scenarios**, including those two. The output gate is inert against scale error across the
entire matrix, which is what makes this a capability gap rather than a tuning problem.

This is D-8's stated scope ("it never inspects whether the input is Morse") meeting a
consequence D-8 does not record: **an octave-type unit error is invisible to every
quality signal in the system.** The output gate keys off `fitQuality`, so it cannot
suppress this; the UI shows `fit 1.00`; nothing warns.

**The reproducer was lost, and has been restored** (step 4a). D-17 removed the seed error
that produced the 4× scale error, so no scenario in the synthetic matrix bootstraps one any
more — the live path reports 21.5 WPM on `hand-13-rough` and plausible speeds everywhere
else, and a probe over dash-heavy and dot-heavy openings could not construct a replacement.
The old `@KnownFailure("D-1")` test therefore started passing **without anything having been
fixed**; its annotation was removed and it now guards D-17 instead.

The replacement injects the wrong unit directly, through **FR-9.5's WPM pin** — the only
supported way to hand the decoder a unit known to be wrong. A clean 20 WPM signal (612 Hz,
+10 dB, the fixture the rest of `HonestFailureTest` uses) decoded against a unit pinned at
40 WPM:

| | pinned 40 WPM | true 20 WPM, unpinned |
|---|---|---|
| characters emitted (62 sent) | **202** | 61 |
| CER | **297.5 %** | 2.5 % |
| transcript is `E`/`T`/`I`/`M` | **98.5 %** | 21 % |
| `fitQuality` | **1.00** | 1.00 |
| `outputSuppressed` | **false** | false |
| `lockState` | **LOCKED** | LOCKED |

`TA C Q TEE E EEE EEET ETTTT ET TEEE TETE TET T EEEE` at maximum reported confidence. The
observation window is full — 64 fit samples at `p10 = p50 = 1.00`, against the real
recording's `p10 = 0.91, p50 = 1.00`, so the synthetic reproducer is if anything a *cleaner*
instance of the defect than the clip that first showed it.

Two tests hold it, deliberately separated (`HonestFailureTest`):

- `a unit pinned an octave out does not reproduce the text` — passes, and guards the
  **premise**. The last reproducer evaporated silently when the input that triggered it was
  removed; this one asserts CER > 0.5, which survives any legitimate fix (a decoder that
  suppressed the output would score 100 %) and fails loudly if the scale error stops
  happening.
- `a decode built on a wrong unit is flagged by some signal` — asserts the **defect**. It
  was `@KnownFailure("D-1")` when written and passes since D-22 below.

**The pin is the vehicle, not the defect.** `ObservationFit` scores 2-means clusters over
*normalised* durations `ln(duration / unit)`, and `fitQuality` is separation × ratio ×
tightness over those clusters — every term is invariant to the unit, so a uniform `ln 2`
shift changes none of them. Where the wrong unit came from is irrelevant to whether anything
can see it.

The pin also disables D-21's periodic re-fit (FR-9.5 owns the unit while pinned), so the one
signal that might have caught this — a streaming-vs-batch disagreement — is not computed at
all in this state. That is a real gap in its own right.

The direction of the error matters, and the asymmetry is worth recording. Pinned *slow* the
decoder largely recovers (10 WPM against a true 20: CER 15.2 %; 13.3 WPM: 2.5 %, identical
to unpinned) because the lattice's per-character shrinkage absorbs it. Pinned *fast* it
shatters (30 WPM: 38.0 %; 40: 297.5 %; 53: 310.1 %) — a short unit reads every dash as a run
of dots. Only the extreme slow end trips anything: at 6.7 WPM the fit collapses to 0.02 and
the gate suppresses correctly, which is the gate working on a *shape* error, not a scale one.

### Closed by output plausibility · `decisions.md` D-22

Something notices now: `LiveDecoderEngine.outputImplausible` and
`FileResult.outputImplausible`. The reproducer above trips it at **1.03 marks per committed
character** against 3.4 for real text.

The scale-freedom argument is what settled the design. It rules out both of the timing
cross-checks this entry used to list as candidates — there is no absolute anchor anywhere in
the timing to check against, because every quantity in Morse is a ratio. It leaves exactly
one place the freedom breaks, the **decoded characters**, and that is what the fix reads. The
statistic, the 48-character window, the 1.9 threshold and the 1.50× margin behind it are all
in D-22, along with the measurement that killed the shorter windows: at 16 characters a
healthy `hesitation` decode dips *below* a genuine scale error.

**It reports; it does not suppress**, and that is a decision rather than an omission — the
two rows binding the margin are the two most human fixtures in the corpus, which is lesson
5's exact trap, and after 48 characters the wrong output is already emitted and P-2 forbids
retracting it. So this closes "nothing can see it", not "it cannot happen".

Regression tests, all passing: `HonestFailureTest."a decode built on a wrong unit is flagged
by some signal"` and `."a unit pinned an octave out does not reproduce the text"` (the
reproducer, which must keep failing to decode), plus `."a rough fist and a hesitant sender
are not flagged as implausible"` for the false-positive side.

Note `fitQuality` still reads **1.00** on the reproducer and that is now understood to be
**correct**: it answers whether the two-cluster model fits its own measurements, and under a
scale error it genuinely does. Forcing it down would make it lie about its own question.

Two things this did not close, both carried forward rather than dropped:

- the real recording still reads 30.6 WPM against a true 17.7 on the live path, and is
  uncommitted — **G-1**;
- confidence is still uncorrelated with correctness where the unit is roughly right, which
  is now **D-11** below, with its obvious candidate measured and ruled out.

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

**Narrowed by the periodic re-bootstrap (`decisions.md` D-21), not closed.** The "never
revisits" half is fixed: every 16 marks the unit is re-fit from a batch of recent marks
(2-means, segmentation-free) and adopted on a material disagreement. On the synthetic proxy
`hand-13-rough` — a 1.68× unit error, close to this recording's 1.73× — it corrects the
reported speed from 21.9 WPM to 15.1 (true 13) and takes CER 69.6 % → **65.8 %**. But the
gap to the file path (36.7 %) does not close, because the file path only reaches it by
*freezing* the unit (D-19), and N-5 established the freeze cannot transfer to the live path,
where the Kalman is the only estimate after bootstrap — so the tracker resumes chasing the
fist's per-character bias and the decode re-degrades. The re-bootstrap's real value is on a
recording worse than any synthetic, i.e. this one, which stays blocked on **G-1**.

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

- **file** — **36.7 % CER**, down from 64.6 %, since `decisions.md` D-19 stopped pass 2
  re-opening the locked unit and chasing the 12 % per-character bias (step 6b). Measured with
  `DecoderBench`. This is the tracking cost 6a had attributed; what remains is the lattice and
  the mark/gap models under 25 % jitter.
- **live** — **65.9 % CER** on `HistoryBench` (99 characters against 99 sent, reported
  21.5 WPM against a true 13; timebase out by 1.65× rather than 4×). On `DecoderBench`'s
  shorter text the same scenario read 89.9 % before step 2 and **69.6 %** after it
  (`decisions.md` D-20 made the live decode chunk-invariant; the 89.9 % was a 4096-chunk
  artefact). D-19 and D-20 are both live-neutral / file-neutral respectively for the *other*
  path — the live figure moved only because step 2 stopped it depending on the buffer size.

| Commit | chars emitted (99 sent) | CER |
|---|---|---|
| `ad5479d` … `cb358e7` (pre-D-13) | 66 | 69 % |
| `6c250a3` (D-13) … `92fd11e` | 274 | 308 % |
| `9b52819` (D-16) | 194 | 207 % |
| **D-17 seed guard** | **99** | **65.9 %** |

Live path only — D-18 did not touch it, and `HistoryBench` reports all 42 rows unchanged.

Below the first commit at last, and the first change in the project to improve the worst
case rather than the mean. What remains is ordinary inaccuracy rather than a broken
timebase: two thirds of the characters are still wrong, which is a decode-quality problem
on the hardest legitimate fixture, not a capability gap.

The two paths still fail for different reasons. **The file path's reason was diagnosed and
removed in `decisions.md` D-18, and it was not the one stated here for two commits.**
Porting D-17's seed guard — the "obvious next move" this entry used to recommend — is a
measured no-op: `decodeFile` clusters all 214 marks rather than the first eight, so it
splits at 2.19:1 with `u0` within 1.04× of truth. The seed was never the problem. What
declined the transmission was the `fitQuality < 0.12` veto, on a score that ranks this fist
(0.0987) below speech (0.4741), knocking (0.4033) and clipping (0.7386), all of which it
decoded. Reproduce with `DIAG=1 gradlew :core:test --tests "*FileGateDiagnostic*" -i`.

What remains on the file path is decode quality, not a veto: **36.7 % CER** (was 64.6 %
before D-19) on a fit whose unit is right to 4 %.

**What the history says about D-13.** The pre-D-13 decoder under-emitted here (66 of 99).
D-13 made it lock reliably, and a reliable lock on a signal whose unit estimate is 4×
wrong produced a flood of wrong characters instead of a trickle — 274, a 4× regression that
went unnoticed for three commits. D-16 recovered a third of it and D-17 removed the cause.
The lesson stands independently of the fix: **better tone acquisition is worthless, and
actively harmful, without a working unit estimate.**

The impairment levels are aggressive but not unrealistic — this is what a beginner on a
straight key in a noisy room looks like, which is squarely inside the intended use.

**The remaining error was attributed to the speed tracker, and D-19 removed it.** Step 6a
(`ResidualDiagnostic`) handed each stage of the file path the generator's ground truth in
turn:

| | `hand-13-rough`, file | isolates |
|---|---|---|
| shipped, pre-D-19 | 64.6 % | baseline |
| **+ frozen pass-2 Kalman** | **36.7 %** | **tracking — 27.9 points** |
| + perfect element timeline | 63.3 % | segmentation — 1.3 points |
| + both, plus truth models | 32.9 % | the floor: lattice and models under jitter |

Segmentation is not the problem and is not even marginal: 214 measured marks against 214
true, none fragmented, merged, spurious or dropped, median leading-edge error 1 % of a dot.
**This closed the question this entry used to leave open, and answered it against the
soft-decision front end** (`prior-art.md` idea 1), whose measured ceiling here is 1.3 points.

D-19 (step 6b) made that frozen-Kalman row the shipped file path: pass 2 now decodes with
the unit locked and the Kalman only reports FM-2's track. The file decode is **36.7 %**.
What it does *not* do: 36.7 % is a large improvement and still not a decode, and 32.9 % is
the floor this architecture reaches on this fixture even when handed the truth at every
stage. D-6 stays open on that basis, and the live path is untouched at 89.9 %. Below 32.9 %
needs a better lattice or better mark/gap models under jitter, and nobody has proposed
either.

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

**The file path had shipped that better number since the first commit, and it was inverted.**
`FileGateDiagnostic` scores the whole corpus through `decodeFile`'s pass 1, and a rough but
genuine fist (0.0987) ranks *below* speech (0.4741), knocking (0.4033), clipping (0.7386)
and RTTY (0.5228) — so the `fitQuality < 0.12` veto declined the one real transmission and
passed four impostors. `decisions.md` D-18 retired it in favour of the only claim the score
supports, a structurally impossible fit. That does **not** close D-8: the impostors above
were never being caught by that gate, and are not caught now.

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

## D-9 — A tone change mid-session strands the lock permanently · FIXED

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

**Fixed by `decisions.md` D-23.** A lock that has committed nothing for **10 s** is handed
over to a distinct keyed signal elsewhere in the band. Measured by ablation on one build:

| jump from 612 Hz | before | after |
|---|---|---|
| → 1150 Hz | 2 chars, 98.4 % CER | **88 chars, 11.9 %** |
| → 900 Hz | 2 chars, 98.4 % | **88 chars, 11.9 %** |
| → 1600 Hz | 2 chars, 98.4 % | **90 chars, 12.7 %** |
| → 420 Hz | 2 chars, 98.4 % | **88 chars, 12.7 %** |

The first transmission is untouched (96 characters, 4.0 %) and each recovery costs one
re-lock. `DecoderBench` is identical to its `noStall` ablation on all 16 scenarios.

The owner accepted the D-7 trade on 2026-08-09: a proven lock can now be taken during a
legitimate pause if another station is keying. AC-9 is held not by the timer but by the
requirement that **the current lock be silent** — a keyed frequency is never stranded — which
is what stops the same station's own sideband stealing the lock when it resumes. The full
account of the three conditions, each of which was bought with a broken test, is in D-23.

Regression test: `SessionSequenceTest."a tone jump mid-session is recovered and marked"`,
formerly the `@KnownFailure`. Watch its tone assertion rather than its character count: the
re-lock lands 19–42 Hz off across the four jumps and **D-10** says the EMA cannot re-centre
from there, so it passes at 18.7 Hz against a 25 Hz bar. A future failure on the frequency is
D-10 surfacing here, not this fix regressing.

---

## D-10 — Tone readout stays off after recovering from a wrong lock · Medium

After the decoder recovers from a lock taken on room noise, the tracked frequency settles
well away from the true tone and stays there: **~76–96 Hz** off on a 612 Hz signal.

Decoding is *mostly* unaffected — the analysis window is roughly 100 Hz wide, so the keying
envelope survives the offset — but at the larger offsets (measured up to ~96 Hz after the
D-20 sample-grid rewrite shifted acquisition) the offset does cost the decode, not just the
readout: a seed sweep put the recovered tone 92–98 Hz off on seven of nine seeds, and the
`SessionSequenceTest` recovery case lands ~80 Hz low with a 19 % tail. The `tone` readout and
the spectrum lock marker are wrong regardless, which matters to anyone using them to tune a
radio, and the margin against a genuinely lost lock is much thinner than it looks.

The `@KnownFailure("D-10")` test was re-pointed from morse seed 53 to seed 43 in D-20: the
deterministic acquisition now lands seed 53 on the tone by luck, but the re-centring mechanism
(the tracking EMA cannot re-centre once off by more than `trackWindowHz`) is untouched and
shows on almost every other seed. The defect is not fixed; only the fixture that exposed it
moved.

Related to **D-3**, where a mains-vetoed frequency settles ~20 Hz off for a different
reason. Both suggest the tracking EMA has no mechanism to re-centre once it is off by more
than the hysteresis window (`trackWindowHz` = 15 Hz).

---

## D-11 — Nothing correlates with how wrong the decode is · Medium

Split out of **D-1** when its scale-error half was closed by `decisions.md` D-22, because
that fix does not touch this and it would otherwise have been dropped.

Two scenarios decode at roughly two-fifths wrong while every signal the system publishes
reads healthy. Measured by `ScaleErrorDiagnostic` (`DIAG=1`):

| scenario | CER | `fitQuality` | mean confidence | marks/char | flagged? |
|---|---|---|---|---|---|
| `clean-20` | 2.5 % | 1.00 | 0.995 | 3.43 | — |
| `hand-18` | 10.1 % | 0.95 | **0.943** | 3.39 | — |
| `hesitation` | **39.2 %** | **1.00** | **0.978** | 2.73 | no |
| `qsb-impulse` | **41.8 %** | **1.00** | **0.990** | 2.77 | no |

**The obvious candidate is measured and inverted.** FR-6.6's per-character lattice confidence
is the decoder's own statement of how sure it is, and it ranks `qsb-impulse` at 41.8 % CER
(0.990) *above* `hand-18` at 10.1 % (0.943). Across the whole matrix it ranks a 297 % CER
scale error (0.893) above a 65.8 % rough fist (0.793). It is a within-lattice quantity — how
much better the winning path scored than its rivals — and under a wrong model every rival is
wrong too, so the winner still wins comfortably. It measures decisiveness, not correctness.

This is the same structural shape as D-1 and D-8 rather than a tuning gap: every score the
decoder computes is a **self-consistency** score, and self-consistency is exactly what
survives a systematically wrong answer. D-22 broke that circle once by reading the decoded
characters instead of the timing, and marks-per-character catches only the shattering mode —
`hesitation` and `qsb-impulse` produce normally-shaped characters that are simply the wrong
ones.

Held by `HonestFailureTest."a decode two-fifths wrong is distinguishable from a correct one"`,
`@KnownFailure("D-11")`.

Severity is Medium rather than High: unlike D-1 the output is *partly* right and visibly
ragged, so the user is not being told a wrong answer is a good one so much as not being told
how good it is. What would close it is a signal grounded outside the decoder's own model —
lexicon agreement scored without applying it (FR-7.1 is already implemented and off by
default, P-3), or the D-8 structure work. Both are blocked on **G-1**, which is also the only
place a threshold for this could honestly be set.

---

## D-12 — The UI can report "Listening" with nothing running · High

Found on the **first ever device run** (2026-08-09, emulator, API 36). The main screen
shows the green **Listening** indicator with Pause/Stop, every readout dashed, the spectrum
flat — and `dumpsys activity services` reports **no foreground service at all**. The app
looks like it is working and decodes nothing, indefinitely. Only Stop-then-Listen (or a
restart) recovers, and the user has no reason to try either.

The mechanism is in the code rather than the platform, and is visible without a device once
you know to look for it:

- `DecoderService.publish()` sets **`listening = true` unconditionally** (`DecoderService.kt:317`)
  on every published snapshot;
- `publish()` runs on `decodeExecutor`, off the main thread, one task per audio chunk;
- `onDestroy()` calls `decodeExecutor.shutdown()` — which does **not** cancel already-queued
  tasks — and only then sets `listening = false` (`DecoderService.kt:443`).

So any `publish()` still in flight when the service dies runs *after* the flag is cleared and
re-asserts it. `DecoderService.state` is a companion-object `MutableStateFlow` that outlives
the service, so nothing later corrects it, and `MainActivity` renders Pause/Stop for exactly
`state.listening` (`MainActivity.kt:256`).

The window is not narrow in practice: audio arrives every 80 ms and Stop is pressed while
capturing, which is the only time anyone presses it.

Not reproduced on a schedule yet — the screenshot evidence is a stuck state reached after a
Stop, and the mechanism above is read from the code rather than from an instrumented run. A
regression test should drive `onStartCommand` → `publish()` → `onDestroy` ordering directly;
that is JVM-testable once the executor is injectable, which it currently is not.

---

## D-13 — Readouts report values they do not have · Low

Three cosmetic-but-dishonest readings, all seen on the first device run and all the same
mistake D-5 fixed for `fitQuality`: rendering "no measurement" as a number.

| Readout | Shows when idle | Should be |
|---|---|---|
| debug fit strip | `p10 1.00  p50 1.00  p90 1.00  n0` | no percentiles at all with `n0` |
| `snr` tile | `0 dB` | `—`, as `wpm`/`tone`/`fit` correctly do |

The debug strip is the same "perfect over zero samples" rendering D-5 removed from the main
`fit` tile, surviving one layer down — and it is the readout `Config.emitFitSuppressBelow`
is meant to be calibrated against on real audio, so it is the one that most needs to be
honest.

Separately, the `emit` label in that strip is **clipped and wrapped** to `emi` / `t` at
1080 px. The accessibility tree carries the full string, so it is a width constraint in the
row, not a text bug.

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

## G-2 — Run on an emulator, never on real hardware · Medium (as a risk)

**Downgraded from High on 2026-08-09: the app has now executed.** First run ever, on the
`Medium_Phone_API_36.1` AVD (API 36, x86_64). The premise this entry carried was also wrong
— an AVD *and* its system image were already installed; only `cmdline-tools` is missing, and
that provides `sdkmanager`/`avdmanager`, which manage SDK packages and are not needed to
build, install or run. Nothing was ever blocked on the toolchain.

**Verified working on the emulator:**

| Item | Result |
|---|---|
| Install, launch, first frame | 3.2 s cold start, no crash |
| Compose layout at 1080×2400 | renders correctly; one clipped label (D-13) |
| Runtime permissions | RECORD_AUDIO then POST_NOTIFICATIONS, in sequence; the ungranted state correctly shows Idle rather than pretending to listen |
| Foreground service | `isForeground=true`, `types=0x80` (microphone), ongoing notification on channel `decoder` |
| Notification permission denied | FGS still runs — denial does **not** break capture |
| Microphone capture | live spectrum from the host mic; `AudioFlinger` record thread active |
| Audio focus | requested at start (`USAGE_MEDIA` — see below) |
| Stop | tears the foreground service down cleanly |

**`AudioRecord` buffer sizing is answered, and it is a non-issue.** `MicCapture.captureLoop`
reads a fixed `ShortArray(1280)` — 80 ms — and feeds exactly that to the engine regardless of
what `getMinBufferSize` returns; the hardware buffer only sizes `AudioRecord`'s internal
ring. Combined with D-20's chunk-invariance, **N-2's device-side concern is closed**: the
caller's buffer cannot reach the transcript by either route.

**Found by running it:** `known-issues` **D-12** (the UI can report Listening with no service
— High) and **D-13** (readouts showing values they do not have). Neither is reachable from the
JVM suite.

**Still unexercised, and why the risk is not closed:**

- **A real microphone.** An emulator's mic is the host's, with the host's AGC and its own
  resampling; nothing it reports about capture quality, noise floor or the keying threshold
  transfers to a phone in a room next to a radio. This is the whole point of the app and it
  remains untested.
- **SAF import end to end.** The picker opens; a decode of a pushed WAV was not completed, so
  `AudioFileImporter` and `MediaCodec` are still unproven. It assumes 16-bit PCM
  (`out.asShortBuffer()`) and a device emitting float PCM would produce **silence rather than
  an error**.
- Rotation and configuration change, the settings screen, audio-focus behaviour under a real
  interruption (a call, another app), Bluetooth SCO routing, wakelock behaviour with the
  screen off, and any screen size other than this one.

Audio focus is requested as `USAGE_MEDIA` for a capture-only app, which is worth a look — it
is the attribute for playback, and a recorder ducking other apps under a media usage is at
best an odd claim to make to the platform.

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
- **§8 of the requirements is still open** — export-with-audio, practice mode.
- **First launch prompts for the microphone with no preamble.** A consequence of opening
  straight into listening; the rationale banner only appears after a denial.
- **`LocalClipboardManager` is deprecated** (`MainActivity.kt:125`). Build warning only;
  `LocalClipboard` is the replacement.
