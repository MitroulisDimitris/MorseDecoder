# Test status

**Last updated:** 2026-11-09 · full device run on app built from `c1c0058`

Error rate = **CER**, the share of characters decoded wrong: **0 % is perfect**, 100 % is
nothing right, **over 100 %** means it also invented extra characters. Figures are for the
**live** path (what the app does while listening) unless a column says otherwise.
✅ ≤ 10 % · ⚠️ 10–30 % · ❌ > 30 % · ⏳ not run yet.

## At a glance

| | Status |
|---|---|
| Unit tests (`./gradlew :core:test`) | ✅ 123 tests pass · 7 known failures (open defects, listed below) · 3 benches skipped |
| Device test on a phone, current app | ⚠️ **all 10 clips run** — live mean **33.0 %**; 1 ✅, 6 ⚠️, 3 ❌; quiet room clean |
| Synthetic benchmark (`DecoderBench`) | live mean **17.3 %**, file mean **14.7 %** |

## Device test — per clip

Clips are in `device-test/clips/`; how to run them is in `device-test/README.md`. Phone:
Nothing A059P, VOICE_RECOGNITION source, quiet room, PC speakers ~40 cm away.

| # | Clip | **Phone, live (the app)** | Phone audio, file import¹ | Best possible² | Old mic source³ |
|---|---|---|---|---|---|
| 00 | quiet room, 30 s — must print nothing | ✅ nothing | ✅ nothing | ✅ nothing | ✅ nothing |
| 01 | machine, 20 WPM, 700 Hz | ⚠️ **28.9 %** | 20.0 % | 6.7 % | 82.2 % |
| 02 | hand-like, 15 WPM, 600 Hz | ⚠️ **17.0 %** | 8.5 % | 10.6 % | 91.5 % |
| 03 | rough fist, 13 WPM, 650 Hz | ❌ **117.6 %** | 70.6 % | 67.6 % | 79.4 % |
| 04 | machine, 25 WPM, 800 Hz, noisy | ⚠️ **13.5 %** | 0.0 % | 5.4 % | 13.5 % |
| 05 | machine, 35 WPM, 900 Hz | ⚠️ **12.8 %** | 0.0 % | 7.7 % | 202.6 % |
| 06 | hand-like, 7 WPM, 500 Hz | ⚠️ **11.1 %** | 11.1 % | 7.4 % | 74.1 % |
| 07 | fading + static, 18 WPM, 1850 Hz | ❌ **62.1 %** | 44.8 % | 69.0 % | 51.7 % |
| 08 | tone jump 600 → 1100 Hz | ❌ **36.7 %** | 51.0 % | 40.8 % | 77.6 % |
| 09 | 30 s pause in the middle | ⚠️ **21.6 %** | 0.0 % | 18.9 % | 75.7 % |
| 10 | speed ramp 15 → 25 WPM | ✅ **8.6 %** | 0.0 % | 4.3 % | 64.3 % |
| | **mean (01–10)** | **33.0 %** | **20.6 %** | | |

¹ The same phone capture decoded by the file-import path. Where this is near 0 % and the live
column is not (04, 05, 09, 10), the microphone audio was fine and the live decoder lost text.
² The original clip decoded live straight from the file, no speaker or microphone.
³ Captured through the phone's UNPROCESSED source, which swallowed the start of every
mark (`known-issues.md` D-14). The app no longer uses it; kept for comparison.

**What the run shows**

- **The live path drops the first 1–3 characters** of almost every clip (`THE`, `SPH`, `BE`,
  one `V`) while it learns the speed (`known-issues.md` D-2). This is most of the gap between
  the live and file-import columns.
- **01 lost its closing digits on both paths**, and was 11.1 % in the earlier run. Both
  paths losing it points at that capture's audio, not the live decoder (not yet checked);
  re-run 01 before reading much into it. It also
  re-locked onto room noise at 316 Hz once playback ended (no characters printed).
- **09** resumes after the 30 s pause with `9TER`, and `111` / `222` come out as `1` / `2`.
- **08** now hands over to the new tone (re-lock at 30 s) and decodes `TEST FOUR FIVE`, but
  loses `THREE SECOND` during the hand-over.
- **03 and 07 are decoder limits:** bad from the clean file too (D-6 rough fist; deep fading).

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
REAL_DIR="$(pwd -W)/device-test/clips" REAL_REPORT="$(pwd -W)/device-test/run/table.md" \
  ./gradlew :core:test --tests "*RealAudioBench*" -i
```

`device-test/run/table.md` then holds this file's per-clip numbers as a ready-made table
(live CER, file CER, tone, speed, first characters decoded). Copy the live and file columns in,
add a line to the history below, and commit.

## History

| Date | App commit | Phone (live, mean of clips run) | Note |
|---|---|---|---|
| 2026-11-08 | `5212505` | ~84 % (10 clips) | first phone run; noise locks, old mic source |
| 2026-11-09 | `63261f6` | 35.8 % (clips 01–03) | noise guards (D-24) + VOICE_RECOGNITION (D-25); 01 and 02 near best possible |
| 2026-11-09 | `c1c0058` | **33.0 % (all 10)** | first full run on the new source; file import on the same audio 20.6 %; live loses the opening characters |
