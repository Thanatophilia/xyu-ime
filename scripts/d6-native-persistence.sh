#!/usr/bin/env bash
# Disposable CI emulator only; four fresh test processes, never the user's IME package.
set -euo pipefail
PKG=org.thanatophilia.xyuime.rime.test
RUNNER="$PKG/androidx.test.runner.AndroidJUnitRunner"
CLASS=org.thanatophilia.xyuime.rime.RimeNativePersistenceTest
OUT=emulator-review/d6-native
mkdir -p "$OUT"
TEST_APK=rime-engine/build/outputs/apk/androidTest/debug/rime-engine-debug-androidTest.apk
test -f "$TEST_APK"
timeout 120s adb install -r -t "$TEST_APK"
trap 'adb shell am force-stop "$PKG" >/dev/null 2>&1 || true' EXIT
for phase in seed verify fault recover; do
  timeout 20s adb shell am force-stop "$PKG"
  timeout 20s adb shell pidof "$PKG" > "$OUT/$phase-before-pid.txt" || true
  if test -s "$OUT/$phase-before-pid.txt"; then
    echo "Previous isolated process is still alive" >&2; exit 1
  fi
  # This phase ends after real NativeRime.shutdown. Fault/recover is therefore not an
  # open-DB crash test; that separate D6 gate remains outstanding.
  timeout 240s adb shell am instrument -w -r -e class "$CLASS" -e d6Phase "$phase" "$RUNNER" \
    > "$OUT/$phase-instrumentation.txt" 2>&1
  if ! rg -q 'OK \(1 test\)' "$OUT/$phase-instrumentation.txt"; then
    cat "$OUT/$phase-instrumentation.txt" >&2; exit 1
  fi
  timeout 20s adb shell run-as "$PKG" cat "files/d6-native-evidence/$phase.json" > "$OUT/$phase.json"
done
python3 - "$OUT" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
phases={p:json.loads((root/f'{p}.json').read_text()) for p in ('seed','verify','fault','recover')}
assert len({v['process'] for v in phases.values()})==4, 'fresh process identities required'
assert phases['seed']['negative_control_commits']==0
assert phases['seed']['learned_commits']>0
assert phases['verify']['user_record_commits']>=phases['seed']['learned_commits']
assert phases['fault']['modified_commits']>phases['fault']['original_commits']
assert phases['recover']['restored_commits']==phases['fault']['original_commits']
assert phases['recover']['recovery_repeated']==2
print('D6 native finalize + four cold processes + real closed-DB rollback: PASS')
PY
