#!/usr/bin/env bash
# Fail-closed safety gate for destructive D6 actions on a CI disposable emulator.
set -euo pipefail

fail() { printf 'D6 emulator preflight refused: %s\n' "$1" >&2; exit 2; }

[[ "${D6_DISPOSABLE_EMULATOR:-}" == "YES" ]] || fail "explicit disposable-emulator opt-in is required"
[[ "${GITHUB_ACTIONS:-}" == "true" ]] || fail "GitHub Actions is required"
[[ "${GITHUB_EVENT_NAME:-}" == "workflow_dispatch" ]] || fail "manual workflow_dispatch is required"
[[ "${GITHUB_REF:-}" == "refs/heads/main" ]] || fail "the selected workflow ref must be main"
[[ "${ANDROID_SERIAL:-}" =~ ^emulator-[0-9]+$ ]] || fail "ANDROID_SERIAL must identify one emulator-* device"
command -v adb >/dev/null 2>&1 || fail "adb is unavailable"

serial="$ANDROID_SERIAL"
adb -s "$serial" get-state 2>/dev/null | rg -qx 'device' || fail "selected emulator is not online"
qemu="$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$qemu" == "1" ]] || fail "selected device does not identify itself as an emulator"
api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api" == "36" ]] || fail "expected Android API 36; found ${api:-empty}"
abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$abi" == "x86_64" ]] || fail "expected x86_64 emulator; found ${abi:-empty}"

adb -s "$serial" root >/dev/null 2>&1 || fail "adb root is unavailable; data-level testing is forbidden"
adb -s "$serial" wait-for-device
uid="$(adb -s "$serial" shell id -u | tr -d '\r')"
[[ "$uid" == "0" ]] || fail "adb did not obtain root on the disposable emulator"
printf 'D6 disposable emulator preflight PASS: serial=%s api=%s abi=%s root=yes\n' "$serial" "$api" "$abi"
