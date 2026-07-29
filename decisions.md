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
