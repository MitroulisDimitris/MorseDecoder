# UI specification — main screen

Reference mockup: `design/main-screen.html` (open in a browser; supports light and dark).

Aesthetic target: simple but technical. Instrument-panel readouts, monospace numerals,
hairline borders, no decoration. The user is aiming a phone at a radio and needs to know
at a glance whether the decode can be trusted.

## Layout, top to bottom

1. **Status row** — listening indicator and lock pill with elapsed lock time
2. **Readout grid** — WPM, tone, SNR, fit
3. **Spectrum strip** — locked peak, candidates, frequency axis, keying threshold
4. **Lock controls** — Re-lock, Pin, candidate chips
5. **Transcript** — monospace, with annotations
6. **Assist switches** — visibly off

## Non-obvious decisions

### Fit is a primary readout, not a settings-screen detail

`modelFitQuality` is the only proxy the user has for "am I decoding the right thing." It
sits alongside WPM and SNR. Green above ~0.8, amber below, red when the lock is
`degraded`.

### The threshold is drawn, not just numbered

`thr ±N dB` on its own is unreadable — the user cannot tell whether turning it down will
help. Two rules overlay the spectrum strip: the live keying threshold, and dashed beneath
it, where that threshold sits with the control turned all the way down. The gap between
them is the sensitivity still in hand. When they meet, the control is spent, and a tone
below the line is not going to be recovered by turning `thr` down further — which is the
moment to move the phone rather than keep tapping. The steppers grey out at both ends for
the same reason: a control that keeps counting while the internal clamp swallows the
change is lying about having an effect.

Both rules are plotted in the bars' own units — decibels above the signal's own noise
floor, over a 30 dB range. The bars measure against the FFT band floor and the rules
against the envelope floor, so this compares headroom, not absolute level. Headroom is
the question being asked.

### The lock timer is doing real work

`LOCKED 04:12` tells the user the lock has been stable, which is what they need to decide
whether a stretch of garbage is a lock problem or just bad sending. Without it, every
decode failure looks the same.

### Candidate chips are labelled actions, not readouts

A bare `745` communicates nothing — it reads as another number in a panel full of
numbers. Render as `⇄ 745 Hz`. The slot is **empty when no candidate qualifies**, so its
appearance is itself informative. Maximum two (FR-2.15).

Tapping switches the lock immediately, resets the mixtures, and inserts a re-lock marker.

### Pin and Re-lock are separate controls

Re-lock is a one-shot "try again now". Pin is a latch that freezes the frequency and
suppresses automatic re-lock. Merging them would force users who want stability to keep
tapping a button.

### Assists show their state in the control

`lexicon · off` rather than a toggle whose position must be interpreted. P-3 requires
that the default-off state be obvious, not merely true.

## Transcript annotations

Four types, one vocabulary (FR-9.10). All must survive export.

| Rendering | Meaning | Plain text |
|---|---|---|
| Dotted amber underline | Low confidence | omitted |
| `<?>` amber chip | Undecodable sequence | `<?>` |
| `HH` red chip | Sender signalled an error | `[HH]` |
| `\|` accent bar | Tone re-lock | `\|` |

The re-lock bar shows old frequency, new frequency, and timestamp **on tap**. Rendering
`612→745` inline becomes unreadable in a busy band.

Nothing preceding an `HH` or a `\|` is altered. Both mark a point in time; the user reads
around them.

## Warning states

Per P-4 these replace output rather than decorating it:

- **Low signal** — decoding stops, transcript freezes, banner names the likely cause
- **Bluetooth mic** — persistent warning that decode quality will be degraded
- **Outside calibrated range** — speed below 5 or above 40 WPM; decoding continues
  best-effort, accuracy targets void

## Settings

Reachable from the status row (⚙) so it is available while idle and while listening.

It holds only what depends on the **user's equipment**, not on the signal: the tone
search-band ceiling is the first such value, because sidetone pitch and recording chain
vary per operator and no default fits everyone. Decoder thresholds stay out — they were
derived from measurements against real audio, and asking a user to tune them would be
asking them to guess at something the project calibrated on purpose.

Band changes apply to the running session immediately. The lock may be lost (the search
restarts if the current tone falls outside the new band) but the transcript is kept —
P-2 governs the decoder never retracting, and a settings change is not a retraction.

## Still to design

- Session history / file import screens
- Settings (thresholds, manual WPM pin, developer mode)
- The WPM trace (FR-9.3) — currently a single number in the mockup; needs a sparkline
  or a small time-series strip
- Export sheet
