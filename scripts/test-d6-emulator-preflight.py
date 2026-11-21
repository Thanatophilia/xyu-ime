#!/usr/bin/env python3
"""Test that the destructive D6 device gate is explicit and fail-closed."""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("d6-emulator-preflight.sh")


class EmulatorSafetyGateTest(unittest.TestCase):
    def run_gate(self, *, opted_in: bool = False, serial: str = "emulator-5554",
                 api: str = "36", abi: str = "x86_64", root_rc: int = 0) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "adb.log"
            fake_adb = root / "adb"
            fake_adb.write_text("""#!/bin/sh
printf '%s\\n' \"$*\" >> \"$D6_FAKE_ADB_LOG\"
case \"$*\" in
  *'get-state') echo device ;;
  *'getprop ro.kernel.qemu') echo 1 ;;
  *'getprop ro.build.version.sdk') echo \"$D6_FAKE_API\" ;;
  *'getprop ro.product.cpu.abi') echo \"$D6_FAKE_ABI\" ;;
  *' root') exit \"$D6_FAKE_ROOT_RC\" ;;
  *'id -u') echo 0 ;;
esac
""", encoding="utf-8")
            fake_adb.chmod(0o755)
            env = os.environ.copy()
            env.update({
                "PATH": f"{root}:{env['PATH']}",
                "D6_FAKE_ADB_LOG": str(log),
                "D6_FAKE_API": api,
                "D6_FAKE_ABI": abi,
                "D6_FAKE_ROOT_RC": str(root_rc),
                "GITHUB_ACTIONS": "true",
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/main",
                "ANDROID_SERIAL": serial,
            })
            if opted_in:
                env["D6_DISPOSABLE_EMULATOR"] = "YES"
            else:
                env.pop("D6_DISPOSABLE_EMULATOR", None)
            result = subprocess.run(["bash", str(SCRIPT)], text=True, capture_output=True,
                                    env=env, timeout=10, check=False)
            result.adb_log = log.read_text(encoding="utf-8") if log.exists() else ""
            return result

    def test_requires_explicit_disposable_emulator_opt_in_before_adb(self) -> None:
        result = self.run_gate(opted_in=False)
        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.adb_log)

    def test_rejects_non_emulator_serial_before_adb(self) -> None:
        result = self.run_gate(opted_in=True, serial="device-serial")
        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.adb_log)

    def test_rejects_wrong_android_api(self) -> None:
        result = self.run_gate(opted_in=True, api="35")
        self.assertEqual(2, result.returncode)
        self.assertIn("expected Android API 36", result.stderr)

    def test_rejects_non_x86_64_abi(self) -> None:
        result = self.run_gate(opted_in=True, abi="arm64-v8a")
        self.assertEqual(2, result.returncode)
        self.assertIn("expected x86_64", result.stderr)

    def test_rejects_unavailable_root(self) -> None:
        result = self.run_gate(opted_in=True, root_rc=1)
        self.assertEqual(2, result.returncode)
        self.assertIn("adb root is unavailable", result.stderr)

    def test_accepts_only_expected_rooted_disposable_emulator(self) -> None:
        result = self.run_gate(opted_in=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("D6 disposable emulator preflight PASS", result.stdout)
        self.assertGreaterEqual(len(result.adb_log.splitlines()), 6)
        self.assertTrue(all("emulator-5554" in line for line in result.adb_log.splitlines()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
