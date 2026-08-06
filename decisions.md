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

---

## D-16 — Suppress output while the speed estimate is clamped, and only then

The plan said "treat out-of-band speed as a suppression condition". Measurement says that
is not implementable, and the narrower rule that survives is worth more than it sounds.

### Why the obvious version fails

The speed *value* carries no information about correctness in the region that matters:

| | reported speed | result |
|---|---|---|
| synthetic 50 WPM | 53.6 WPM | **0 % CER** — perfect |
| RTTY | 52.9 WPM | garbage, 177 characters |
| rough 13 WPM fist | 16.3 WPM — inside the band | 57 % CER |

Same speed, opposite verdicts. A threshold anywhere near 40–55 WPM would gag genuine
contest operators (D-1's own table lists QRQ at 40–60+ WPM) while still passing RTTY, and
it would miss the rough-fist case entirely because that one reads *inside* the band.

FR-5.7 also asks explicitly for best-effort decoding outside 5–40 with a flag, so the
literal rule would have contradicted the spec.

A second idea died on the way: comparing the pass-1 bootstrap unit against the pass-2
Kalman unit, on the theory that agreement is evidence. It is not — the bootstrap is 4×
off even on decodes that come out at 0 % CER, by design (`MorseDecoder` notes it is "only
good to a factor of ~2"). Clean decodes disagree by 4.1–4.3×; the rough fist disagrees by
1.22×. The signal is *anti*-correlated with quality.

### What survives

Suppress while the unit is pinned at the [KalmanUnit] clamp. D-11 already established that
a clamped estimate is a bound being enforced rather than a measurement, and refuses to
*display* it; this extends the same reasoning to the characters computed from it. If the
number is not a measurement, neither is anything derived from it.

This is narrower than "outside the calibrated band" on purpose. 5–40 is where we stop
promising accuracy; the clamp is where the estimator stops measuring. Only the second
implies the output is meaningless.

### Measured

Live path, 99 characters sent:

| | before | after |
|---|---|---|
| +10 dB | 98 chars, 1.6 % | 98 chars, 1.6 % |
| 0 dB | 98 chars, 1.6 % | 98 chars, 1.6 % |
| −5 dB | 167 chars, 108 % | **80 chars, 38 %** |
| rough 13 WPM fist | 274 chars, 308 % | 194 chars, 207 % |

Nothing at all in conditions the decoder handles; a large improvement where it does not.
Bench mean 29.0 % → 17.9 %. Closes D-7 and improves D-6 without closing it.

A genuine 45 WPM sender is unaffected — decoded, flagged out of range, not suppressed —
which is the FR-5.7 behaviour the broad version would have destroyed.

---

## D-17 — A bootstrap seed claiming an impossible ratio is discarded

The live path fixes its timebase once, from the first eight marks, by 2-means over the log
durations: lower cluster is dots, upper is dashes, and `u0` — the unit everything else is
measured in — is the lower cluster mean. `BootstrapDiagnostic` measured what that produced
on `hand-13-rough`, and the failure is not the one D-6 guessed at.

Seven of the eight marks were segmented near-perfectly (≥ 0.98 coverage against the
generator's own element list). The eighth was a **25 ms fragment of a 130 ms dash**, 0.19
coverage — noise had split the dash and one piece survived. 2-means put that fragment in
the lower cluster *by itself* and all seven genuine marks, dots and dashes together, in the
upper. `u0` became 25 ms against a true 92.3: **48 WPM believed, 13 WPM sent**, after which
every dash read as a run of dots.

Nothing else was at fault. Segmentation was good (210 mark runs against 214 true marks).
The algorithm was good — fed the true durations of those same eight elements it returns
105.8 ms and 2.08:1, near-correct. One outlier, and no defence against it.

### Why not reject on cluster size

The obvious guard is "a cluster with one member is not a cluster". It is wrong. A singleton
lower cluster is **normal** on a dash-heavy sample: seven dashes and one dot is a
legitimate opening and seeds correctly at ~3:1. Rejecting on membership would discard good
bootstraps for the same shape that makes bad ones.

What distinguishes this case is the *distance*, not the count. The split claims **5.55:1**.
D-3 puts real fists at 2:1–2.5:1 against a 3:1 prior, and `initFromBootstrap` already
refuses splits below 1.8:1 as "no two clusters here". This is that judgement at the other
end: above `bootstrapMaxRatio` (4.5:1) the split is not describing dots and dashes.

Rejection reuses the existing discard path — clear and keep listening. It costs one further
bootstrap attempt, because the marks keep arriving.

### Measured

`HistoryBench`, the same probe every row of `version-history.md` was produced with. **One
of 42 rows changed:**

| | before | after |
|---|---|---|
| `hand-13-rough`, live | 194 chars, **207.1 %** | 99 chars, **65.9 %** |
| Live mean, 16 scenarios | 21.9 % | **13.1 %** |
| Live worst case | 207.1 % | **65.9 %** |
| File mean | 15.6 % | 15.6 % (unchanged) |

Every other Morse scenario and all five non-Morse fixtures are byte-identical. The
scenario emits 99 characters against 99 sent — the count is now right — and reports 21.5
WPM against a true 13, so the timebase is out by 1.65× rather than 4×.

This is the first change to improve the worst case rather than the mean. It also puts the
scenario below its first-commit value (69 %) for the first time; D-13's regression is
recovered.

### Two consequences worth stating

**A vacuity guard in `LiveEngineTest` had to be re-pointed.** Ambient noise clusters at
ratios no fist produces, so those bootstraps are now discarded before a unit exists to
clamp, and the 60 WPM ceiling is no longer reached on that fixture. The guard now measures
reachability with `bootstrapMaxRatio` disabled; asserting it on the default config would
only assert that this record had been reverted. Noise still decodes to nothing.

**D-1's known-failure test now passes, and D-1 is not fixed.** It passes because the 4×
error no longer occurs on the only fixture that produced one, not because anything
detected it. The annotation is removed so the test guards this change, and D-1 stays open
with no synthetic reproducer — see `known-issues.md`.

---

## D-18 — The file path's honest-failure gate claims only what it can prove

Step 1b of the plan was to port D-17's seed guard to `MorseDecoder.twoMeansSeed`, on the
stated grounds that the file path "has the same unguarded 2-means" and therefore fails on
`hand-13-rough` for the same reason the live path did. Measured with the new
`FileGateDiagnostic`, that premise is wrong on every count, and the real cause is a
different guard entirely.

### Step 1b was a no-op, and the reason is structural

The file path's 2-means is not exposed to D-17's failure. D-17's outlier could dominate
because the live seed clusters the **first eight marks**, so one 25 ms fragment can form a
cluster by itself. `decodeFile` clusters **every mark in the recording** — 214 of them on
this fixture — where a single fragment cannot move a centroid appreciably. The split lands
at 2.19:1 with `u0` = 89.2 ms against a true 92.3, an error of **1.04×**.

Applying `bootstrapMaxRatio` = 4.5 to the file seed changes **nothing on any fixture**,
Morse or non-Morse. The only fixtures whose seed exceeds 4.5:1 are `music` (11.16) and
`music+noise` (11.26), and rejecting their seed in favour of the weak 3:1 fallback lands
EM in the same place: both still score `fitQuality` 0.0000 and are still declined. The
diagnostic keeps that table so the port is not proposed a third time.

### What actually declined it

`fitQuality < 0.12`. The fit scored **0.0987** — and this is not a seeding problem, because
fed the true unit it still scores 0.1071. No improvement to the seed could have rescued it.

The threshold was picking out the one genuine transmission in the corpus. Ranked by
`fitQuality`, a rough but real fist sits **below every impostor the gate lets through**:

| | fitQuality | outcome under the 0.12 gate |
|---|---|---|
| `hand-13-rough` — a real 13 WPM fist | **0.0987** | **declined** |
| `speech` | 0.4741 | decoded, 31 chars |
| `knocking` | 0.4033 | decoded, 30 chars |
| `clipping` | 0.7386 | decoded, 19 chars |
| `rtty` | 0.5228 | stopped by `atSpeedCeiling`, not by this |
| `music`, `music+noise` | 0.0000 | declined |
| `speech+hum` | 0.0000 | declined |

This is `known-issues` D-8 restated from the file side: the two populations are not
separated by this score, so no cut point works. The ordering is wrong, not the number.

### The evidence that retired the threshold

The same fist, the same seed, the same impairments, at three transmission lengths:

| text | marks | `u0` | `fitQuality` | under the 0.12 gate |
|---|---|---|---|---|
| 40 chars | 103 | 88.2 ms | 0.3258 | decoded |
| 79 chars | 214 | 89.2 ms | **0.0987** | **declined** |
| 126 chars | 329 | 86.8 ms | 0.3312 | decoded |

`u0` holds within 4 % of truth throughout while the score swings by 3.3× and crosses the
veto in the middle — not monotonically, so this is not "more data, better fit". At these
levels `fitQuality` is sampling noise. Vetoing a whole transmission on it made the outcome
a coin flip on identical signal quality, which is also why `version-history.md` and
`DecoderBench` disagreed about whether this scenario decodes at all: the two use the same
impairments over different text lengths, and landed on opposite sides of the threshold.

### The decision

The gate now declines only a **structurally impossible** fit — `fitQuality <= 0`. Every
correct rejection in the corpus is a hard zero, not a small number: `music` and
`music+noise` fail on ratio (7.0–7.3:1, past the score's 5.5 ceiling), `speech+hum` on
separation (1.29, under its 1.5 floor). Those are the claims this score can support.

This is deliberately **not** a re-tuned threshold. Fitting a number to these fixtures is
the trap CLAUDE.md names, and the length table above is what a fitted number looks like
when it fails. The change is to stop the score vetoing where it carries no information; it
still reaches the UI as `modelFitQuality`, which is where a weak-but-possible fit belongs —
annotate, do not withhold.

### Measured

`DecoderBench`, before and after. **One scenario changed, in all four file variants:**

| | before | after |
|---|---|---|
| `hand-13-rough`, file | 0 chars, **100 %** | 79 chars, **64.6 %** at 17.9 WPM |
| File mean, 16 scenarios | 13.8 % | **11.6 %** |
| Live path, every column | — | byte-identical |

Every other row is byte-identical, non-Morse fixtures included. `HistoryBench` is unchanged
on all 42 rows, because its `hand-13-rough` uses the 126-char text and was on the decoding
side of the threshold already — the disagreement above, seen from the other end.

The full suite stays green with the same nine known failures. `known-issues` D-6 stays
open: 64.6 % is two thirds wrong, and the live path is untouched at 89.9 %.

### What this did not fix

`qsb-impulse` still emits nothing from a file, and the diagnostic shows it is a different
guard: pass 1 fits it perfectly (`fitQuality` 1.0000, `u0` 61.8 ms against a true 66.7) and
`atSpeedCeiling` discards the result at the end of pass 2. That guard is **right** here —
ungated the scenario emits 121 characters against 62 sent at 168.4 % CER, exactly the
confident verbosity D-16 exists to stop. Recorded so it is not mistaken for the same defect.

**Superseded by D-19.** Once pass 2 stops re-opening the locked unit, the runaway that
tripped `atSpeedCeiling` no longer happens: `qsb-impulse` reports a sane 19.2 WPM and
decodes at 45.6 % rather than declining. The ungated 168 % verbosity above was a *symptom*
of the tracker running away on the impulses, not an argument for the veto.

---

## D-19 — On the file path, pass 2 decodes with the unit locked; the Kalman only reports

FM-1 says file mode fits its parameters over the whole recording and then decodes with them
**locked**. It did not: pass 2 ran a Kalman that re-opened the unit character by character,
and on a rough fist that tracker followed `hand-13-rough`'s 12 % per-character bias.
`ResidualDiagnostic` (step 6a) measured the cost at **27.9 CER points** — freezing the
tracker took the file decode 64.6 % → 36.7 % — with **zero** cost on `drift-ramp` and
`wobble`, the two scenarios that exist to exercise drift tracking. Pass 1's global fit plus
the lattice's per-character shrinkage (FR-5.6) already absorb the drift the tracker was for.

So the decode should not consume the tracker. But the tracker cannot simply be removed:
FM-2 requires a reported per-segment speed track, and **AC-6** asserts it stays within 10 %
of the sender through a 15 → 25 WPM ramp. The job was to separate the two — decode against
the locked unit, report a track alongside without feeding it back. Getting that right took
two measurements that refuted the obvious approaches.

### The plan's proposed track source fails AC-6, and so does every other internal signal

N-5 proposed reporting the track from `DecodedChar.unitMsLocal`, the per-character shrunk
least-squares fit. Measured with `Ac6TrackDiagnostic`, with the decode unit frozen:

| report source | worst dev vs the AC-6 ramp | AC-6 |
|---|---|---|
| `unitMsLocal` | 42.2 % | fail |
| `unitMsRaw` (unshrunk LS) | 46.1 % | fail |
| a dedicated reporting Kalman over `unitMsRaw` | 38.4 % | fail |

Nothing derived from the decoder's own per-character measurements holds the ramp. The cause
is structural: with the decode frame frozen, the **online mark/gap model keeps adapting**,
and a drift-free frame plus a drifting model means the sender's drift is absorbed into
`markModel.means[0]`. Every local unit is measured *against that model*, so they all read
back toward the frozen unit — the track goes flat. `unitMsLocal` is shrunk toward the frozen
unit on top of that, which is why it is worst.

### The fix: freeze the unit, and measure the report against the frozen pass-1 centres

The reported track is measured against the mark-cluster centres **snapshotted at the end of
pass 1**, before any online adaptation (`RobustTimingDecoder.refMean0/refMean1`). That raw
fit is immune to the model's drift absorption, and fed through the Kalman — which now tracks
purely to report — it holds the ramp:

| report source (frozen decode) | worst dev | AC-6 |
|---|---|---|
| Kalman over the reference-frame fit (**shipped**) | **8.0 %** | **pass** |

`FileResult.wpmTrack` already reads `1200 / unitMsGlobal`, i.e. this Kalman, so no call site
changed. Freezing the **models** as well as the unit was tried and rejected: it also cleans
the track (Kalman to 8.0 %) but it regressed a 20 %-jitter fist that was *benefiting* from
online adaptation (AC-4's `hand-18` lexicon decode 5.6 % → 7.9 %), for no decode gain on
`hand-13-rough`. So the models keep adapting for the decode; only the *report* is measured
against the frozen reference.

### Raw mode only — measured, not assumed

The freeze is applied when no lexicon prior is active (`freezeDecodeUnit = prior == null`).
It improves the raw decode across the board, but on `hand-18` at the AC-4 text length it
moves the residual errors to ones the lexicon cannot flip: the tracking decode + prior
reaches 5.6 % where the frozen one stalls at 7.9 %, which would fail **AC-4** (< 6 %). The
prior is an adaptive layer (FR-7.1); when it is engaged, let pass 2 track. The default ships
raw (P-1/P-3), so the default gets the freeze.

This is also a live illustration of the N-4 lesson. `ResidualDiagnostic` read the freeze as
free on `hand-18` — on a 79-char text. On the AC-4 128-char text the raw decode *improves*
(8.7 % → 7.9 %) while the assisted decode regresses, and on the `DecoderBench` text the raw
decode regresses (8.9 % → 11.4 %). One scenario, three text lengths, three verdicts. Quote
the instrument and the length with the number.

### Measured

`DecoderBench`, file path, before and after (live path byte-identical, every column):

| scenario | before | after |
|---|---|---|
| `hand-13-rough` | 64.6 % | **36.7 %** |
| `qsb-impulse` | declines (`atSpeedCeiling`) | **45.6 %** at a sane 19.2 WPM |
| `hand-18` | 8.9 % | 11.4 % |
| **file mean, 16 scenarios** | **11.6 %** | **6.6 %** |

`qsb-impulse` is the second win and it was not planned. On the shipped path the tracker ran
away to the 60 WPM ceiling on the static crashes — a D-1 scale error — and `atSpeedCeiling`
declined the whole transmission (D-18 recorded this as the veto being *right*). With the
unit locked at pass 1's sane `u0`, the runaway cannot happen: the decoder reports 19.2 WPM
against a true 18, `outOfCalibratedRange` false, and decodes a real transmission poorly
rather than declining it. The same root cause as the `hand-13-rough` win — pass 2 taking the
unit somewhere pass 1 had not — expressed as a runaway rather than a bias chase.

### What this did not do

`known-issues` D-6 stays open: 36.7 % is a third right, still not a decode, and the live path
is untouched at 89.9 %. `hand-18` costs 2.5 raw points on the short text — the price of the
freeze, accepted against a 5-point mean improvement and a halved worst case. The live path is
deliberately excluded: there the Kalman is the only unit estimate after bootstrap, and
`freezeDecodeUnit` defaults off.
