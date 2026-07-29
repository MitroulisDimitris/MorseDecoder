# Processing pipeline

```
  Audio in            mic or file, 16 kHz mono
      |
      v
  Tone lock           Goertzel at tracked frequency         [frame rate]
      |
      v
  Envelope + keying   noise floor, Schmitt trigger          [frame rate]
      |
      v
  Element stream      durations, gating, bridging           [element rate]
      |
      v
  Unit model          mixtures, Kalman tracking             [element rate]  --+
      |                                                                       | feedback
      v                                                                       |
  Lattice decode      segment hypotheses, Viterbi           [character rate]  |
      |                                                                       |
      v                                                                       |
  Transcript          append with confidence                                  |
                                                                              |
  (unit estimate reparameterises envelope + keying and framing) <-------------+
```

## Three clocks

The single most important structural fact: this pipeline runs on three different rates,
and conflating them is the usual source of bugs.

| Clock | Rate | Stages |
|---|---|---|
| Frame | ~200–500 Hz | Goertzel, envelope, noise floor, keying decision |
| Element | ~5–50 Hz | Run-length encoding, duration gating, unit model updates |
| Character | ~1–10 Hz | Segmentation hypotheses, Viterbi, output |

## Frame rate — audio to binary key state

Samples arrive at 16 kHz into a ring buffer, sliced into overlapping ~10 ms windows with
a 2–5 ms hop. Everything downstream ticks at 200–500 Hz.

**Tone lock** runs far slower than the frames themselves — roughly every 100 ms. Rolling
FFT over 300–1200 Hz, peak pick, mains-harmonic veto, slow EMA. Once `f0` is known, each
frame gets one Goertzel evaluation at that frequency: a single number, no full FFT.

A short median filter across frames precedes everything else. Static crashes and key
clicks are one or two frames wide; real elements are dozens.

**Envelope and keying** maintains a rolling ~20th percentile of the energy as the noise
floor. Two thresholds sit above it (e.g. floor + 9 dB on, floor + 6 dB off); the
hysteresis is what stops the output chattering at marginal SNR. Output is one bit per
frame. SNR is computed here and drives the low-signal state (FR-3.7).

## Element rate — key states to durations

Run-length encode the bit stream: consecutive identical frames collapse into
`(mark, 84 ms)`, `(gap, 31 ms)`. Two filters apply immediately — runs shorter than
0.3 units dropped, mark dropouts shorter than 0.5 units bridged.

Both gates are expressed in *units*, which aren't known yet. That circularity is why the
bootstrap is a separate path: envelope autocorrelation over the first few seconds finds
the dominant repeating timescale without needing classification. Crude, but enough to set
the gates and window lengths before the real model takes over.

**Unit model** consumes the duration stream. Mark durations feed a Gaussian mixture over
log durations, converging on dot and dash centroids fitted independently. Gaps get a
separate mixture — this is what makes Farnsworth work. A Kalman filter tracks the dot
unit; its variance is the lock-quality readout, and the overall fit becomes
`modelFitQuality` (FR-5.8).

## Character rate — durations to text

**Lattice decode** deliberately refuses to be greedy. Every ambiguous gap spawns
branches rather than committing to a segmentation. For each candidate character group, a
*local* unit is fitted from that group's marks and shrunk toward the Kalman estimate —
this is the drift mechanism, and why a momentarily fast character still decodes.

Each branch scores its marks by distance to centroid, walks the Morse trie (prosigns as
first-class entries), and yields candidates with confidences. Viterbi picks the best path.

**Transcript** appends committed characters with confidences. Nothing is ever rewritten.

## The two feedback loops

1. **Unit estimate → frame-rate stages.** Analysis window length, minimum-duration
   gates, dropout bridge width, and the noise-floor percentile window are all expressed
   in units, so they retune as the speed estimate settles and drifts.
2. **Frequency lock hysteresis.** The current lock influences whether a new peak is
   accepted, which is what stops QRM dragging the tracker onto a competing signal.

## Latency

A character cannot be emitted until the following gap is known to be inter-character,
which means waiting ~3 units, plus a Viterbi commit horizon of 2–3 characters held
uncommitted so later evidence can still revise the path.

At 20 WPM a unit is 60 ms → ~300 ms total. At 5 WPM a unit is 240 ms and the
inter-character wait alone is 720 ms. Hence NFR-2 is stated as `≈ 3 units + 120 ms`
rather than a flat figure.

## Tone lock failure modes

| Failure | Downstream symptom | Detectable |
|---|---|---|
| Locked to noise / empty band | Envelope ≈ noise floor, SNR collapses | Yes — low-signal state |
| Locked to steady carrier | Key state stuck on, no elements | Yes — duty cycle |
| Locked to a harmonic | Weaker but correctly keyed | Harmless, reduced SNR |
| Off-frequency by ~30 Hz | Shrinking trigger margins, sporadic dropouts | Weakly — degrades quietly |
| Hopping between signals | Interleaved elements, clusters collapse | Yes — `modelFitQuality` |
| Locked to the wrong CW station | Clean, valid Morse | **No.** None whatsoever |

The last row is why FR-9.12 exists. If two signals are present and the wrong one is
locked, the decoder is not malfunctioning — it is correctly decoding someone else. There
is no internal error signal. Prominent frequency display plus one-tap candidate switching
is the only possible mitigation.
