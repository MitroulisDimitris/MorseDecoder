# Real audio — what exists, where it lives, what comes next

`progress.md` step 11 is the first open step: build a corpus of **real hand-sent** Morse,
because every number this project tunes against is otherwise its own generator (review of
2026-11-09 in `progress.md`). This file is the handover for that work.

## Why the audio is not in this repo

The repository is public, and the recordings below are third-party with no stated licence
(the ARRL texts are also QST excerpts). Only the owner's own recordings are committed:
`device-test/clips/` (generated) and `device-test/captures/` (the phone runs).

## What exists today, outside the repo

On the owner's first PC, `C:\Users\dmitr\Desktop\morse-phone-clips\` (~280 MB). Copy the whole
folder to the machine you work on; nothing in it is machine-specific.

| Path | What |
|---|---|
| `kaggle/wav/*.wav` | 16 kHz mono PCM16 conversions of the files below — what the benches read |
| `kaggle/realqso/` | `realQSO_001..003`: real on-air contacts. **Only 001 has a transcript** (`realQSQ_001.txt`, sic) |
| `kaggle/arrl/` | 8 ARRL W1AW code-practice broadcasts (mp3 + text), 10/15/20/30 WPM; the "10 WPM" ones are Farnsworth at 15 |
| `kaggle_cases.tsv` | case list for the 11 Kaggle files (realQSO + ARRL), relative to the folder |
| `cases.tsv` | everything: the generated clips, the old UNPROCESSED phone recordings (`phone_wav/`), Kaggle |
| `device_run*/` | older device runs (superseded by `device-test/captures/`) |

Source of the Kaggle files: dataset **MorseCode_Sound_Collections** by bg4xsd (downloaded as
`archive (2).zip`, 2.7 GB). Most of that archive is machine-sent practice audio.

```sh
REAL_DIR=<folder> REAL_CASES=kaggle_cases.tsv ./gradlew :core:test --tests "*RealAudioBench*" -i
SLIDE_DIR=<folder> SLIDE_CASES=kaggle_cases.tsv ./gradlew :core:test --tests "*SlidingFileBench*" -i
```

Results 2026-11-09 (live / file): realQSO_001 **43.0 / 100 %**; ARRL 0.2–0.6 / 0.0–0.7 %.
realQSO_001 is the only real hand-sent recording with a transcript, which is the problem.

## Step 11 — build the hand-sent set

**No public dataset of real hand-sent Morse with transcripts exists** (searched 2026-11-09:
Hugging Face, Kaggle, GitHub, papers; DeepFist's own README calls hand-sent fists "the open
frontier"). So it is built from real on-air recordings:

| Source | What | Notes |
|---|---|---|
| **IZ4KBW archive** — Google Drive folder `1XaF3Mvwa6WvgOCzZjp0NwzgnYdNHsW2Q`, announced on [SWLing Post](https://swling.com/blog/page/195/?p=jzhkupjh) | thousands of real amateur CW contacts, mostly Italian ragchews | **best lead**; casual contacts like these are often straight key or bug. No transcripts |
| [G4YXX recordings](https://archive.org/details/norman-varnes-g4yxx-wt-recordings/) on archive.org | coast-station and military CW, MP3 | professional, often hand-sent, very formulaic |
| `realQSO_002`, `realQSO_003` | already downloaded | no transcript yet |
| Freesound 40 m clips | single clips | check each clip's licence |

The first PC's FortiClient blocks `swling.com` and `drive.google.com` (it answers with its
own certificate); download from another machine.

**Procedure**

1. Download 20–30 IZ4KBW contacts; prefer ones that sound hand-sent (uneven rhythm).
2. Put them in `morse-phone-clips/real/` and convert to 16 kHz mono PCM16 WAV
   (`ffmpeg -i in.mp3 -ac 1 -ar 16000 -sample_fmt s16 out.wav`). `WavIo` reads PCM16, PCM8
   and float32 WAV only.
3. Draft each with both decoders (`RealAudioBench` with an empty reference prints the text).
4. Confirm only what can be confirmed: **callsigns** (check on QRZ), **RST**, **name**,
   **QTH**, `73` / `TU`. Best done by someone who can copy CW by ear, even slowly; failing
   that, callsign formats and public logs.
5. Score on those confirmed tokens — `progress.md` step 12 adds a partial-reference mode to
   `RealAudioBench` for this. Full CER needs full transcripts, which nobody has.
