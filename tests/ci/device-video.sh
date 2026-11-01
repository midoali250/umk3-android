#!/usr/bin/env bash
set -euo pipefail
ABI="${1:?usage: device-video.sh ABI}"
PKG=com.midoali.atari.debug

./gradlew :android:installDebug --no-daemon --max-workers=2 --stacktrace \
  -Pandroid.injected.build.abi="$ABI"

adb push ci-rom/umk3-usa.md /data/local/tmp/umk3.md
adb shell run-as "$PKG" cp /data/local/tmp/umk3.md files/umk3.md

rc=0
./gradlew :android:connectedDebugAndroidTest --no-daemon --max-workers=2 --stacktrace \
  -Pandroid.injected.build.abi="$ABI" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.midoali.atari.VideoInstrumentedTest,com.midoali.atari.ControlsInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.rom="/data/data/$PKG/files/umk3.md" \
  -Pandroid.testInstrumentationRunnerArguments.screenshots=true || rc=$?

mkdir -p artifacts/device-captures
adb logcat -d > artifacts/device-captures/logcat.txt || true
for f in $(adb shell run-as "$PKG" ls files/test-captures 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$PKG" cat "files/test-captures/$f" > "artifacts/device-captures/$f" || true
done
exit "$rc"
