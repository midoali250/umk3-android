#!/usr/bin/env bash
set -euo pipefail
ABI="${1:?usage: device-video.sh ABI}"
PKG=com.midoali.atari.debug

./gradlew :android:installDebug --no-daemon --max-workers=2 --stacktrace \
  -Pandroid.injected.build.abi="$ABI"

adb push ci-rom/umk3-usa.md /data/local/tmp/umk3.md
adb shell run-as "$PKG" mkdir -p files
adb shell run-as "$PKG" cp /data/local/tmp/umk3.md files/umk3.md

rc=0
./gradlew :android:connectedDebugAndroidTest --no-daemon --max-workers=2 --stacktrace \
  -Pandroid.injected.build.abi="$ABI" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.midoali.atari.VideoInstrumentedTest,com.midoali.atari.ControlsInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.rom="/data/data/$PKG/files/umk3.md" \
  -Pandroid.testInstrumentationRunnerArguments.screenshots=true || rc=$?

mkdir -p artifacts/device-captures
adb exec-out screencap -p > artifacts/device-captures/screen.png || true
adb logcat -d > artifacts/device-captures/logcat.txt || true
adb shell run-as "$PKG" ls -la files/test-captures > artifacts/device-captures/test-captures-list.txt 2>&1 || true
adb exec-out run-as "$PKG" tar -cf - -C files test-captures > artifacts/device-captures/test-captures.tar 2>artifacts/device-captures/test-captures-tar-error.txt || true
if [ -s artifacts/device-captures/test-captures.tar ]; then
  tar -xf artifacts/device-captures/test-captures.tar -C artifacts/device-captures || true
fi
exit "$rc"
