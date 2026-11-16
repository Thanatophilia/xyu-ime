#!/usr/bin/env bash
set -uo pipefail
mkdir -p emulator-review
# This setting belongs to the disposable test device, not the user's phone.
adb shell settings put secure show_ime_with_hard_keyboard 1
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest
result=$?
timeout 20s adb pull /sdcard/Android/data/org.thanatophilia.xyuime.debug/files/Pictures/diagnostics/. emulator-review/ || true
timeout 15s adb logcat -d -t 3000 > emulator-review/logcat.txt || true
timeout 15s adb shell dumpsys input_method > emulator-review/input-method.txt || true
exit "$result"
