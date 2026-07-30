# Known issues

Open defects and gaps, with the evidence behind each. An item leaves this file when it is
fixed or when a decision record supersedes it.

Numbers quoted as "measured" come from a real 72-second phone recording of on-air CW
(1882 Hz, ~17 WPM, moderate fading) unless stated otherwise. It is the only real-world
sample the project has, which is itself issue **G-1**.

Severity is about what a user experiences, not how hard the fix is:

| | Meaning |
|---|---|
| **High** | Produces wrong output that looks correct, or blocks a real use case |
| **Medium** | Degrades a real use case; user can see something is wrong |
| **Low** | Cosmetic, or affects developers only |

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

This is D-8's stated scope ("it never inspects whether the input is Morse") meeting a
consequence D-8 does not record: **an octave-type unit error is invisible to every
quality signal in the system.** The output gate keys off `fitQuality`, so it cannot
suppress this; the UI shows `fit 1.00`; nothing warns.

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

## D-3 — The mains veto blocks every common sidetone · High

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

The requirement's own comment claims a keyed signal at 600 Hz "locks via its sidebands and
tracks in". That is doubtful at the working resolution: with a 4096-point FFT at 16 kHz
the bins are 3.9 Hz and a Hann main lobe spans about four of them, so keying sidebands at
±7 Hz merge into the vetoed main peak rather than appearing as separate maxima. **Untested
either way** — worth an experiment before any redesign.

Proposed fix: restrict the veto to low-order harmonics (≤ 8th, so below roughly 500 Hz)
and tighten to ±3 Hz, letting D-13's keying screen reject steady hum on its merits
anywhere above that. Amends FR-2.5, so it needs a decision, not just a patch.

---

## D-4 — Out-of-range flag is computed from a different number than the one displayed · Medium

`MorseDecoder.kt:68` derives `outOfCalibratedRange` from `u0`, the *bootstrap* unit, while
the UI displays `kalman.wpm` from pass two. The two can disagree arbitrarily.

Measured: the real recording decoded at **58.95 WPM with no "outside 5–40 WPM" warning**,
because the bootstrap that seeded it sat inside the band.

Fix is small — derive the flag from the reported speed. Worth going further: a large
disagreement between the bootstrap unit and the final unit is itself evidence the decode
is unreliable, and is currently discarded.

---

## D-5 — "No data" and "perfect fit" render identically · Medium

`ObservationFit.value()` returns a neutral `1.0` until at least 12 marks have been
observed (`ObservationFit.kt:35`). The UI renders that as `fit 1.00`, which is also what a
flawless decode shows. A lock that is producing nothing at all therefore displays maximum
confidence.

Distinct from **D-1**: that one is a genuine 1.00 on real measurements, this one is a
placeholder being shown as a measurement. Both mislead in the same direction.

Fix: render `—` until `obsFit.ready`, and surface a state for "locked, nothing decoding".
The engine-side half of this landed with D-13 (an unproven lock is now abandoned), but the
UI still shows no explanation while it happens.

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
