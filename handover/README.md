# Morse decoder — handover

Specification package for an Android Morse code decoder with automatic speed detection
and noise tolerance. Drop this folder into a repo and point Claude Code at it.

```
morse-decoder-handover/
├── CLAUDE.md                        project context and invariants
├── README.md
├── requirements/
│   └── v1-requirements.md           the spec — numbered, referenced elsewhere
├── docs/
│   ├── pipeline.md                  signal chain, three clocks, failure modes
│   ├── decisions.md                 rationale for non-obvious choices
│   ├── export-formats.md            .txt and .json schemas
│   └── ui-spec.md                   screen layout, annotation vocabulary
└── design/
    └── main-screen.html             visual reference, open in a browser
```

## What this is

An app that listens to Morse code — from a phone mic pointed at a radio speaker, or from
an imported audio file — and transcribes it. It works out the tone frequency and the
sending speed on its own, and it is built around hand-sent code, which is far messier
than machine-keyed code.

Supported speed range is 5–40 WPM, which covers essentially all human sending.

## The design stance

The app reports what it heard. It does not guess at what was meant. Corrections exist,
are clearly marked, and are off by default. The transcript is append-only — even when the
sender signals an error, the app marks the point and keeps going rather than deleting
text.

Where the input is genuinely unusable — heavy room reverb, competing speech, weak signal
— the app says so instead of producing confident-looking garbage.

## Start here

1. `CLAUDE.md` — invariants and build order
2. `requirements/v1-requirements.md` — the actual spec
3. `docs/decisions.md` — before changing anything that looks arbitrary

## Status

Specification only. No code written. First task is milestone 1 in `CLAUDE.md`: the file
import path, the `PcmSource` abstraction, and the synthetic Morse generator that
everything else is tested against.
