# Decision log

Rationale for choices that look arbitrary from the requirements alone. If you're about
to change one of these, read the reasoning first — several were reversed once already.

---

## D-1 — Speed range is 5–40 WPM, not unlimited

Hand-*formed* elements stop existing around 35 WPM. Above that a keyer generates the
dots and dashes and the timing is machine-accurate.

| Method | Typical range |
|---|---|
| Straight key, beginner | 5–13 WPM |
| Straight key, competent | 15–25 WPM |
| Bug (semi-automatic) | 20–35 WPM |
| Paddle + electronic keyer | 20–40 WPM |
| Contest / QRQ | 40–60+ WPM |

Most on-air CW sits at 15–25 WPM. The consequence for design: **wide timing jitter and
tight time resolution are separate problems that barely overlap.** Below 35 WPM there is
plenty of time resolution to spare, so a narrow filter and wide duration variances are
affordable. Above 35 the sender is a keyer, so variance is small and duration priors can
be trusted.

Unbounded range was rejected because it makes the accuracy targets unfalsifiable and
because both extremes fight the noise requirement: above 60 WPM a dot is under 12 ms and
the analysis window must shrink, widening the effective filter bandwidth.

---

## D-2 — Estimate the unit from marks only, never gaps

Three independent reasons, which is why this is stated as an invariant rather than a
tuning choice:

- Room reverb smears each dash's tail into the following gap, systematically shortening
  measured gaps.
- Hesitation inflates gaps arbitrarily and carries no timing information.
- Under Farnsworth timing, gap durations are decoupled from character speed by design.

Gaps are used for segmentation only, with asymmetric tolerance: a gap being too long is
nearly meaningless, a gap being too short is highly informative.

---

## D-3 — The 3:1 dash/dot ratio is a weak prior, not a constraint

Real fists land at 2:1 to 2.5:1. Bug senders produce machine-fast dots and hand-made
dashes, so the two clusters have wildly different variances. Fitting both centroids
independently and using 3:1 only to disambiguate degenerate fits is what makes hand-sent
code work at all.

---

## D-4 — Local per-character unit fitting is the drift mechanism

Relative timing *within* a character stays coherent even when absolute speed wanders. So
rather than classifying against a global unit that may be 40 seconds stale, fit a unit
from the current character's own marks, shrunk toward the global Kalman estimate.

This also handles per-character habits — real operators rush certain letters consistently.

The circularity (local fitting needs segmentation, segmentation needs the unit) is
resolved by not committing: hypothesise boundaries, fit per hypothesis, let Viterbi score
whole paths. This is why the lattice must exist from the start rather than being
retrofitted.

---

## D-5 — Lattice decoding is not a "correction"

P-3 says assists are off by default. It would be easy to read that as disabling the
lattice, which would gut hand-sent handling entirely.

The distinction: **lattice decoding uses timing evidence to resolve timing ambiguity.**
That is decoding. The lexicon and n-gram prior use *non-timing* evidence to change
characters. That is a correction, and only that falls under P-3.

---

## D-6 — Step-change detection deferred, but the reset path is not

Detecting an abrupt speed change is cheap once the Kalman tracker exists — two estimates
at different time constants and a divergence test. The expensive part is recovery.

But FR-2.12 already requires a mixture-reset path for frequency re-lock. Once that
exists, speed step-change recovery is largely the same machinery pointed at a different
trigger. Build the reset once; leave the speed trigger disabled rather than absent.

A cheap 80 % recovery, when it is reinstated: on divergence, temporarily inflate the
Kalman process noise so the tracker adapts fast for a few seconds, instead of a full
reset and mixture re-fit.

---

## D-7 — Re-lock guards are deliberately asymmetric

A wrong lock is visible to the user, who can hit the button. A dropped lock during a
legitimate pause is silent and destroys a working session. So the guards are biased
heavily toward staying put.

The load-bearing guard is counting **elements, not seconds**: silence produces no
elements, so a pause can never trigger a re-lock. AC-9 is the regression test for
exactly this.

---

## D-8 — `modelFitQuality` is not the rejected "is this Morse?" classifier

That classifier was cut as not worth the complexity. `modelFitQuality` asks a much
narrower and much cheaper question: does the decoder's own two-cluster model fit its own
measured durations?

It never inspects whether the input is Morse. It is a near-free byproduct of the mixture
fit, and it happens to catch wrong lock, off-frequency lock, lock hopping, and excessive
noise with one number.

---

## D-9 — Append-only, including on the error prosign

When a sender keys `HH` they are saying "scrub that." The tempting implementation is to
retract the preceding word.

Rejected under P-1 and P-2: the user should see what was actually sent and decide.
Senders make mistakes about their own mistakes, and a decoder that deletes text is one
that can delete correct text. The prosign is marked in place and the corrected sending
simply appends after it.

Same reasoning applies to the re-lock marker — the garbage preceding a re-lock stays,
annotated rather than removed.

---

## D-10 — Bad acoustics are out of recommended use

Heavy room reverb, competing speech, and music are input problems. The app does what it
can (narrowband Goertzel, impulse filtering) and then warns rather than attempting
heroic compensation.

This is P-4. A decoder that produces plausible-looking output from unusable input is
worse than one that says "I can't hear this."

---

## D-11 — 60 WPM is a hard ceiling on the estimator, not just a warning band

D-1 already draws the line: above 60 WPM a dot is under 12 ms. The estimator has to
enforce it, because the failure is self-reinforcing rather than self-correcting.

The glitch gate scales with the estimated unit — marks under 0.3 units are discarded as
impulse debris. So a unit estimate that drifts short also *shortens the gate*, which
admits shorter noise blips as real marks, which pulls the estimate shorter again. On
ambient room sound the loop ran to 100+ WPM, a speed nothing sendable produces.

Three enforcement points, all cheap:

- a bootstrap implying more than 60 WPM is discarded outright, not seeded — it is noise
  that happened to cluster, not a fast sender that was mis-measured
- the Kalman unit is clamped, so tracking cannot walk past the ceiling afterwards
- while the clamp is holding, the speed readout shows **nothing**, not 60

That last one is P-4. A clamped estimate is a bound being enforced, not a measurement,
and displaying it as a number invites the user to trust it. The 5–40 WPM band of FR-5.7
is a different statement — "outside where we promise accuracy," decode anyway. This is
"outside what physically exists," so there is nothing to report.

---

## D-12 — No assist master switch; FR-7.4 is not implemented

FR-7.4 asks for a global switch that disables all assists at once. With two assists that
both default off, it earns nothing: the only way to reach a state where the master does
something is to turn an assist on first, and then turning that assist back off is the
same gesture. It also carried a trap — the master defaulted *on* while the individuals
defaulted off, so the one chip reading `assists · on` sat next to two reading `off`,
which is exactly the misreading P-3 exists to prevent.

Each assist is now independently toggled, and nothing else changes: the export schema
already recorded only the effective per-assist booleans, so `:core` is untouched.

Revisit if the assist count grows. A master switch is worth having over five of these;
it is not worth having over two.

---

## D-13 — Search 300–2000 Hz, and pick peaks by keying rather than by power

Two changes from one field report: a 72-second phone recording of real CW that the app
could not decode at all. Measured, its tone sat at **1882 Hz**. FR-2.1 searched 300–1200,
so the signal was never a candidate; the app locked the loudest thing it *could* see —
room noise at 363 Hz — and reported 59 WPM of garbage.

### The ceiling was never a physical limit

Unlike the 5–40 WPM band (D-1), which follows from how fast a hand can form an element,
1200 Hz followed from nothing. Sidetone pitch is operator preference, and a recording
chain shifts nothing but adds its own resonances. 600–800 Hz is conventional; this
recording was made by holding a phone to a small speaker and landed far higher.

2000 Hz covers it with headroom, and the ceiling is now a user setting (FR-9.14) because
no single value can be right for every recording chain. The 300 Hz floor stays fixed —
below it is mains hum and handling noise, and nothing sends there.

### Loudness was the wrong selection criterion

Widening the band alone is not enough, and is arguably worse: more band means more
non-Morse peaks to lose the lock to. In this recording a 2120 Hz component beat the real
tone outright for the first 8 seconds.

So peaks are now scored on **whether they are being keyed** — a p95/p20 envelope ratio
plus a minimum number of on/off transitions — and the strongest *keyed* peak wins. This
is the only property that distinguishes CW from every other narrowband signal; hum, data
carriers and room tone are all steady. Two calibration notes, both learned the hard way
against the real file:

- **Amplitude spread alone is not enough.** A single noise burst against a quiet
  background produces the same wide p95/p20 gap that keying does. Keying is *repeated*,
  so a transition count is required as well.
- **The ratio must be floored relative to its own scale.** Real recordings contain true
  digital silence; p20 lands on exactly zero and an unguarded ratio explodes past 1e17,
  which made silence score as the most strongly keyed thing in the band. A ratio against
  zero is evidence of nothing.

Measured separation on the real clip: the keyed carrier scores 465 at its 10th percentile
over a 600 ms probe; the loudest unkeyed content never exceeds 33. The bar sits at 40.

### A lock that decodes nothing is abandoned

The screen only helps at the moment of acquisition, and a wrong lock made anyway was
previously permanent: the re-lock guards count elements (D-7, rightly), but an unkeyed
carrier produces no elements, so the counters never advance and the search never resumes.
`LOCKED`, confident, decoding nothing, indefinitely — which is what the report described.

So an unproven lock — one that has never emitted a character — is dropped after ~15 s, or
after ~8 s if its frequency carries signal that is not keying. The invariant is that **a
lock which has decoded a character is never dropped by these guards**; a working lock that
later degrades remains FR-2.10's job.

Gating on `lowSignal` instead was tried and is wrong: it is computed over a 10 s window,
so it lags real silence by up to 10 s — long enough to trip a sweep counter mid-pause and
break AC-9. "Has this lock ever produced anything" is the condition that actually
separates the case worth protecting from the case worth abandoning.

### What this did not fix

The file-import path now decodes the clip (1881 Hz, 17.7 WPM, legible). The live path now
*finds* the same tone, but decodes it far less well than the two-pass file path does —
the streaming bootstrap is weaker on real fading hand-sent audio. That is a separate
problem from tone selection and is the "real corpus" gap already recorded in §7.

---

## D-14 — The spectrum strip is the range control; settings holds only the outer bounds

Two levels, because they answer different questions on different timescales.

**Graph bounds** (persisted, settings screen) are a fact about the user's equipment: what
frequency their sidetone sits on and what their recording chain does. Set once, changed
rarely.

**The search range** (session-only, dragged on the strip) is a reaction to what is
happening right now: a 400 Hz artifact keeps winning the lock and the real signal is at
1000 Hz. The user is watching it happen and wants it fixed immediately.

Putting the second on the strip rather than in settings follows from that. It is reactive,
so it must be one gesture away from where the user is already looking; and the strip
already shows the artifact's position, so making them convert it into a number would be
work the interface should be doing.

### Excluded spectrum is dimmed, not hidden

The load-bearing detail. The user excludes a region precisely because something in it was
winning; they need to keep seeing that peak to understand why it stopped. Hiding it makes
the strip lie about what is in the room, and makes the narrowing look like it did nothing.

For the same reason the axis belongs to the *graph*, not the range: bars stay put while
only the shading moves. An axis rescaling under a dragging finger reads as the control
fighting back.

### The narrowing is deliberately not persisted

A range narrowed to chase one stubborn signal, silently still narrow tomorrow, is a
decoder that mysteriously cannot hear things — and the user has no reason to connect
today's silence to yesterday's drag. Graph bounds persist because they describe equipment;
the narrowing does not because it describes a moment.

Mitigation for the session it *does* last: `⟲` is visible beside the readout whenever the
range is non-default. The undo gesture (double-tap) is invisible, so the state must not be.

### Why not the alternatives

| Rejected | Reason |
|---|---|
| Stepper pairs under the strip | Four more controls in the densest part of the screen, and it reintroduces the number-to-position translation the strip removes. |
| A range slider below the strip | Duplicates the strip's own axis 30 dp lower. The strip *is* the axis. |
| Tap a peak to lock it | Useful, and worth having as well — but one-shot. It does not stop the engine re-acquiring the same artifact a minute later, which is the actual complaint. |
| Settings only | Fails the use case, which is reactive. |

Minimum span is 100 Hz: a range dragged shut leaves the decoder searching nothing.

---

## D-15 — The analysis window follows the speed (FR-2.7, finally)

FR-2.7 always said the window "adapts once a speed estimate exists". It never did:
`windowMs` was a fixed 10 ms from the first commit. fldigi ships a matched filter whose
width tracks the current WPM, so the idea is well precedented (`prior-art.md`).

The physics: a Goertzel over N samples is roughly `sampleRate/N` wide. At 16 kHz, 10 ms is
a **100 Hz filter**. That is correct at 40 WPM — a dot is 30 ms and the window must stay
short enough to resolve one — and at 7 WPM, where a dot is 171 ms, it is 17× wider than
necessary and admits 100 Hz of noise to measure a 171 ms element.

The window is now a quarter of a dot, clamped to 10–40 ms, rebuilt when the target moves
more than 25 % (hysteresis: a rebuild perturbs the envelope distribution the percentile
floor is built from, and there is no value chasing a Kalman estimate still settling).

### What it actually bought

Measured across speed × SNR, three seeds each, adaptive versus fixed:

| WPM | +3 dB | 0 dB | −3 dB | −6 dB |
|---|---|---|---|---|
| 7 | — | — | — | **−24.6** |
| 10 | — | — | −2.9 | **−27.0** |
| 15 | — | — | — | −2.4 |
| 20 | — | — | — | −5.8 |
| 30 | — | — | — | — |

(CER percentage points; negative is better, — is identical.)

**Nothing above −3 dB, where the decoder already works. Real gains at the cliff**, the
largest being 10 WPM at −6 dB going from 38.4 % to 11.4 % — unusable to usable. Nothing at
30 WPM, correctly: the target window there is already at the 10 ms floor, so there is
nothing to widen.

That shape is the honest summary: this does not make good conditions better, it moves the
edge of the envelope. Worth having in an app pointed at a room by a phone microphone,
where marginal SNR is the normal case rather than the exception.

### Two things it did not do

**It does not help D-10**, contrary to what `prior-art.md` originally claimed. The tone
lock comes from the 4096-point FFT peak search; the Goertzel window has no bearing on it.
A wide window *masks* an off-frequency lock rather than causing one, so narrowing it makes
D-10 more consequential. Measured: the D-10 known-failure test fails by the same margin.

**It is not implemented on the file path.** That path is two-pass and fits its parameters
over the whole recording, reaching the same end another way — it already decodes the −5 dB
bench scenario at 0 % CER, so there is no headroom to recover. Adding a second envelope
pass there would be complexity without a measurable payer.

### One prerequisite worth noting

`Goertzel.power` is now normalised by window length, returning mean power rather than total
energy. Without that, changing N mid-stream would jump the scale by (N₂/N₁)² and invalidate
the rolling percentile floor the keying threshold rides on. Everything downstream works in
ratios and is unaffected; only the absolute "is this silence" guards shift, and they sit
far below any real signal either way.
