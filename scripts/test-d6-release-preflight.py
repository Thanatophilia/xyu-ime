#!/usr/bin/env python3
"""Dependency-free tests for the D6 release APK metadata gate."""
from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("d6-release-preflight.py")
spec = importlib.util.spec_from_file_location("d6_release_preflight", SCRIPT)
assert spec and spec.loader
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)

DATA_VERSION = "pinyin-simp-0c6861e-3"
NEW_VERSION = "pinyin-simp-0c6861e-3-d6-ci-b"
ASSETS = {name: f"sha256-{i}" for i, name in enumerate(preflight.ASSETS)}
IDENTITY = {
    "package_name": "org.thanatophilia.xyuime",
    "certificate_sha256": "fa62895fa256fb286acb6546a1663331af42fa08abac4c1ce3fa1645ee90d7d0",
    "abis": ["arm64-v8a", "x86_64"],
    "rime_asset_sha256": ASSETS,
}


def apk(name: str, code: int, data_version: str, digest: str) -> dict:
    return {
        "path": name, "sha256": digest, **IDENTITY,
        "version_code": code, "version_name": f"0.3.{code}",
        "data_versions": [data_version],
    }


class ApkInspectionTest(unittest.TestCase):
    def test_extracts_release_identity_assets_and_data_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(path, "w") as apk_file:
                for index, name in enumerate(preflight.ASSETS):
                    apk_file.writestr(name, f"asset-{index}")
                apk_file.writestr("classes.dex", b"pinyin-simp-0c6861e-3")
                apk_file.writestr("lib/arm64-v8a/libxyu_rime.so", b"native-arm")
                apk_file.writestr("lib/x86_64/libxyu_rime.so", b"native-x86")
            class Result:
                returncode = 0
                stderr = ""
                def __init__(self, stdout: str) -> None:
                    self.stdout = stdout
            manifest = {"value": "N: android=http://schemas.android.com/apk/res/android\\n"}
            def run(command: list[str], **_: object) -> Result:
                if command[1:3] == ["dump", "badging"]:
                    return Result("package: name='org.thanatophilia.xyuime' versionCode='145' versionName='0.3.145'\\n")
                if command[1:3] == ["dump", "xmltree"]:
                    return Result(manifest["value"])
                return Result("Verifies\\nVerified using v2 scheme (APK Signature Scheme v2): true\\nSigner #1 certificate SHA-256 digest: FA:62:89:5F:A2:56:FB:28:6A:CB:65:46:A1:66:33:13:1A:F7:42:FA:08:AB:AC:4C:1C:3F:A1:64:5E:E9:0D:7D:0\\n")
            from unittest.mock import patch
            with patch.object(preflight.subprocess, "run", side_effect=run):
                result = preflight.inspect_apk(path, "aapt", "apksigner")
            self.assertEqual(145, result["version_code"])
            self.assertEqual(DATA_VERSION, result["data_versions"][0])
            self.assertEqual(["arm64-v8a", "x86_64"], result["abis"])
            self.assertEqual(3, len(result["rime_asset_sha256"]))
            manifest["value"] = " A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff\\n"
            with patch.object(preflight.subprocess, "run", side_effect=run):
                with self.assertRaises(preflight.PreflightError):
                    preflight.inspect_apk(path, "aapt", "apksigner")


class ReleasePairValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.baseline = {
            "accepted_source": {
                "version_name": "0.3.145", "version_code": 145,
                "package_name": IDENTITY["package_name"],
                "apk_sha256": "a" * 64,
                "signer_certificate_sha256": IDENTITY["certificate_sha256"],
                "data_version": DATA_VERSION,
            }
        }
        self.base = apk("145.apk", 145, DATA_VERSION, "a" * 64)
        self.same = apk("149.apk", 149, DATA_VERSION, "b" * 64)
        self.bump = apk("149-b.apk", 149, NEW_VERSION, "c" * 64)

    def test_same_resource_release_pair_passes(self) -> None:
        report = preflight.validate_pair(self.baseline, self.base, self.same, "same", None)
        self.assertEqual("PASS", report["result"])
        self.assertTrue(report["comparison"]["same_package_and_signer"])
        self.assertEqual(DATA_VERSION, report["comparison"]["expected_candidate_data_version"])

    def test_isolated_resource_version_pair_passes(self) -> None:
        report = preflight.validate_pair(
            self.baseline, self.base, self.bump, "resource-bump", NEW_VERSION
        )
        self.assertEqual(NEW_VERSION, report["comparison"]["expected_candidate_data_version"])

    def assert_rejected(self, base: dict, candidate: dict, mode: str = "same",
                        version: str | None = None) -> None:
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_pair(self.baseline, base, candidate, mode, version)

    def test_rejects_wrong_frozen_baseline_hash(self) -> None:
        bad = {**self.base, "sha256": "f" * 64}
        self.assert_rejected(bad, self.same)

    def test_rejects_package_or_signer_change(self) -> None:
        self.assert_rejected(self.base, {**self.same, "package_name": "other.app"})
        self.assert_rejected(self.base, {**self.same, "certificate_sha256": "0" * 64})

    def test_rejects_downgrade_and_same_version(self) -> None:
        for code in (145, 144):
            self.assert_rejected(self.base, apk("bad.apk", code, DATA_VERSION, "d" * 64))

    def test_rejects_unintended_asset_changes(self) -> None:
        changed = {**self.same, "rime_asset_sha256": {**ASSETS, preflight.ASSETS[0]: "changed"}}
        self.assert_rejected(self.base, changed)

    def test_rejects_wrong_or_ambiguous_data_version(self) -> None:
        self.assert_rejected(self.base, apk("bad.apk", 149, NEW_VERSION, "e" * 64))
        multiple = {**self.same, "data_versions": [DATA_VERSION, NEW_VERSION]}
        self.assert_rejected(self.base, multiple)

    def test_resource_bump_requires_distinct_valid_version(self) -> None:
        self.assert_rejected(self.base, self.bump, "resource-bump", DATA_VERSION)
        self.assert_rejected(self.base, self.bump, "resource-bump", "../escape")
        self.assert_rejected(self.base, self.bump, "resource-bump", None)


if __name__ == "__main__":
    unittest.main(verbosity=2)
