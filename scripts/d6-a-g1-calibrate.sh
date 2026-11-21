#!/usr/bin/env bash
# G1 only: validate test machinery on synthetic test-package data. No release APK installed.
set -euo pipefail
bash scripts/d6-emulator-preflight.sh
readonly SERIAL="$ANDROID_SERIAL"
readonly FIXTURE=org.thanatophilia.xyuime.d6fixture
readonly OBSERVER=org.thanatophilia.xyuime.rime.test
readonly OUT=d6-a-g1-report
readonly RUN="g1-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
readonly PRIVATE="$(mktemp -d)"
OWNED=NO
a() { timeout 40s adb -s "$SERIAL" "$@"; }
cleanup() {
  if [[ "$OWNED" == YES ]]; then
    a shell am force-stop "$FIXTURE" >/dev/null 2>&1 || true
    a shell am force-stop "$OBSERVER" >/dev/null 2>&1 || true
    # Only this run's newly installed test packages on the disposable emulator.
    a shell run-as "$OBSERVER" rm -rf files/d6-release-snapshots files/d6-release-calibration files/d6-release-snapshot-evidence >/dev/null 2>&1 || true
  fi
  rm -rf "$PRIVATE"
}
trap cleanup EXIT
[[ ! -e "$OUT" ]] || { echo 'G1 refuses stale report directory' >&2; exit 2; }
mkdir -p "$OUT"
a shell pm list packages --user 0 > "$PRIVATE/packages.txt"
python3 - "$PRIVATE/packages.txt" <<'PY'
import sys
from pathlib import Path
lines = Path(sys.argv[1]).read_text().splitlines()
if not lines or any(not line.startswith('package:') for line in lines):
    raise SystemExit('G1 package inventory unavailable')
installed = {line.strip()[8:] for line in lines}
for package in ('org.thanatophilia.xyuime', 'org.thanatophilia.xyuime.debug',
                'org.thanatophilia.xyuime.d6fixture', 'org.thanatophilia.xyuime.d6fixture.test',
                'org.thanatophilia.xyuime.rime.test'):
    if package in installed:
        raise SystemExit('G1 requires clean disposable test package state')
PY
OWNED=YES
# G1 editor calibration must not bind any IME. It uses only the editor's own InputConnection.
a install -t d6-upgrade-fixture/build/outputs/apk/debug/d6-upgrade-fixture-debug.apk
a install -t d6-upgrade-fixture/build/outputs/apk/androidTest/debug/d6-upgrade-fixture-debug-androidTest.apk
a install -t rime-engine/build/outputs/apk/androidTest/debug/rime-engine-debug-androidTest.apk
timeout 180s adb -s "$SERIAL" shell am instrument -w -r \
  -e class org.thanatophilia.xyuime.d6fixture.EditorCalibrationTest \
  -e d6Phase calibrate -e d6Run "$RUN" \
  "$FIXTURE.test/androidx.test.runner.AndroidJUnitRunner" > "$PRIVATE/editor.txt" 2>&1
python3 scripts/d6-a-g1-report.py --run "$RUN" --raw "$PRIVATE/editor.txt" --kind editor --output "$OUT/editor.json"
a shell run-as "$FIXTURE" cat "files/d6-fixture-evidence/$RUN-calibrate.json" > "$PRIVATE/editor.json"
python3 scripts/d6-a-g1-report.py --run "$RUN" --raw "$PRIVATE/editor.txt" --kind editor --report "$PRIVATE/editor.json" --output "$OUT/editor.json"
# The observer's explicit calibration phase owns only its isolated synthetic files.
timeout 300s adb -s "$SERIAL" shell am instrument -w -r \
  -e class org.thanatophilia.xyuime.rime.RimeReleaseSnapshotTest \
  -e d6Phase calibrate -e d6Run "$RUN" \
  "$OBSERVER/androidx.test.runner.AndroidJUnitRunner" > "$PRIVATE/observer.txt" 2>&1
python3 scripts/d6-a-g1-report.py --run "$RUN" --raw "$PRIVATE/observer.txt" --kind observer --output "$OUT/observer.json"
a shell run-as "$OBSERVER" cat files/d6-release-snapshot-evidence/calibrate.json > "$PRIVATE/observer.json"
python3 scripts/d6-a-g1-report.py --run "$RUN" --raw "$PRIVATE/observer.txt" --kind observer --report "$PRIVATE/observer.json" --output "$OUT/observer.json"
printf '{"result":"PASS","scope":"G1 synthetic machinery only; G2-G5 NOT_RUN","release_installed":false}\n' > "$OUT/summary.json"
printf 'D6-A G1 calibration PASS; no release installation or release data access\n'
