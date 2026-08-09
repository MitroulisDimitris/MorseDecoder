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

### The spectrum strip is also the range control

Two frequency spans are in play, and keeping them distinct is the whole design:

- **The graph** is the axis extent — a persisted setting, changed rarely, on the settings
  screen. It never moves while the user is watching a signal.
- **The search range** is what the decoder actually sweeps. It is narrowed live by
  dragging dotted edges on the strip, and lasts only for the session.

The strip is the right home for this because the user reaches for it reactively: they are
watching a wrong lock happen and want it fixed now, not two screens away. Direct
manipulation also removes a translation step — they can already see the artifact's
position, so making them convert it into a number would be work the interface should do.

Three rules make it legible:

- **Excluded spectrum is dimmed, not hidden.** The user narrows the range precisely
  because something out there was winning the lock. Hiding it would make the strip lie
  about what is in the room, and the narrowing would look like it had done nothing.
- **The axis belongs to the graph, not the range.** Bars stay put while the shading moves.
  An axis rescaling under a dragging finger feels like the control fighting back.
- **The edge wedges point inward,** into the searched side, so which way to drag needs no
  legend.

Gestures: drag an edge to move it; long-press a peak to isolate it (±60 Hz — the
one-gesture form of "just decode this one"); double-tap to clear isolation and narrowing
together. A `⟲` appears beside the range readout whenever it is non-default, because the
undo gesture is invisible and the state must never be.

Minimum span is 100 Hz. A range dragged shut would leave the decoder searching nothing.

### The threshold is drawn, not just numbered — and exactly once

`thr ±N dB` on its own is unreadable: the user cannot tell whether the tone is above or
below the bar the decoder is applying. So the live keying threshold is drawn across the
spectrum strip, in the bars' own units — decibels above the signal's own noise floor over
a 30 dB range. (The bars measure against the FFT band floor and the rule against the
envelope floor, so this compares headroom, not absolute level. Headroom is the question
being asked.)

**One rule, never two.** An earlier version also drew the threshold's lower travel limit
as a dashed second line, to answer "will turning `thr` down help?". It answered that, and
in exchange implied the decoder applies two thresholds — the first thing a user asks on
seeing it is which of the two is real. A limit is a property of the control, not of the
signal, so it is stated on the control: the steppers disable at each end and the readout
reads `−10 dB min`. The strip shows only what the decoder is actually doing.

The general rule this is an instance of: the spectrum strip carries *measurements of the
signal*. Anything that is a fact about a control belongs beside that control.

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
- **Output doesn't look like text** — the decoded characters average fewer marks than real
  text can (`known-issues.md` D-1). Decoding continues; the banner suggests pinning WPM

The last two decorate rather than replace, and the second is the deliberate exception to the
rule above. A wrong *timebase* is invisible to the fit readout — Morse timing is scale-free,
so `fit` sits at 1.00 while the transcript reads `TEE E EEE EEET` — and this banner is the
only thing on screen that would say so. It does not stop the decode, because by the time the
statistic can speak the characters are already emitted and P-2 forbids retracting them, and
because the margin behind it is measured on synthetic audio only (`decisions.md` D-22).
Suggesting the fix the user can apply — the WPM pin — is the whole of its job.

## Settings

Reachable from the status row (⚙) so it is available while idle and while listening.

It holds only what depends on the **user's equipment**, not on the signal: the tone
graph's bounds are the first such value, because sidetone pitch and recording chain vary
per operator and no default fits everyone. Decoder thresholds stay out — they were derived
from measurements against real audio, and asking a user to tune them would be asking them
to guess at something the project calibrated on purpose.

This screen is for *exact*, the strip is for *fast*. They are the same state, not two
controls: the screen sets the outer bounds, the strip narrows within them. It is also the
accessible path to those values, since dragging an edge is not usable with a screen
reader — which is reason enough to keep it even once the strip exists.

Changes apply to the running session immediately and reset any live narrowing. The lock
may be lost (the search restarts if the current tone falls outside the new bounds) but the
transcript is kept — P-2 governs the decoder never retracting, and a settings change is
not a retraction.

## Still to design

- Session history / file import screens
- Settings (thresholds, manual WPM pin, developer mode)
- The WPM trace (FR-9.3) — currently a single number in the mockup; needs a sparkline
  or a small time-series strip
- Export sheet
