#!/bin/bash
# Plays WAV files through the PC speakers while the app listens on a USB-attached phone,
# and collects what the app did. Needs a *debug* build installed (DebugTap is inert on
# release builds), the phone unlocked, and the phone placed near the speakers.
#
#   tools/device-run.sh <outdir> <clip.wav>...
#   DUMP=0 tools/device-run.sh ...        # skip the raw-capture dump
#   QUIET_S=30 tools/device-run.sh ...    # how long to keep listening after playback
#
# Per clip, <outdir> receives:
#   <clip>.log   the app's MorseBench event log (chars, re-locks, 2 s status), plus
#                MARK:play_start / MARK:play_end / MARK:quiet_end from this script
#   <clip>.wav   the exact samples the engine was fed (DebugTap dump) — replay it with
#                REAL_DIR=... gradlew :core:test --tests "*RealAudioBench*" -i
#   <clip>.png   a screenshot at the end
#
# Score the logs with tools/score-device.py. Playback uses PowerShell's SoundPlayer, so
# this runs from Git Bash on Windows; swap the play line for aplay/afplay elsewhere.
set -u
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
export MSYS_NO_PATHCONV=1
PKG=com.morsedecoder.app
FILES=/sdcard/Android/data/$PKG/files
OUT="$1"; shift; mkdir -p "$OUT"
QUIET_S="${QUIET_S:-10}"

"$ADB" shell svc power stayon usb
trap '"$ADB" shell svc power stayon false; "$ADB" shell rm -f $FILES/dump_pcm' EXIT
if [ "${DUMP:-1}" = 1 ]; then "$ADB" shell "mkdir -p $FILES && touch $FILES/dump_pcm"; fi

for W in "$@"; do
  N=$(basename "$W" .wav)
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  "$ADB" shell am force-stop $PKG
  "$ADB" shell rm -rf $FILES/captures
  sleep 1
  "$ADB" logcat -c
  "$ADB" shell am start -n $PKG/.MainActivity >/dev/null
  sleep 4
  "$ADB" shell log -t MorseBench "MARK:play_start"
  powershell.exe -NoProfile -Command "(New-Object Media.SoundPlayer '$(cygpath -w "$W")').PlaySync()"
  "$ADB" shell log -t MorseBench "MARK:play_end"
  sleep "$QUIET_S"
  "$ADB" shell log -t MorseBench "MARK:quiet_end"
  "$ADB" exec-out screencap -p > "$OUT/$N.png"
  "$ADB" shell am force-stop $PKG
  "$ADB" logcat -d -v time -s MorseBench:I > "$OUT/$N.log"
  CAP=$("$ADB" shell "ls $FILES/captures/*.wav 2>/dev/null" | tr -d '\r' | tail -1)
  # adb.exe is a Windows binary: with path conversion off, the local side needs a Windows path.
  DEST="$OUT/$N.wav"; command -v cygpath >/dev/null && DEST=$(cygpath -w "$DEST")
  if [ -n "$CAP" ]; then "$ADB" pull "$CAP" "$DEST" >/dev/null || echo "pull failed: $CAP"; fi
  echo "done $N"
done
