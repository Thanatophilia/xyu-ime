#!/usr/bin/env python3
"""MOCK LOGICAL tests only: tiny synthetic ZIPs, no real APK/SDK/device tests.

Only APK whole-file SHA is mocked for success paths; production frozen constants,
ZIP inspection, BUILD/SHA256SUMS parsing and cloud comparisons remain real.
Run directly with Python's standard library; no downloaded artifacts needed.
"""
from __future__ import annotations

import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest import mock

SPEC = importlib.util.spec_from_file_location(
    "wp0", Path(__file__).resolve().with_name("d6-release-upgrade-a-wp0.py"))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class MockLogicalWP0Tests(unittest.TestCase):
    """Each test is a mock logical regression, never real release evidence."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="wp0-logical-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.dirs = {kind: self.root / kind for kind in GATE.EXPECTED}
        for kind, root in self.dirs.items():
            root.mkdir()
            expected = GATE.EXPECTED[kind]
            self.write_zip(root / expected["apk"])
            (root / "BUILD.txt").write_text(self.build_text(kind), encoding="utf-8")
            (root / "SHA256SUMS.txt").write_text(
                f"{expected['sha256']}  {expected['apk']}\n", encoding="utf-8")
        self.cloud_path = self.root / "cloud.json"
        self.output = self.root / "result.json"
        self.locals = {kind: {
            "build": GATE.parse_build_text(self.build_text(kind)),
            "apk": {**GATE.inspect_apk(root / GATE.EXPECTED[kind]["apk"]),
                    "sha256": GATE.EXPECTED[kind]["sha256"]},
        } for kind, root in self.dirs.items()}
        self.report = {"result": "PASS", "mode": "same", "comparison": {
            "same_package_and_signer": True, "version_code_increased": True,
            "rime_assets_unchanged": True, "debug_probe_absent": True,
            "expected_candidate_data_version": GATE.DATA_VERSION,
        }}
        for kind, local in self.locals.items():
            self.report[kind] = {**copy.deepcopy(local["apk"]),
                "package_name": GATE.PACKAGE_NAME,
                "certificate_sha256": GATE.CERTIFICATE_SHA256,
                "version_name": local["build"]["version_name"],
                "version_code": local["build"]["version_code"],
                "rime_asset_sha256": copy.deepcopy(local["apk"]["assets"])}
            del self.report[kind]["assets"]
        self.save_report()

    @staticmethod
    def build_text(kind):
        item = GATE.EXPECTED[kind]
        return (f"Commit: {item['commit']}\nVersion: {item['version_name']} "
                f"({item['version_code']})\nBuilt UTC: 2026-11-21T00:00:00Z\n")

    @staticmethod
    def write_zip(path, *, omit=(), dex=None, native=b"synthetic native", extra=()):
        entries = {asset: f"synthetic {asset}".encode() for asset in GATE.ASSETS}
        entries.update({f"lib/{abi}/libxyu_rime.so": native for abi in GATE.ABIS})
        entries["classes.dex"] = dex if dex is not None else b"synthetic " + GATE.DATA_VERSION.encode() + b"\x00"
        with zipfile.ZipFile(path, "w") as apk:
            for name, value in entries.items():
                if name not in omit:
                    apk.writestr(name, value)
            for name, value in extra:
                apk.writestr(name, value)

    def save_report(self):
        self.cloud_path.write_text(json.dumps(self.report), encoding="utf-8")

    def frozen_sha_mock(self):
        def digest(path):
            for item in GATE.EXPECTED.values():
                if Path(path).name == item["apk"]:
                    return item["sha256"]
            raise AssertionError("mock only applies to synthetic frozen-named APKs")
        return mock.patch.object(GATE, "sha256", side_effect=digest)

    def check_cloud(self):
        return GATE.validate_cloud_report(self.report, self.locals["base"], self.locals["candidate"])

    def run_main(self, *, frozen_sha=True):
        args = ["--base-dir", str(self.dirs["base"]), "--candidate-dir", str(self.dirs["candidate"]),
                "--cloud-preflight-json", str(self.cloud_path), "--output", str(self.output)]
        patch = self.frozen_sha_mock() if frozen_sha else contextlib.nullcontext()
        with patch, contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return GATE.main(args)

    def test_mock_01_frozen_constants_are_exact_145_and_149(self):
        expected = {
            "base": ("xyu-ime-0.3.145.apk", "0.3.145", 145,
                     "9c0df3e86eeb31e5d7646957089f18f0ee86f409",
                     "2810845541a0fbaf80ad9d3c0f7a9da1570695fdcdac95a60c88d8f8418c6804"),
            "candidate": ("xyu-ime-0.3.149.apk", "0.3.149", 149,
                          "42a230e4d41d787612e4c64bd5be75d5ad9f81cd",
                          "7ef1f6d27298cd3d7951bf52672cb11780579e8489d740bfce308a8bd671f2d6"),
        }
        for kind, values in expected.items():
            self.assertEqual(tuple(GATE.EXPECTED[kind][field] for field in
                                   ("apk", "version_name", "version_code", "commit", "sha256")), values)
        self.assertEqual(GATE.PACKAGE_NAME, "org.thanatophilia.xyuime")
        self.assertEqual(GATE.CERTIFICATE_SHA256,
                         "fa62895fa256fb286acb6546a1663331af42fa08abac4c1ce3fa1645ee90d7d0")

    def test_mock_02_cli_pass_is_static_plan_only(self):
        self.assertEqual(self.run_main(), 0)
        result = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual(result["result"], "PASS")
        for field in ("device_actions", "release_data_access", "live_signature_verified", "device_gate_satisfied"):
            self.assertIs(result[field], False)
        self.assertFalse(result["cloud_preflight"]["report_authenticated"])
        self.assertIn("original real", result["cloud_preflight"]["signer_evidence"])
        self.assertEqual(result["state_machine"]["status"], "PLAN_ONLY_NOT_EXECUTED")
        self.assertTrue(all(item["result"] == "NOT_RUN" for item in result["state_machine"]["stages"]))
        self.assertLess(sum(path.stat().st_size for root in self.dirs.values()
                            for path in root.iterdir()), 20000)

    def test_mock_03_real_sha_function_and_unmocked_synthetic_rejected(self):
        apk = self.dirs["base"] / GATE.EXPECTED["base"]["apk"]
        self.assertEqual(GATE.sha256(apk), hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertEqual(self.run_main(frozen_sha=False), 2)
        self.assertFalse(self.output.exists())

    def test_mock_04_wrong_149_bytes_despite_correct_sums_rejected(self):
        with mock.patch.object(GATE, "sha256", return_value="0" * 64):
            with self.assertRaisesRegex(GATE.GateError, "byte hash"):
                GATE.check_artifact("candidate", self.dirs["candidate"])

    def test_mock_05_wrong_145_hash_rejected(self):
        apk = copy.deepcopy(self.locals["base"]["apk"])
        apk["sha256"] = "0" * 64
        with self.assertRaises(GATE.GateError):
            GATE.validate_frozen("base", self.locals["base"]["build"], apk)

    def test_mock_06_no_replacement_candidate_name_code_commit(self):
        for field, value in (("version_name", "0.3.151"), ("version_code", 151),
                             ("commit", "0" * 40)):
            with self.subTest(field=field):
                build = {**self.locals["candidate"]["build"], field: value}
                with self.assertRaises(GATE.GateError):
                    GATE.validate_frozen("candidate", build, self.locals["candidate"]["apk"])
        apk = {**self.locals["candidate"]["apk"], "path": "xyu-ime-0.3.151.apk"}
        with self.assertRaises(GATE.GateError):
            GATE.validate_frozen("candidate", self.locals["candidate"]["build"], apk)

    def test_mock_07_exact_sums_parser_accepts_text_binary_and_other_files(self):
        digest = "a" * 64
        self.assertEqual(GATE.parse_sums(f"{digest}  app.apk\n{digest.upper()} *README.md\n"),
                         {"app.apk": digest, "README.md": digest})

    def test_mock_08_sums_prefix_garbage_paths_and_duplicates_rejected(self):
        digest = "a" * 64
        for text in (f"{digest}garbage  app.apk", f"{digest} app.apk", "",
                     f"{digest}  ../app.apk", f"{digest}  ./app.apk", f"{digest}  /app.apk",
                     f"{digest}  app.apk ", f"{digest}  app.apk\n{digest} *app.apk\n",
                     f"{digest}  app.apk\n\n", f"{digest}  app.apk\nnot a record"):
            with self.subTest(text=text), self.assertRaises(GATE.GateError):
                GATE.parse_sums(text)

    def test_mock_09_wrong_sums_and_phantom_extra_apk_rejected(self):
        root = self.dirs["candidate"]
        expected = GATE.EXPECTED["candidate"]
        for text in (f"{'0' * 64}  {expected['apk']}\n",
                     f"{expected['sha256']}  {expected['apk']}\n{'1' * 64}  other.apk\n"):
            (root / "SHA256SUMS.txt").write_text(text, encoding="utf-8")
            with self.subTest(text=text), self.assertRaises(GATE.GateError):
                GATE.check_artifact("candidate", root)

    def test_mock_10_build_duplicates_and_malformed_fields_rejected(self):
        valid = self.build_text("candidate")
        for text in (valid + "Commit: " + "0" * 40, valid + "Version: 0.3.149 (149)",
                     valid + "Built UTC: duplicate", valid.replace("Version: ", "Version:"),
                     valid.replace("(149)", "(0149)"), valid.replace("Commit:", " Commit:"),
                     "Version: 0.3.149 (149)\n", valid + "unparsed garbage"):
            with self.subTest(text=text), self.assertRaises(GATE.GateError):
                GATE.parse_build_text(text)

    def test_mock_11_extra_candidate_apk_even_nested_rejected(self):
        root = self.dirs["candidate"]
        nested = root / "nested"
        nested.mkdir()
        (nested / "replacement.APK").write_bytes(b"not allowed")
        with self.assertRaisesRegex(GATE.GateError, "exactly"):
            GATE.check_artifact("candidate", root)

    def test_mock_12_unrelated_artifact_files_allowed(self):
        root = self.dirs["candidate"]
        (root / "README.md").write_text("allowed", encoding="utf-8")
        (root / "screenshots").mkdir()
        (root / "screenshots" / "screen.png").write_bytes(b"synthetic image")
        with self.frozen_sha_mock():
            self.assertEqual(GATE.check_artifact("candidate", root)["apk"]["sha256"],
                             GATE.EXPECTED["candidate"]["sha256"])

    def test_mock_13_artifact_file_directory_and_ancestor_symlinks_rejected(self):
        root = self.dirs["candidate"]
        link = self.root / "linked-candidate"
        link.symlink_to(root, target_is_directory=True)
        with self.assertRaises(GATE.GateError):
            GATE.check_artifact("candidate", link)
        (root / "linked-readme").symlink_to(self.cloud_path)
        with self.assertRaises(GATE.GateError):
            GATE.check_artifact("candidate", root)
        with self.assertRaises(GATE.GateError):
            GATE.require_path(link / GATE.EXPECTED["candidate"]["apk"])

    def test_mock_14_cloud_forged_package_rejected(self):
        for kind in ("base", "candidate"):
            with self.subTest(kind=kind):
                self.report[kind]["package_name"] = "forged.package"
                with self.assertRaises(GATE.GateError):
                    self.check_cloud()
                self.report[kind]["package_name"] = GATE.PACKAGE_NAME

    def test_mock_15_cloud_exact_sole_cert_rejected_if_forged_or_malformed(self):
        for digest in ("0" * 64, GATE.CERTIFICATE_SHA256 + "0", "fa:62", [GATE.CERTIFICATE_SHA256],
                       GATE.CERTIFICATE_SHA256.upper(), None):
            with self.subTest(digest=digest):
                self.report["candidate"]["certificate_sha256"] = digest
                with self.assertRaises(GATE.GateError):
                    self.check_cloud()

    def test_mock_16_cloud_forged_assets_even_equal_on_both_rejected(self):
        for kind in ("base", "candidate"):
            self.report[kind]["rime_asset_sha256"][GATE.ASSETS[0]] = "0" * 64
        with self.assertRaises(GATE.GateError):
            self.check_cloud()

    def test_mock_17_cloud_names_codes_hashes_abis_data_and_path_rejected(self):
        for kind in ("base", "candidate"):
            for field, value in (("version_name", "0.3.151"), ("version_code", 151),
                                 ("version_code", str(GATE.EXPECTED[kind]["version_code"])),
                                 ("sha256", "0" * 64), ("path", "other.apk"),
                                 ("abis", ["x86_64"]), ("data_versions", ["new-data"])):
                with self.subTest(kind=kind, field=field):
                    old = self.report[kind][field]
                    self.report[kind][field] = value
                    with self.assertRaises(GATE.GateError):
                        self.check_cloud()
                    self.report[kind][field] = old

    def test_mock_18_cloud_missing_false_numeric_flags_rejected(self):
        for flag in ("same_package_and_signer", "version_code_increased", "rime_assets_unchanged", "debug_probe_absent"):
            for value in (None, False, 1, "true"):
                with self.subTest(flag=flag, value=value):
                    self.report["comparison"].pop(flag, None)
                    if value is not None:
                        self.report["comparison"][flag] = value
                    with self.assertRaises(GATE.GateError):
                        self.check_cloud()
            self.report["comparison"][flag] = True

    def test_mock_19_cloud_wrong_expected_data_result_mode_rejected(self):
        for key, value in (("result", "FAIL"), ("mode", "resource-bump")):
            old = self.report[key]
            self.report[key] = value
            with self.assertRaises(GATE.GateError):
                self.check_cloud()
            self.report[key] = old
        del self.report["comparison"]["expected_candidate_data_version"]
        with self.assertRaises(GATE.GateError):
            self.check_cloud()

    def test_mock_20_optional_signer_count_must_be_one_not_bool(self):
        for count in (0, 2, True, "1"):
            self.report["base"]["signer_count"] = count
            with self.subTest(count=count), self.assertRaises(GATE.GateError):
                self.check_cloud()
        self.report["base"]["signer_count"] = 1
        self.assertFalse(self.check_cloud()["live_signature_verified"])

    def test_mock_21_duplicate_cloud_json_fields_rejected(self):
        self.cloud_path.write_text('{"result":"FAIL","result":"PASS"}', encoding="utf-8")
        with self.assertRaisesRegex(GATE.GateError, "duplicate JSON"):
            GATE.check_cloud_report(self.cloud_path, self.locals["base"], self.locals["candidate"])

    def test_mock_22_missing_asset_abi_jni_dex_rejected(self):
        path = self.dirs["candidate"] / GATE.EXPECTED["candidate"]["apk"]
        for omitted in (GATE.ASSETS[0], "lib/x86_64/libxyu_rime.so", "classes.dex"):
            self.write_zip(path, omit=(omitted,))
            with self.subTest(omit=omitted), self.assertRaises(GATE.GateError):
                GATE.inspect_apk(path)
        self.write_zip(path, extra=(("lib/armeabi-v7a/extra.so", b"bad ABI"),))
        with self.assertRaises(GATE.GateError):
            GATE.inspect_apk(path)

    def test_mock_23_debug_probe_in_dex_or_native_rejected(self):
        path = self.dirs["candidate"] / GATE.EXPECTED["candidate"]["apk"]
        for options in ({"dex": GATE.PROBE}, {"native": GATE.PROBE}):
            self.write_zip(path, **options)
            with self.subTest(options=options), self.assertRaises(GATE.GateError):
                GATE.inspect_apk(path)

    def test_mock_24_missing_or_extra_data_version_rejected(self):
        path = self.dirs["candidate"] / GATE.EXPECTED["candidate"]["apk"]
        for dex in (b"none", GATE.DATA_VERSION.encode() + b"\x00pinyin-simp-forged\x00"):
            self.write_zip(path, dex=dex)
            with self.frozen_sha_mock(), self.assertRaises(GATE.GateError):
                GATE.check_artifact("candidate", self.dirs["candidate"])

    def test_mock_25_duplicate_zip_members_and_archive_symlinks_rejected(self):
        path = self.dirs["candidate"] / GATE.EXPECTED["candidate"]["apk"]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            self.write_zip(path, extra=((GATE.ASSETS[0], b"shadow"),))
        with self.assertRaises(GATE.GateError):
            GATE.inspect_apk(path)
        entry = zipfile.ZipInfo("link")
        entry.create_system = 3
        entry.external_attr = 0o120777 << 16
        self.write_zip(path, extra=((entry, b"destination"),))
        with self.assertRaises(GATE.GateError):
            GATE.inspect_apk(path)

    def test_mock_26_stale_pass_removed_before_failed_run(self):
        self.assertEqual(self.run_main(), 0)
        self.report["candidate"]["package_name"] = "forged"
        self.save_report()
        self.assertEqual(self.run_main(), 2)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob(".wp0-*")))

    def test_mock_27_malformed_json_and_missing_input_fail_without_stale_pass(self):
        self.cloud_path.write_text("[]", encoding="utf-8")
        self.output.write_text('{"result":"PASS"}', encoding="utf-8")
        self.assertEqual(self.run_main(), 2)
        self.assertFalse(self.output.exists())
        self.cloud_path.unlink()
        self.assertEqual(self.run_main(), 2)
        self.assertFalse(self.output.exists())

    def test_mock_28_output_symlink_refused_without_modifying_target(self):
        target = self.root / "keep.txt"
        target.write_text("unchanged", encoding="utf-8")
        self.output.symlink_to(target)
        self.assertEqual(self.run_main(), 2)
        self.assertEqual(target.read_text(encoding="utf-8"), "unchanged")

    def test_mock_29_output_must_not_destroy_inputs_or_artifact(self):
        self.output = self.dirs["candidate"] / "BUILD.txt"
        before = self.output.read_bytes()
        self.assertEqual(self.run_main(), 2)
        self.assertEqual(self.output.read_bytes(), before)
        self.output = self.cloud_path
        before = self.output.read_bytes()
        self.assertEqual(self.run_main(), 2)
        self.assertEqual(self.output.read_bytes(), before)

    def test_mock_30_state_machine_order_and_both_independent_repeats(self):
        history = []
        for stage in GATE.STAGES:
            self.assertEqual(GATE.next_stage(history), stage)
            history.append({"stage": stage, "result": "PASS"})
        self.assertIsNone(GATE.next_stage(history))
        for repeat in ("repeat-01", "repeat-02"):
            for checkpoint in ("DEVICE_GATE", "G2", "G3", "G4", *GATE.CHECKPOINTS):
                self.assertIn(f"{repeat}/{checkpoint}", GATE.STAGES)
        self.assertEqual(set(GATE.CHECKPOINTS), {f"T{i}" for i in range(8)})

    def test_mock_31_state_machine_fail_stops_and_skips_or_retries_rejected(self):
        failed = [{"stage": "G1", "result": "FAIL"}]
        self.assertIsNone(GATE.next_stage(failed))
        for history in ([{"stage": "repeat-01/T0", "result": "PASS"}],
                        failed + [{"stage": "repeat-01/DEVICE_GATE", "result": "PASS"}],
                        [{"stage": "G1", "result": "NOT_RUN"}],
                        [{"stage": "G1", "result": "PASS"}] * 2):
            with self.subTest(history=history), self.assertRaises(GATE.GateError):
                GATE.next_stage(history)


    def test_mock_32_walk_errors_and_atomic_write_errors_fail_closed(self):
        self.output.write_text('{"result":"PASS"}', encoding="utf-8")
        with mock.patch.object(GATE.os, "walk", side_effect=OSError("unreadable tree")):
            self.assertEqual(self.run_main(), 2)
        self.assertFalse(self.output.exists())
        with mock.patch.object(GATE.os, "replace", side_effect=OSError("cannot publish")):
            self.assertEqual(self.run_main(), 2)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob(".wp0-*")))

    def test_mock_33_cloud_symlink_and_output_hardlink_refused(self):
        alias = self.root / "cloud-link.json"
        alias.symlink_to(self.cloud_path)
        with self.assertRaises(GATE.GateError):
            GATE.check_cloud_report(alias, self.locals["base"], self.locals["candidate"])
        self.output.hardlink_to(self.cloud_path)
        try:
            before = self.cloud_path.read_bytes()
            self.assertEqual(self.run_main(), 2)
            self.assertEqual(self.cloud_path.read_bytes(), before)
        finally:
            # Explicit unlink also works under PRoot's hard-link emulation.
            self.output.unlink(missing_ok=True)
            alias.unlink(missing_ok=True)

    def test_mock_34_mutually_different_local_assets_rejected(self):
        self.locals["candidate"]["apk"]["assets"][GATE.ASSETS[0]] = "0" * 64
        self.report["candidate"]["rime_asset_sha256"][GATE.ASSETS[0]] = "0" * 64
        with self.assertRaisesRegex(GATE.GateError, "assets differ"):
            self.check_cloud()

    def test_mock_35_symlink_then_parent_cannot_bypass_path_check(self):
        alias = self.root / "directory-alias"
        alias.symlink_to(self.dirs["candidate"], target_is_directory=True)
        path = alias / ".." / "cloud.json"
        with self.assertRaises(GATE.GateError):
            GATE.require_path(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
