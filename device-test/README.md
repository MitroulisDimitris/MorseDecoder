# Device test — running the app against a loudspeaker (Windows)

Plays ten Morse clips through the PC's speakers while the app listens on a phone connected
over USB, then scores what the app decoded. This is `progress.md` **step 0**: it checks the
app on real hardware, with a real room and a real microphone in the path — the things no
JVM test can see (`known-issues.md` D-14 was found this way).

About 15 minutes for the full set. Nothing here needs the PC this was written on.

## What you need

| | Why | Notes |
|---|---|---|
| **Android Studio**, installed to the default `C:\Program Files\Android\Android Studio` | Its bundled JDK builds the project, and its SDK provides `adb` | `gradle.properties` points Gradle at `C:/Program Files/Android/Android Studio/jbr`. Installed elsewhere? Change that one line. |
| **Git for Windows** | Clones the repo; its **Git Bash** runs `tools/device-run.sh` | |
| An Android phone with **USB debugging** on | Runs the app | Settings → About phone → tap *Build number* 7× → Developer options → *USB debugging* |
| PC speakers | Play the clips | Normal listening volume |
| Python 3 | *Optional* — scores the phone's own log | Not needed: step 7 scores without it |

## Steps

All commands are for **Git Bash**, from the repository root.

1. **Get the code.**
   ```sh
   git clone https://github.com/MitroulisDimitris/MorseDecoder.git
   cd MorseDecoder
   ```
   Open the folder once in Android Studio and let it sync: that writes `local.properties`
   (the SDK location). Without it, set it by hand — forward slashes, because a properties
   file treats `\` as an escape:
   `echo "sdk.dir=C:/Users/$USERNAME/AppData/Local/Android/Sdk" > local.properties`

2. **Check the phone is visible.**
   ```sh
   ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
   "$ADB" devices
   ```
   Accept the "Allow USB debugging?" prompt on the phone. The phone must be listed as
   `device`, not `unauthorized`.

3. **Build and install a debug build.** It must be *debug*: the capture dump and event log
   the test relies on are inert in release builds.
   ```sh
   ./gradlew :app:assembleDebug
   "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a copy signed on another PC
   is installed. `"$ADB" uninstall com.morsedecoder.app` (it erases that copy's settings),
   then install again.

4. **Grant the microphone** (saves tapping the prompt):
   ```sh
   "$ADB" shell pm grant com.morsedecoder.app android.permission.RECORD_AUDIO
   ```

5. **Place the phone** 30–50 cm from the speakers, microphone towards them, in a quiet room.
   **Unlock it** and leave it unlocked; the script keeps the screen awake while on USB and
   turns that off when it finishes.

6. **Run the clips.**
   ```sh
   tools/device-run.sh device-test/run device-test/clips/*.wav
   ```
   For each clip it restarts the app, plays the clip, listens 10 s more, and saves into
   `device-test/run/`: the app's event log (`.log`), a screenshot (`.png`) and the exact audio
   the microphone handed the decoder (`.wav`). `device-test/run/` is gitignored.

7. **Score it.** Replays the captured audio through the decoder on the PC — the result is
   identical to what the phone decoded, and needs no Python:
   ```sh
   REAL_DIR="$(pwd -W)/device-test/clips" ./gradlew :core:test --tests "*RealAudioBench*" -i
   ```
   Read the `live` rows (the app's path; `file` is the import path on the same audio) and the
   `mean CER` lines at the end. With Python, the phone's own log scores the same way:
   `python tools/score-device.py device-test/run device-test/clips/truth.tsv`

8. **Record the result** in `version-history.md` against the commit you ran.

## The clips

`clips/` holds 11 WAVs (16 kHz mono) and the answer key, `clips/truth.tsv`. Each clip has 3 s
of silence at both ends, because how the app behaves before a signal starts and after it
stops is part of what is measured.

| clip | what it tests |
|---|---|
| `00-room-quiet` | 30 s of silence — the app must lock onto nothing and print nothing |
| `01` machine, 20 WPM, 700 Hz | baseline |
| `02` hand-like, 15 WPM, 600 Hz | 2.5:1 dash ratio, 15 % timing jitter |
| `03` rough fist, 13 WPM, 650 Hz | `known-issues.md` D-6 — the decoder's own limit |
| `04` machine, 25 WPM, 800 Hz, +3 dB noise | noise in the signal itself |
| `05` machine, 35 WPM, 900 Hz | top of the speed range |
| `06` hand-like, 7 WPM, 500 Hz | bottom of the speed range |
| `07` 18 WPM, 1850 Hz, deep fading + static | like a real off-air recording |
| `08` 600 Hz, then 1100 Hz after 5 s | a tone change (D-9 / D-23) |
| `09` 15 WPM, 700 Hz, 30 s silence in the middle | a pause must not cause a re-lock (AC-9) |
| `10` 15 → 25 WPM ramp, 750 Hz | speed tracking (AC-5, AC-6) |

They are generated, not recorded: `PHONE_CLIPS_OUT=<dir> ./gradlew :core:test --tests
"*PhoneClipsTool*"` writes the same files byte for byte on any machine (use an absolute
path — Gradle runs tests from inside `core/`).

## Optional: compare microphone sources on a new phone

`known-issues.md` D-14: on the first test phone the default UNPROCESSED source swallowed the
start of every mark, and the app now uses VOICE_RECOGNITION. To check another phone, create a
marker naming a source before a run (debug builds only), e.g. for MIC:
```sh
"$ADB" shell touch /sdcard/Android/data/com.morsedecoder.app/files/audio_source_MIC
tools/device-run.sh device-test/run-MIC device-test/clips/01-machine-20wpm-700hz.wav
"$ADB" shell rm /sdcard/Android/data/com.morsedecoder.app/files/audio_source_MIC
```
Names: `MIC`, `CAMCORDER`, `VOICE_RECOGNITION`, `UNPROCESSED`, `DEFAULT`.

## If something goes wrong

- **`$'\r': command not found`** — the script was checked out with Windows line endings.
  `.gitattributes` prevents this on a fresh clone; on an old one,
  `git rm --cached tools/device-run.sh && git checkout -- tools/device-run.sh`.
- **No sound** — playback uses Windows' default output device, through PowerShell.
- **Every clip is empty** — the phone locked, or the app is not a debug build (step 3).
- **Stopped half-way** — run `"$ADB" shell svc power stayon false` to let the screen sleep again.
