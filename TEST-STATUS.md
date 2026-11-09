# Test status

**Last updated:** 2026-11-09 · commit `63261f6` (+ this file)

Error rate = **CER**, the share of characters decoded wrong: **0 % is perfect**, 100 % is
nothing right, **over 100 %** means it also invented extra characters. Figures are for the
**live** path (what the app does while listening); the file-import path is in the detail
tables. ✅ ≤ 10 % · ⚠️ 10–30 % · ❌ > 30 % · ⏳ not run yet.

## At a glance

| | Status |
|---|---|
| Unit tests (`./gradlew :core:test`) | ✅ 123 tests pass · 7 known failures (open defects, listed below) · 3 benches skipped |
| Device test on a phone, current app | ⏳ **3 of 10 clips run** — full run is `progress.md` step 0 |
| Synthetic benchmark (`DecoderBench`) | live mean **17.3 %**, file mean **14.7 %** |

## Device test — per clip

Clips are in `device-test/clips/`; how to run them is in `device-test/README.md`.

| # | Clip | **Phone, current app** | Best possible¹ | Old mic source² |
|---|---|---|---|---|
| 00 | quiet room, 30 s — must print nothing | ✅ nothing | ✅ nothing | ✅ nothing |
| 01 | machine, 20 WPM, 700 Hz | ⚠️ **11.1 %** | 6.7 % | 82.2 % |
| 02 | hand-like, 15 WPM, 600 Hz | ⚠️ **17.0 %** | 10.6 % | 91.5 % |
| 03 | rough fist, 13 WPM, 650 Hz | ❌ **79.4 %** | 67.6 % | 79.4 % |
| 04 | machine, 25 WPM, 800 Hz, noisy | ⏳ | 5.4 % | 13.5 % |
| 05 | machine, 35 WPM, 900 Hz | ⏳ | 7.7 % | 202.6 % |
| 06 | hand-like, 7 WPM, 500 Hz | ⏳ | 7.4 % | 74.1 % |
| 07 | fading + static, 18 WPM, 1850 Hz | ⏳ | 69.0 % | 51.7 % |
| 08 | tone jump 600 → 1100 Hz | ⏳ | 40.8 % | 77.6 % |
| 09 | 30 s pause in the middle | ⏳ | 18.9 % | 75.7 % |
| 10 | speed ramp 15 → 25 WPM | ⏳ | 4.3 % | 64.3 % |

¹ The same clip decoded straight from the file, no speaker or microphone — the best the
decoder can do today. Where this column is bad (03, 07, 08), the decoder is the limit, not
the phone.
² Captured through the phone's UNPROCESSED source, which swallowed the start of every
mark (`known-issues.md` D-14). The app no longer uses it; kept for comparison.

**Known weak spots, decoder side:** 03 is `known-issues.md` D-6 (a rough fist); 07 is deep
fading; 08 loses part of the transmission after the tone changes, until the D-23 hand-over.

## Unit tests — known failures

These tests assert behaviour the decoder does not have yet; the build stays green while
they fail, and turns red if one starts passing so this list cannot go stale.

| Defect (`known-issues.md`) | What fails |
|---|---|
| D-6 | a rough 13 WPM fist decodes at ~66 % |
| D-8 ×5 | speech, RTTY, clipping and (file path) knocking decode as Morse; fit score cannot tell RTTY from Morse |
| D-11 | a decode 39 % wrong looks identical to a correct one |

## Synthetic benchmark — summary

`BENCH=1 ./gradlew :core:test --tests "*DecoderBench*" -i` — 20 scenarios, synthetic audio.

| | live | file |
|---|---|---|
| mean | 17.3 % | 14.7 % |
| worst | 100 % (`room-far`, nothing decoded) | 188.6 % (`room-far`) |
| clean machine-sent (`clean-20`, `ac1`, `ac2`) | 2.5 % | 0.0 % |

## How to update this file

After a device run (`device-test/README.md` steps 1–6):

```sh
REAL_DIR="$(pwd -W)/device-test/clips" REAL_REPORT=device-test/run/table.md \
  ./gradlew :core:test --tests "*RealAudioBench*" -i
```

`device-test/run/table.md` then holds this file's per-clip numbers as a ready-made table
(live CER, file CER, tone, speed, first characters decoded). Copy the live column into
**Phone, current app**, add a line to the history below, and commit.

## History

| Date | Commit | Phone (live, mean of clips run) | Note |
|---|---|---|---|
| 2026-11-08 | `5212505` | ~84 % (10 clips) | first phone run; noise locks, old mic source |
| 2026-11-09 | `63261f6` | 35.8 % (clips 01–03) | noise guards (D-24) + VOICE_RECOGNITION (D-25); 01 and 02 near best possible |
