# Morse Decoder — v1 Requirements

Android app that decodes audible Morse code from a microphone or an imported audio
file, automatically detecting the tone frequency and sending speed, and tolerating
realistic environmental noise and human timing variation.

---

## 1. Design principles

These govern every ambiguous decision below. If a requirement below appears to conflict
with a principle here, the principle wins and the requirement is wrong.

- **P-1 — Raw first.** The default output is what was actually heard. The decoder
  reports elements as it measured them; interpretation is the user's job.
- **P-2 — Append-only transcript.** Nothing already emitted is ever rewritten or
  retracted. Annotations may be attached; characters may not be replaced in place.
- **P-3 — No silent intervention.** Any assist that changes the characters shown is
  visibly marked and individually toggleable, and is **off by default**.
- **P-4 — Honest failure.** When input quality is inadequate, warn the user. Do not
  attempt to compensate for input that is inherently broken, and do not emit
  confident-looking output from unusable signal.
- **P-5 — Present tense only.** Decoding starts when listening starts. Audio before
  that point does not exist.

---

## 2. Scope

### In scope for v1

- Live microphone input and imported audio file input
- Automatic tone frequency detection, tracking, and re-lock
- Automatic speed (WPM) detection over **5–40 WPM**
- Machine-sent and hand-sent code, with hand-sent as the priority case
- Slow speed drift tracking within a session
- Adaptive noise floor with explicit low-SNR warning
- Full ITU character set, numerals, punctuation, and common prosigns
- Session transcript with annotations, export, and share

### Explicitly out of scope for v1

| Item | Rationale |
|---|---|
| Step-change speed detection | Deferred. Note the mixture-reset path built for FR-2.12 is most of the machinery; leave the speed trigger disabled rather than absent |
| Speeds below 5 or above 40 WPM | Outside the calibrated band; flag, don't decode |
| Line-in / direct radio connection | Mic and file only |
| "This isn't Morse" rejection | Not worth the complexity. FR-5.8 covers the useful subset |
| Recovering audio from before listening started | See P-5 |
| Transcript rewriting of any kind | See P-2 |
| Network features of any kind | Offline app; no network permission requested |

---

## 3. Functional requirements

### FR-1 — Audio input

- **FR-1.1** Capture via `AudioRecord`, 16 kHz mono PCM16.
- **FR-1.2** Request an unprocessed or voice-recognition audio source. AGC and noise
  suppression must be disabled where the device allows it — AGC destroys the envelope
  and NS can gate the tone entirely.
- **FR-1.3** Import audio files via the Storage Access Framework. Decode through
  `MediaExtractor`/`MediaCodec` and resample to 16 kHz mono. Support at minimum WAV,
  MP3, M4A/AAC, OGG, FLAC.
- **FR-1.4** Both sources feed a single `PcmSource` abstraction. The decoder core has
  no knowledge of which source it is consuming.
- **FR-1.5** Detect a Bluetooth SCO microphone route and warn the user that decode
  quality will be degraded.
- **FR-1.6** Handle audio focus loss (incoming call, another app claiming the mic) by
  pausing cleanly and resuming when focus returns. Never fail silently.

### FR-2 — Tone detection and lock

- **FR-2.1** Locate the carrier by rolling FFT peak search over 300–1200 Hz.
- **FR-2.2** Extract per-hop energy with a Goertzel filter locked to the tracked
  frequency.
- **FR-2.3** Track frequency with a slow EMA. Adaptation must be slow enough not to
  chase transmitter chirp during a key-down.
- **FR-2.4** Apply lock hysteresis so that a second nearby signal (QRM) does not cause
  the tracker to hop between tones mid-transmission.
- **FR-2.5** Do not lock within ±5 Hz of a 50 Hz or 60 Hz mains harmonic without an
  explicit user override. 600 Hz is a harmonic of both.
- **FR-2.7** Analysis window nominally 10 ms with 50–75 % overlap, hop 2–5 ms; window
  length adapts once a speed estimate exists.

#### Lock state machine

- **FR-2.8** Lock states: `searching` → `acquiring` → `locked` → `degraded`, with
  `degraded` returning to `locked` or falling back to `searching`.
- **FR-2.9** Expose the current state and the elapsed time in `locked` to the UI.

#### Automatic re-lock guards

- **FR-2.10** An automatic re-lock may fire **only** when all of the following hold
  simultaneously. The bias is deliberately toward holding a questionable lock rather
  than dropping a good one, because a wrong lock is visible to the user and a dropped
  lock is not.
  1. `modelFitQuality` (FR-5.8) has been poor across **20+ observed elements** — counted
     in elements, never in seconds, so that silence can never trigger a re-lock.
  2. SNR is **above** the decode threshold. Low SNR is a signal problem; re-locking
     cannot fix it and the weak-signal warning applies instead.
  3. A fresh sweep finds a candidate scoring **better** than the current lock. If
     nothing beats it, stay.
  4. At least **15 s** have elapsed since the last lock change (hold-off, prevents
     oscillation between similar-strength signals).
  5. The frequency is **not user-pinned**.
- **FR-2.11** Manual re-lock is always available and bypasses all guards.
- **FR-2.12** On any re-lock, **reset the duration mixtures** (FR-5.2) — elements
  gathered under a wrong lock must not contaminate the new model. **Retain** the Kalman
  WPM estimate as a prior; the sender's speed did not change.
- **FR-2.13** Every re-lock, manual or automatic, inserts a re-lock marker into the
  transcript (FR-9.10). Per P-2 nothing preceding it is altered.

#### Candidate tones

- **FR-2.14** A secondary peak qualifies as a candidate when it is ≥ 6 dB above the
  noise floor, ≥ 100 Hz from the current lock, and sustained for ≥ 2 s. The sustain
  requirement exists to stop the candidate indicator flickering.
- **FR-2.15** Display at most **two** candidates. Beyond that the band is too crowded
  for the app to help and the user should be adjusting their radio.
- **FR-2.16** Selecting a candidate switches the lock immediately, applying FR-2.12
  and FR-2.13.
- **FR-2.6** Allow the user to pin the frequency manually. Pin disables automatic
  re-lock (FR-2.10.5) but not manual re-lock.

### FR-3 — Noise handling

- **FR-3.1** Estimate the noise floor as a rolling low percentile (~20th) of the
  envelope, never a mean. The window must be long relative to the slowest expected
  element so a 5 WPM dash cannot be adapted away.
- **FR-3.2** Convert the envelope to a binary keying stream via a Schmitt trigger:
  on above floor + X dB, off below floor + Y dB, with Y < X.
- **FR-3.3** Apply an impulse/median filter ahead of the envelope to suppress static
  crashes and key clicks.
- **FR-3.4** Discard marks and gaps shorter than ~0.3 × the current dot estimate.
- **FR-3.5** Bridge signal dropouts shorter than ~0.5 units (fading). Longer dropouts
  terminate the character and are reported as unknown rather than guessed.
- **FR-3.6** Maintain a continuous SNR estimate.
- **FR-3.7** Below a configurable SNR threshold, enter a **low-signal state**: stop
  emitting characters and display a warning identifying the likely cause (weak signal,
  competing audio). Per P-4, this is the whole response — no compensation is attempted.

### FR-4 — Element detection

- **FR-4.1** Produce a stream of typed durations: mark, gap, in milliseconds.
- **FR-4.2** Any partial character in progress when listening starts is discarded and
  must not contribute to bootstrap statistics.
- **FR-4.3** Gaps beyond ~10 units contribute "word boundary at most" and nothing
  further. They do not enter any duration model.

### FR-5 — Speed estimation and drift

- **FR-5.1** Bootstrap a coarse unit estimate from envelope autocorrelation over the
  first few seconds, before any clustering. This sets window lengths, noise-floor
  window, and cluster priors, and resolves the circularity that the duration gates are
  themselves expressed in units.
- **FR-5.2** Refine with a Gaussian mixture over **log** durations. Separate mixtures
  for marks and for gaps — required for Farnsworth timing, where character speed and
  spacing are decoupled.
- **FR-5.3** Estimate dot and dash centroids **independently**. The 3:1 ratio is a weak
  prior only, used to disambiguate degenerate fits. Real fists land at 2:1–2.5:1; bug
  senders produce clusters with very different variances.
- **FR-5.4** Estimate the unit from **mark durations only**. Gaps are used for
  segmentation, never for speed. Reverb shortens measured gaps and hesitation inflates
  them; neither affects marks.
- **FR-5.5** Track the global unit as a Kalman-filtered slowly-varying state. Expose
  its variance as a lock-quality signal.
- **FR-5.6** Fit a **local unit per candidate character**, shrunk toward the global
  estimate in proportion to available evidence. This is the primary drift mechanism —
  never classify a character against a stale global estimate.
- **FR-5.7** If the estimated speed leaves 5–40 WPM, continue decoding on a best-effort
  basis and display an "outside calibrated range" indicator. Accuracy targets do not
  apply in that state.
- **FR-5.8** Emit **`modelFitQuality`**, a normalised score for how well the observed
  mark durations fit the two-cluster model — cluster separation, ratio plausibility, and
  within-cluster variance. This is not a "is this Morse?" classifier; it asks only
  whether the decoder's own model fits its own measurements. It is a near-free byproduct
  of FR-5.2 and serves as the general upstream-health signal, catching wrong lock,
  off-frequency lock, lock hopping, and excessive noise alike.

### FR-6 — Decoding

- **FR-6.1** Do not commit greedily to character segmentation. Hypothesise boundaries
  at every plausibly-inter-character gap, fit a local unit per hypothesis, and score
  whole paths with Viterbi over a character lattice.
- **FR-6.2** Element classification is soft — distance to cluster centroid yields a
  confidence, not a hard label.
- **FR-6.3** **Timing-level lattice decoding is always on and is not a "correction"**
  under P-3. It resolves timing ambiguity using timing evidence only. What P-3 governs
  is FR-7, which changes characters using non-timing evidence.
- **FR-6.4** Prosigns (`AR`, `SK`, `BT`, `KN`, `AS`) are single entries in the lattice
  alphabet, not letter pairs. They are sent with no inter-character gap and will decode
  as garbage otherwise.
- **FR-6.5** Undecodable sequences emit a visible placeholder. Nothing is dropped
  silently.
- **FR-6.6** Every emitted character carries a confidence value.

### FR-7 — Optional assists (all default OFF)

Each is an independent switch. Each visibly marks every character it affects, and the
original raw decode remains inspectable.

- **FR-7.1** CW lexicon / letter n-gram prior as lattice weights. Corpus must be
  CW-specific — Q-codes, on-air abbreviations (`CQ`, `DE`, `QTH`, `RST`, `TNX`, `ES`,
  `UR`, `HW`, `73`) — not general English.
- **FR-7.2** When enabled, the lexicon must weaken or disable itself in callsign and
  exchange contexts (following `CQ` or `DE`, and within serial sequences). Callsigns
  are effectively random strings and a language prior will otherwise "correct" a valid
  callsign into a plausible word.
- **FR-7.3** Cut-number expansion (`T`→0, `N`→9, `A`→1; `5NN`→`599`).
- **FR-7.4** A global master switch disables all assists at once.

### FR-8 — Error prosign

- **FR-8.1** Detect the eight-dot error prosign (`HH`).
- **FR-8.2** Per P-2, **nothing is retracted.** The transcript continues to append.
- **FR-8.3** The occurrence is marked in the UI at the point it was received, so the
  user can see that the sender signalled a correction and decide what to do about it.

### FR-9 — Transcript annotations and UI

#### Annotation vocabulary

- **FR-9.10** Exactly four annotation types, treated as one deliberate vocabulary
  because they must survive export:

  | Annotation | Meaning | Plain-text form |
  |---|---|---|
  | Dotted underline | Low confidence | omitted (see FR-10.5) |
  | `<?>` | Undecodable sequence | `<?>` |
  | `HH` chip | Sender signalled an error | `[HH]` |
  | `\|` bar | Tone re-lock occurred | `\|` |

- **FR-9.11** The re-lock bar carries its detail on tap — old frequency, new frequency,
  timestamp — rather than rendering inline. Inline `612→745` becomes unreadable in a
  busy band.

#### Screen

- **FR-9.1** Live transcript with autoscroll, tap-to-copy, and low-confidence
  characters visually distinguished.
- **FR-9.2** Primary readout row: estimated WPM, detected tone frequency, SNR, and
  `modelFitQuality`. Fit belongs on the main row rather than in a menu — it is the only
  proxy the user has for "am I decoding the right thing".
- **FR-9.3** WPM shown as a live trace over the session, not a single number — drift is
  expected and visible drift is diagnostic.
- **FR-9.4** Spectrum or waterfall strip, primarily as an aiming aid, with the locked
  peak and any candidates visually distinguished.
- **FR-9.5** Manual overrides for every automatic parameter: pin frequency, pin WPM,
  adjust threshold.
- **FR-9.6** Assist switches (FR-7) surfaced clearly, with their off-by-default state
  obvious.
- **FR-9.7** Input-quality warnings, including the low-signal state, Bluetooth mic
  route, and out-of-range speed.
- **FR-9.8** Decode state survives rotation and configuration changes.
- **FR-9.9** `Pin` and `Re-lock` are **separate controls**. Re-lock is a one-shot
  "try again now"; Pin is a latch. Combining them would force users who want stability
  to keep tapping.
- **FR-9.12** Candidate tones render as labelled, tappable chips (e.g. `⇄ 745 Hz`) —
  never as a bare number, which reads as a readout rather than an action. The chip slot
  is empty when no candidate qualifies, so its appearance is itself meaningful.
- **FR-9.13** Lock state and elapsed lock time displayed persistently, so the user can
  tell whether a stretch of garbage is a lock problem or bad sending.

### FR-10 — Export and persistence

- **FR-10.1** Session history with timestamps.
- **FR-10.2** Three destinations sharing one serialiser: **clipboard** (copy all or copy
  selection), **file** (via SAF, user picks location), and **share** (Android
  `ACTION_SEND`).
- **FR-10.3** **Plain text (`.txt`)** — short metadata header (session start, source:
  mic or filename, duration, observed WPM range) followed by the transcript with ASCII
  annotations per FR-9.10. Clipboard uses this format minus the header.
- **FR-10.4** **Structured (`.json`)** — per character: character, confidence,
  timestamp, raw element durations, unit estimate in force, locked frequency. Plus
  session metadata and the annotation list with positions. Schema in
  `docs/export-formats.md`.
- **FR-10.5** Low confidence has no natural plain-text form and is **omitted** from
  `.txt` export; it is preserved in `.json`. Suffixing marked characters (`R?R QTH`)
  makes the text harder to read than the raw decode.
- **FR-10.6** Transcript and ring buffers are bounded so that long sessions cannot grow
  memory without limit.

---

## 4. Non-functional requirements

- **NFR-1** Fully offline. No network permission is declared.
- **NFR-2** Live decode latency budget is a function of speed, not a constant. A
  character cannot be emitted until the following gap is known to be inter-character
  (~3 units) plus the Viterbi commit horizon. At 20 WPM that is ~300 ms; at 5 WPM the
  inter-character wait alone is ~720 ms. Specify as `≈ 3 units + 120 ms`.
- **NFR-3** File decode runs at ≥ 10× realtime.
- **NFR-4** DSP thread stays well under one core; target < 5 % CPU on a mid-range device.
- **NFR-5** Deterministic offline mode — identical WAV input yields identical text
  output, so tests are reproducible.
- **NFR-6** Foreground service and wakelock for screen-off decoding.
- **NFR-7** `RECORD_AUDIO` requested at runtime, with a clear rationale screen.

---

## 5. File mode specifics

- **FM-1** File mode runs **two-pass**: sweep the file for tone frequency and duration
  clusters, then decode with parameters already locked. No bootstrap warmup, no
  mid-stream re-lock.
- **FM-2** Report a global WPM plus a per-segment WPM track for the file.
- **FM-3** File mode must not inherit the constraints of the live path. Live is the
  restricted case, not the reference case.

---

## 6. Test strategy

### Synthetic generator (build first)

Parameters: WPM, SNR, tone frequency, Farnsworth ratio, per-element jitter, fading
depth and rate, impulse noise density, hesitation injection, per-character systematic
bias.

Drift models required:

- Linear ramp, e.g. 15 → 25 WPM over 60 s
- Sinusoidal wobble, ±15 % at 0.05 Hz
- Hesitation gaps injected at word boundaries and mid-character
- Per-character fixed random bias held constant across a session — this is what a real
  fist actually looks like

Interference cases (for FR-2 and FR-5.8):

- Two simultaneous CW signals at different frequencies and speeds
- Off-frequency lock, injected deliberately, to confirm `modelFitQuality` degrades
- Steady carrier / mains hum with no keying

### Real corpus

Recordings of real operators, including straight key and bug. Synthetic jitter does not
capture how humans actually send. The `.json` export (FR-10.4) is the intended
collection mechanism — record, export, and the corpus arrives with the decoder's own
measurements attached.

### Acceptance criteria

Targets apply within 5–40 WPM. **Raw mode** means all FR-7 assists off, which is the
default configuration.

| # | Condition | Target |
|---|---|---|
| AC-1 | Machine-sent, +10 dB SNR, raw mode | CER < 2 % |
| AC-2 | Machine-sent, 0 dB SNR, raw mode | CER < 10 % |
| AC-3 | Hand-sent, 20 % jitter, +10 dB SNR, raw mode | CER < 12 % |
| AC-4 | AC-3 with lexicon assist enabled | CER < 6 % |
| AC-5 | Drifting stream vs. static-speed baseline | CER penalty ≤ 2 pp |
| AC-6 | Tracked WPM during a ramp | within ±10 % of truth throughout |
| AC-7 | WPM estimate convergence | within ±10 % after 20 elements |
| AC-8 | Low-signal state | fires before CER exceeds 25 %, rather than emitting garbage |
| AC-9 | Good lock through 30 s silence | zero automatic re-locks |
| AC-10 | Deliberate off-frequency lock | `modelFitQuality` degrades and re-lock recovers within 20 elements |

AC-3 is deliberately looser than an early draft. With the language prior off by default,
genuinely ambiguous cases — a collapsed inter-character gap making `C` and `NN`
indistinguishable from timing alone — cannot be resolved. That is an accepted
consequence of P-1, not a defect. AC-4 measures what the assist buys. AC-9 is the
regression guard for the re-lock guards in FR-2.10.

---

## 7. Build milestones

1. **Harness.** File import, `PcmSource` abstraction, synthetic generator, simple
   threshold decoder. Machine-sent, 10–25 WPM. Establishes reproducible measurement.
2. **Robust timing decoder.** Mixture model, local unit fitting, Viterbi lattice,
   `modelFitQuality`. Validated against the hand-sent corpus.
3. **Live path.** Microphone capture, adaptive noise floor, frequency tracking, lock
   state machine and re-lock guards, low-signal state, foreground service.
4. **UI.** Transcript, readouts, spectrum strip, candidate chips, manual overrides,
   annotation rendering.
5. **Export.** Plain text and JSON, clipboard/file/share.
6. **Assists.** CW lexicon, callsign context backoff, cut numbers — all behind switches,
   all off by default.

Milestone 1 is not optional groundwork. Without it, every threshold gets tuned by
intuition and every fix silently regresses something else.

---

## 8. Open questions

- Kotlin + Compose, or is there an existing app skeleton to reuse?
- Minimum supported API level?
- Should export optionally include the source audio as WAV alongside the JSON? It makes
  the corpus genuinely reusable when the DSP changes, at real storage cost. Suggested:
  yes in a developer/debug mode, no by default.
- Is a straight-key/bug *practice* mode (decode your own sending, show timing feedback)
  in scope later? Architecturally free; it would shape the UI.
