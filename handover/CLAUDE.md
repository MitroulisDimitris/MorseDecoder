# Morse decoder — project context

Android app that decodes audible Morse code from a microphone or an imported audio file,
auto-detecting tone frequency and sending speed, tolerant of environmental noise and
human timing variation.

This folder is a specification handover. **No code exists yet.**

## Read first

| File | What it is |
|---|---|
| `requirements/v1-requirements.md` | The spec. Numbered requirements, referenced everywhere else |
| `docs/pipeline.md` | Signal chain architecture, the three clocks, failure modes |
| `docs/decisions.md` | Why the non-obvious choices are what they are |
| `docs/export-formats.md` | `.txt` and `.json` schemas |
| `docs/ui-spec.md` | Screen layout and annotation vocabulary |
| `design/main-screen.html` | Visual reference, open in a browser |

## Invariants

These are load-bearing. Violating one produces something that looks like it works and
isn't the app that was specified. Each traces to a principle in §1 of the requirements.

- **Append-only transcript.** Nothing emitted is ever rewritten or retracted — including
  on the `HH` error prosign and on tone re-lock. Annotate in place, keep appending.
- **Raw output by default.** All assists (lexicon, cut numbers) ship off. The default
  decode is what was measured, not what was probably meant.
- **Estimate the unit from marks only.** Never from gaps. Three independent reasons in
  `docs/decisions.md` D-2.
- **Never assume a 3:1 dash/dot ratio.** Weak prior only.
- **Lattice decoding is always on.** It is not a "correction" — see D-5. Disabling it to
  satisfy the raw-output principle would be a misreading.
- **Re-lock guards count elements, not seconds.** Silence must never trigger a re-lock.
- **No network permission.** Fully offline, no exceptions.

## Build order

Follow §7 of the requirements. Milestone 1 is not optional scaffolding:

1. Harness — file import, `PcmSource`, synthetic generator, threshold decoder
2. Robust timing decoder — mixtures, local unit fitting, Viterbi, `modelFitQuality`
3. Live path — mic, adaptive noise floor, lock state machine, re-lock guards
4. UI
5. Export
6. Assists

Without milestone 1 every threshold gets tuned by intuition and every fix silently
regresses something else. Build the synthetic generator before the decoder.

## Testing

Acceptance criteria are in §6 of the requirements, stated as character error rate at
given SNR and speed. They only apply within 5–40 WPM and in raw mode (assists off).

The synthetic generator needs drift models, not just static WPM plus jitter — ramps,
wobble, hesitation, and per-character systematic bias. Real hand-sent recordings are
required for milestone 2; synthetic jitter does not capture how humans actually send.

Two regression guards worth wiring early:

- **AC-9** — a good lock survives 30 s of silence with zero automatic re-locks
- **AC-5** — a drifting stream costs no more than 2 percentage points of CER versus the
  static baseline

## Undecided

Stack and API level are open — nothing in the spec assumes Kotlin/Compose, though that
is the obvious default. Remaining questions are in §8 of the requirements. Ask rather
than guessing on these; several of them change the architecture.
