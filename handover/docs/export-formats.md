# Export formats

Three destinations share one serialiser: clipboard, file (SAF), and share
(`ACTION_SEND`). Two formats.

## Plain text (`.txt`)

```
# Morse decoder session
# started    2026-07-28T14:02:11+03:00
# source     microphone
# duration   00:07:42
# wpm range  17.8 - 22.4

CQ CQ DE SV1ABC K
GM ES TNX FER CALL <?> UR RST 5N9
RR QTH ATHENS [HH] QTH PIRAEUS
| R FB OM 73 SK
```

Header is omitted for clipboard export. Annotations per FR-9.10:

| Marker | Meaning |
|---|---|
| `<?>` | Undecodable sequence |
| `[HH]` | Sender signalled an error |
| `\|` | Tone re-lock occurred |

Low confidence has no plain-text form and is omitted (FR-10.5). Suffixing marked
characters (`R?R QTH`) is harder to read than the raw decode.

Cut numbers appear as sent (`5N9`) unless the assist is enabled, in which case affected
characters are marked in the app but exported expanded (`599`).

## Structured (`.json`)

The important one — this is the corpus collection mechanism, not just a convenience.
Recording real operators and exporting gives ground-truth-adjacent data with the
decoder's own measurements attached.

```json
{
  "schemaVersion": 1,
  "session": {
    "startedAt": "2026-07-28T14:02:11+03:00",
    "durationMs": 462000,
    "source": { "type": "microphone", "name": null },
    "appVersion": "1.0.0",
    "assists": { "lexicon": false, "cutNumbers": false },
    "wpmRange": [17.8, 22.4]
  },
  "characters": [
    {
      "index": 0,
      "char": "C",
      "confidence": 0.94,
      "tOffsetMs": 1840,
      "elements": [
        { "type": "mark", "durationMs": 182, "symbol": "dash" },
        { "type": "gap",  "durationMs": 61,  "role": "intra" },
        { "type": "mark", "durationMs": 59,  "symbol": "dot" },
        { "type": "gap",  "durationMs": 63,  "role": "intra" },
        { "type": "mark", "durationMs": 178, "symbol": "dash" },
        { "type": "gap",  "durationMs": 58,  "role": "intra" },
        { "type": "mark", "durationMs": 62,  "symbol": "dot" }
      ],
      "unitMsLocal": 60.4,
      "unitMsGlobal": 61.2,
      "toneHz": 612,
      "snrDb": 14.2,
      "modelFitQuality": 0.91
    }
  ],
  "annotations": [
    { "type": "undecodable", "charIndex": 27 },
    { "type": "lowConfidence", "charIndex": 41, "confidence": 0.38 },
    { "type": "errorProsign", "charIndex": 52, "tOffsetMs": 214300 },
    {
      "type": "relock",
      "charIndex": 68,
      "tOffsetMs": 288100,
      "fromHz": 612,
      "toHz": 745,
      "trigger": "manual"
    }
  ]
}
```

### Notes

- `charIndex` in `annotations` refers to the position in `characters`. A `relock` or
  `errorProsign` annotation sits *before* the character at that index.
- `elements` carries the raw measured durations *and* the symbol the decoder assigned.
  Keeping both is what makes the file useful for re-running a changed DSP against the
  same measurements.
- `unitMsLocal` vs `unitMsGlobal` exposes the shrinkage from FR-5.6 — useful for
  diagnosing drift-tracking behaviour.
- `trigger` on a relock is `"manual"`, `"candidate"`, or `"automatic"`.
- `symbol` may be `"dot"`, `"dash"`, or `"unknown"`; `role` on a gap may be `"intra"`,
  `"inter"`, `"word"`, or `"unknown"`.

### Open

Whether to bundle the source audio as WAV alongside the JSON. It makes the corpus
genuinely reusable when the DSP changes, at real storage cost on a phone. Suggested:
available in a developer/debug mode, off by default.
