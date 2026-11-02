#!/usr/bin/env bash
set -euo pipefail
ABI="${1:?usage: device-video.sh ABI}"
PKG=com.midoali.atari.debug

./gradlew :android:installDebug --no-daemon --max-workers=2 --stacktrace \
  -Pandroid.injected.build.abi="$ABI"

# Prevent Android's first-run immersive-mode confirmation from covering the game.
adb shell settings put secure immersive_mode_confirmations confirmed || true
# A "Pixel Launcher isn't responding" dialog steals focus and every injected touch, which
# made three verified tests look broken. Suppress system error dialogs on this emulator.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

adb push ci-rom/umk3-usa.md /data/local/tmp/umk3.md
adb shell run-as "$PKG" mkdir -p files
adb shell run-as "$PKG" cp /data/local/tmp/umk3.md files/umk3.md

rc=0
./gradlew :android:connectedDebugAndroidTest --no-daemon --max-workers=2 --stacktrace \
  -Pandroid.injected.build.abi="$ABI" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.midoali.atari.VideoInstrumentedTest,com.midoali.atari.ControlsInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.rom="/data/data/$PKG/files/umk3.md" \
  -Pandroid.testInstrumentationRunnerArguments.screenshots=true \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || rc=$?

mkdir -p artifacts/device-captures
for f in $(adb shell ls /data/local/tmp/umk3-*.png 2>/dev/null | tr -d '\r'); do
  adb pull "$f" artifacts/device-captures/ || true
done
adb exec-out screencap -p > artifacts/device-captures/screen.png || true
adb logcat -d > artifacts/device-captures/logcat.txt || true
adb shell run-as "$PKG" ls -la files/test-captures > artifacts/device-captures/test-captures-list.txt 2>&1 || true
adb exec-out run-as "$PKG" tar -cf - -C files test-captures > artifacts/device-captures/test-captures.tar 2>artifacts/device-captures/test-captures-tar-error.txt || true
if [ -s artifacts/device-captures/test-captures.tar ]; then
  tar -xf artifacts/device-captures/test-captures.tar -C artifacts/device-captures || true
fi
exit "$rc"
