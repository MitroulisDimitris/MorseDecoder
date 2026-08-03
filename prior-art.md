# Prior art — how others decode Morse, and what we should take

Research note, July 2026. Prompted by the question "are we missing something obvious?"
before committing to fixes for the ten open defects in `known-issues.md`.

Short answer: **no obvious trick is being withheld from us.** The hard problems we have
are the hard problems the field has, and on two of them we are already ahead. But there
are four concrete borrowings worth making, and one requirement of our own we never
implemented that others clearly did.

---

## The landscape

| | What it is | Approach |
|---|---|---|
| **CW Skimmer** (VE3NEA) | The acknowledged state of the art. Decodes up to 700 simultaneous signals across a passband | Bayesian statistics throughout; author spent 8 years and "hundreds of algorithms" |
| **Morse Expert** (VE3NEA) | Single-signal phone/desktop app — our closest analogue | Same algorithms as CW Skimmer. 12–45 WPM auto, auto pitch |
| **fldigi** | The open-source ham workhorse | Matched filter (FFT convolution) + AGC + adaptive threshold + Self-Organising Map character classifier |
| **MorseCode.World** (Stephen Phillips) | Browser decoder, the page you sent | Web Audio FFT → volume threshold → timing. Expert version: manual everything. Adaptive version: picks the loudest frequency |
| **AG1LE** (Mauri Niininen) | Prolific open-source experimenter, ~15 years of published work | Bayesian decoder in C (Kalman keystate + probabilistic path search); later CNN-LSTM-CTC neural models |
| **Academic ML** | MorseNet, various CRNN/CTC models | Spectrogram → CNN+RNN → CTC. Trained on synthetic corpora |

---

## What nobody has solved

**Non-Morse rejection — our D-8 — is unsolved across the board.** This is the most
important finding in this note, because it reframes D-8 from "we are behind" to "we are
attempting something the field does not attempt."

- **Morse Expert**, from the author of the best decoder in existence, displays *no*
  confidence metric and *no* failure indication. The only quantitative feedback is an
  input level meter in dBFS.
- **fldigi** has squelch and AGC — level-based gating, which cannot distinguish a loud
  teleprinter from a loud CW signal.
- **AG1LE's Bayesian decoder** has squelch to suppress noise-triggered decoding, and its
  author states plainly that "the noise estimator does not seem to be able to keep track
  of the real noise level."
- **MorseCode.World** leaves the threshold to the user entirely.

The reason is structural: these are all tools for operators who are *already tuned to a
CW signal* and know it is there. Rejecting non-Morse is not their problem. It is ours,
because we point a phone microphone at a room. **P-4 is a genuinely unusual design stance,
not a box we forgot to tick.**

Corollary: we should stop expecting to find a standard technique for D-8. If we want it,
we are building it.

---

## Where we are already ahead

**Tone selection.** MorseCode.World's adaptive decoder "selects the loudest frequency."
That is precisely the power-based selection we measured failing and replaced in D-13 —
on the field recording, the loudest in-band peak was room noise at 363 Hz while the CW sat
at 1882 Hz. Our keying-spread scoring is a real improvement over the obvious approach, and
it is worth knowing that the obvious approach is what a well-regarded tool ships.

**Time resolution.** Their FFT-256 gives 5.3 ms resolution and they note the consequence
honestly: "even when a *perfect* downloaded signal is analysed, the measured variation in
the durations is quite significant… because, relative to the speed of the Morse code, the
measurements are quite infrequent." Our 2.5 ms hop halves that quantisation noise.

**Automatic speed.** Their expert version requires the user to type the WPM. Ours has never
required it. Their newer version adapts, so this is converging, but our lattice + Kalman
approach is a stronger form of it than "fit the speed to the data."

---

## Four things worth taking

### 1. Stop making hard decisions early (helps D-1, D-6)

Shovkoplyas' stated principle, from 8 years on the problem:

> approach every problem in the Bayesian framework, express all prior knowledge in the
> form of probabilities, and use observed data to update those probabilities, **rather
> than making hard decisions at every input sample.**

We violate this in one specific, identifiable place: the **Schmitt trigger** binarises the
envelope into key-up/key-down before anything downstream sees it (`FrontEnd.process`,
`LiveDecoderEngine.processFrame`). Every later stage — mixtures, Kalman, Viterbi — inherits
whatever that threshold decided, with no way to revisit it.

**The example this argument was built on has since been withdrawn.** It was: on a rough
fist at +5 dB a marginal threshold decision becomes an element-boundary error becomes a 4×
unit error, which D-1 means nothing downstream can notice. Measurement (`decisions.md`
D-17) found the chain was shorter than that. The threshold *did* fragment one dash, but the
4× error came from the bootstrap seed accepting that single fragment as its entire dot
cluster — a bounds check, not an architecture. With the check in place the same audio
decodes at 65.9 % instead of 207 %, and the fragmentation the front end produces is
otherwise benign (210 mark runs against 214 true marks).

The principle stands and the citation does not. Before this becomes the largest change in
the project, it needs a failure that survives contact with the cheap fixes — measure where
the remaining 65.9 % accumulates first.

Our lattice is already Bayesian in shape; the front end is not. Feeding *soft* key-state
probabilities into the lattice instead of hard runs is the single most architecturally
significant idea in this note. It is also the largest change, and it would touch
`RobustTimingDecoder` — see "what I would not do yet" below.

AG1LE's decoder does exactly this: a Kalman filter estimates *keystate likelihood* rather
than keystate, and a probabilistic path search consumes the likelihood.

### 2. Enforce the speed bound inside the decoder, not just on the display (fixes D-1's symptom)

Others impose hard speed limits as a decoding constraint:

- Morse Expert: **12–45 WPM**
- AG1LE's decoder: configurable, tested at **8–80 WPM**

We have a 5–40 calibrated band and a 60 WPM clamp on `KalmanUnit`, but D-4 means the
out-of-range *flag* is computed from a different variable than the *displayed* speed, so
the constraint never surfaces. Our worst failure, `hand-13-rough`, reported **53 WPM for a
13 WPM signal** — a value every tool listed above would reject outright as outside its
operating range.

**Half done, and the other half was measured to be wrong.** D-4 is fixed. Treating "speed
outside the band" as a suppression condition is not implementable: speed carries no
information about correctness in the region that matters — synthetic 50 WPM decodes at 0 %
CER while RTTY at 52.9 WPM is garbage, and this very scenario read 16.3 WPM, *inside* the
band. What shipped instead is narrower and survives: suppress only while the estimate is
pinned at the physical clamp (`decisions.md` D-16). The scenario now reports 21.5 WPM,
so the symptom this idea aimed at is gone — removed by D-17 rather than by a speed rule.

### 3. Implement FR-2.7's speed-adaptive window — which we never did · DONE (D-15)

fldigi provides a **matched filter whose width is optimised for the current WPM**, and
AG1LE reports it gives "virtually error free" decoding at S5–S9.

Our own requirements already say this. FR-2.7:

> Analysis window nominally 10 ms with 50–75 % overlap, hop 2–5 ms; **window length adapts
> once a speed estimate exists.**

`LiveDecoderEngine` uses a fixed `windowMs = 10.0` regardless of speed. The adaptation was
specified and never built. Consequences we have already measured:

- A 10 ms Goertzel window is roughly **100 Hz wide**, which is why D-10's 76 Hz lock error
  still decodes — and why the lock is far sloppier than the readout implies.
- At 7 WPM (dot = 171 ms) we are using a filter 17× wider than necessary, throwing away
  SNR for nothing.

Implemented and measured — see decisions.md D-15. It buys nothing above −3 dB and up to
27 CER points at −6 dB.

**One claim here was wrong.** I wrote that this would help D-10. It does not: the tone lock
comes from the 4096-point FFT peak search, which the Goertzel window has no bearing on.
A wide window *masks* a sloppy lock rather than causing it, so narrowing it makes D-10
more consequential, not less. Measured: the D-10 known-failure test still fails by the same
margin, and the off-frequency scenarios in the bench are unchanged.

### 4. Show the duration histogram (helps D-8 diagnosis, cheap)

MorseCode.World plots the distribution of measured on/off durations and tells the user:

> If the chart shows a clean clustering of timings then it's likely that the decoder will
> have made sense of the timings.

They surface the modality to a human instead of acting on it. That is honest and cheap,
and it is the same signal I proposed for D-8. Our `fit p10/p50/p90` debug strip is a
weaker version of the same idea — a histogram would be strictly more informative and would
have made the RTTY and scale-error failures visible at a glance.

---

## The D-8 idea this research produced — tested, and half of it was wrong

The reasoning was: RTTY defeats our fit metric because runs of *k* identical bits produce
marks of *k* × 22 ms, so a two-cluster fit lands near 3:1. But Morse marks are **only** 1
or 3 units, while RTTY fills 1, 2, 3, 4, 5 … — so Morse alone has a *forbidden zone at 2
units*, and testing occupancy there should be discriminative in a way `fitQuality`
structurally cannot be.

**Measured, and refuted.** `decisions.md` D-3 already said why: real fists run 2:1 to
2.5:1, so legitimate dashes sit squarely in the supposedly forbidden zone. Off-lattice
occupancy scores **0.19 for speech against 0.28–0.36 for a rough hand-sent fist** — the
non-Morse case looks *more* Morse-like than the Morse case. The idea was wrong for a
reason already written down in our own decision log.

The investigation did produce something better, by asking what Morse has that nothing else
does: **marks and gaps share one unit.** Fit the dot from marks only (D-2), then measure
how far the gaps land from the 1/3/7-unit lattice. Nothing in the decoder currently checks
the two against each other.

That works — partially — and the numbers are in known-issues D-8. The finding worth
carrying here is methodological: **tuned on synthetic fixtures alone, the threshold
inverts.** It rejects the real recording and accepts RTTY. One real clip changed the
conclusion completely, which is the strongest argument for G-1 this project has produced.

## Corpus (G-1)

AG1LE published an open-source synthetic Morse dataset generator with an accompanying
paper ([arXiv 1807.04239](https://arxiv.org/pdf/1807.04239)), and trained a CNN-LSTM-CTC
model on 5.2 hours / 5000 generated files. Our `SyntheticMorseGenerator` is comparable in
capability; the difference is that they generate for *training* and we generate for
*testing*.

This does not solve G-1, which is specifically about **real** recordings. MorseCode.World
hosts real samples — including one recorded through a microphone by a human sender, and an
"Inspector Morse" TV clip their own decoder cannot manage — but licensing is unstated, so
they are a reference point rather than a corpus we can adopt.

Worth noting they deliberately publish a sample their decoder fails on. That is the same
instinct as our `@KnownFailure` tests.

---

## What I would not do yet

**Neural / CTC decoding.** MorseNet and the CRNN-CTC family work, and would likely beat our
timing decoder on rough fists. But: they need a training corpus we do not have (G-1), they
put a model of unknown size in an offline app, and they would replace the entire decode
path — including everything `decisions.md` documents about *why* it is shaped as it is. If
we ever have real recordings in quantity, revisit. Not before.

**Full soft-decision front end.** Idea 1 above is the deepest fix, and it is a rewrite of
the boundary between `FrontEnd` and `RobustTimingDecoder`. Worth doing eventually; not
worth doing before the cheap wins, and not worth doing without the bench to prove it helps.

---

## Recommended order, revised by this research

1. ~~**D-4** — make the speed bound real rather than cosmetic.~~ **Done.** The flag now
   derives from the reported speed. Treating out-of-band speed as a *suppression*
   condition (idea 2) is still open and remains the cheapest fix for our worst symptom.
2. ~~**D-5** — render `—` for unknown fit.~~ **Done.** `fitQuality` is nullable.
3. ~~**Test the 2-unit occupancy idea.**~~ **Done, and it failed** — see the section above.
   The replacement (gap-lattice consistency) works on 6 of 7 non-Morse fixtures with all
   Morse accepted, but at 1.05–1.13× margins resting on a single real recording. Not
   shippable until G-1 is. `StructureBench` reproduces the measurement.
4. **FR-2.7 speed-adaptive window** (idea 3) — spec-mandated, well-precedented, improves
   weak-signal and D-10 together. **Now the top open item.**
5. **D-9** — the tone-jump deadlock. Unrelated to any of the above; still the most
   user-visible bug.
6. Soft-decision front end, only if 4–5 leave D-6 unfixed.

The order has also shifted for a reason worth stating: step 3 concluded that **D-8 is
gated on G-1, not on cleverness.** More real recordings would unblock it; more thinking
will not.

---

## Sources

- [CW Skimmer](https://en.wikipedia.org/wiki/CW_Skimmer) — Bayesian multi-signal decoder, VE3NEA
- [Morse Expert](https://ve3nea.github.io/MorseExpert/) — same algorithms, single signal, 12–45 WPM
- [AG1LE: Towards Bayesian Morse Decoder](http://ag1le.blogspot.com/2013/01/towards-bayesian-morse-decoder.html)
- [AG1LE: New Morse Decoder Part 2](http://ag1le.blogspot.com/2013/12/new-morse-decoder-part-2.html) — Kalman keystate, probabilistic paths, instantaneous speed
- [AG1LE: Adding matched filter to fldigi CW](http://ag1le.blogspot.com/2012/05/fldigi-adding-matched-filter-feature-to.html)
- [fldigi CW configuration](http://www.w1hkj.com/FldigiHelp/cw_configuration_page.html) — matched filter, adaptive threshold, SOM decoder
- [MorseCode.World adaptive decoder](https://morsecode.world/international/decoder/audio-decoder-adaptive.html) — "selects the loudest frequency"
- [Morse Code Datasets for Machine Learning](https://arxiv.org/pdf/1807.04239) — open-source synthetic generator
- [MorseNet: A Unified Neural Network for Morse Detection and Recognition in Spectrogram](https://www.researchgate.net/publication/344064119_MorseNet_A_Unified_Neural_Network_for_Morse_Detection_and_Recognition_in_Spectrogram)
