#!/usr/bin/env bash
set -uo pipefail
mkdir -p emulator-review
# This setting belongs to the disposable test device, not the user's phone.
adb shell settings put secure show_ime_with_hard_keyboard 1
# Fail fast on the current window/accessibility work before the longer regression set.
# Keep its reports separately: connectedDebugAndroidTest replaces its output directory.
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.thanatophilia.xyuime.ImePhase6WindowAccessibilityTest,org.thanatophilia.xyuime.KeyboardAccessibilityTest,org.thanatophilia.xyuime.KeyboardSymbolPageTest,org.thanatophilia.xyuime.ImePhase7SymbolInputTest
result=$?
if [ "$result" -eq 0 ]; then
  cp -a app/build/outputs/androidTest-results/connected emulator-review/d4-targeted-results
  ./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest
  result=$?
fi
timeout 20s adb pull /sdcard/Download/xyu-ime-review/. emulator-review/ || true
timeout 15s adb logcat -d -t 3000 > emulator-review/logcat.txt || true
timeout 15s adb shell dumpsys input_method > emulator-review/input-method.txt || true
exit "$result"
