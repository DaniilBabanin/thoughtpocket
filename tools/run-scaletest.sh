#!/usr/bin/env bash
# Run the on-device scale test WITHOUT wiping sideloaded models.
#
# `./gradlew connectedAndroidTest` uninstalls the app when it's done, which clears
# the app's external llm/ dir (the Gemma .litertlm models). This script instead:
#   - installs both APKs with `adb install -r` (keeps app data + external files),
#   - restores E2B/E4B from Edge Gallery if the llm/ dir is empty,
#   - runs the test via `am instrument` (which never uninstalls),
# so the models survive across runs.
#
# Usage: tools/run-scaletest.sh [scale|tuning] [adb-serial]
# JAVA_HOME may be set in the environment; defaults to the project's pinned JDK 17.
set -euo pipefail
METHOD="${1:-scale}"
SER="${2:-}"
ADB=(adb); [ -n "$SER" ] && ADB=(adb -s "$SER")
export JAVA_HOME="${JAVA_HOME:-/home/db/jdk/jdk-17.0.13+11}"
DST=/sdcard/Android/data/com.thoughtpocket/files/llm
APK=app/build/outputs/apk/debug/app-debug.apk
TAPK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q
"${ADB[@]}" install -r "$APK" >/dev/null
"${ADB[@]}" install -r "$TAPK" >/dev/null

if [ -z "$("${ADB[@]}" shell "ls $DST/*.litertlm 2>/dev/null" | tr -d '\r')" ]; then
  echo "Models missing — restoring from Edge Gallery…"
  bash tools/copy-edge-model.sh E2B "$SER"
  bash tools/copy-edge-model.sh E4B "$SER"
fi

"${ADB[@]}" logcat -c
"${ADB[@]}" shell am instrument -w \
  -e class "com.thoughtpocket.ScaleTest#$METHOD" \
  com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
echo "=== SCALE log ==="
"${ADB[@]}" logcat -d -s SCALE | sed 's/.*SCALE *: //' | grep -vE '^\s*$|beginning of'
