#!/usr/bin/env python3
"""Pure logical rejection tests for allowlisted G1 evidence, no Android device."""
import copy
import importlib.util
import json
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("g1", Path(__file__).with_name("d6-a-g1-report.py"))
g1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g1)
RUN = "g1-test-1"
EDITOR = {"run": RUN, "phase": "calibrate", "result": "PASS", "mechanism_assertions": 7,
          "scope": "synthetic editor mechanism only; not release IME input", "process": "12:345"}
OBSERVER = {
    "run": RUN, "phase": "calibrate", "result": "PASS", "fixture_id": "synthetic-pinyin-01",
    "sdk": 36, "abi": "x86_64", "process": "13:346", "negative_control_commits": 0,
    "negative_db_sha256": "a" * 64, "positive_control_commits": 3,
    "positive_db_sha256": "b" * 64, "reading": "ni hao ni hao", "synthetic_selections": 3,
    "missing_db": "MISSING_DB", "symlink_db": "INVALID_COPY", "wrong_digest": "COPY_MISMATCH",
    "missing_manifest": "INCOMPLETE_DB", "truncated_manifest": "CORRUPT_DB", "missing_wal": "INCOMPLETE_DB",
    "replay_and_source_immutability": "PASS", "native_finalized_before_copy": True,
    "scope": "synthetic calibration only; work-copy WAL recovery is not source flush evidence",
}

class ReportTests(unittest.TestCase):
    def test_real_success_required_not_empty_zero_exit(self):
        self.assertTrue(g1.instrumentation_ok("\nOK (1 test)\n"))
        for value in ("", "OK (0 tests)", "OK (2 tests)", "OK (1 test)\nFAILURES!!!",
                      "OK (1 test)\nINSTRUMENTATION_FAILED", "OK (1 test)\nshortMsg=crashed"):
            self.assertFalse(g1.instrumentation_ok(value))
    def test_editor_exact_schema(self):
        self.assertEqual(EDITOR, g1.validate_report("editor", EDITOR, RUN))
        for key, value in (("mechanism_assertions", 0), ("phase", "T0"), ("result", "FAIL"), ("run", "old")):
            with self.assertRaises(ValueError):
                g1.validate_report("editor", {**EDITOR, key: value}, RUN)
    def test_extra_fields_not_allowed(self):
        for kind, report in (("editor", EDITOR), ("observer", OBSERVER)):
            with self.assertRaises(ValueError):
                g1.validate_report(kind, {**report, "database_text": "synthetic"}, RUN)
    def test_observer_reading_removed_from_public_result(self):
        public = g1.validate_report("observer", OBSERVER, RUN)
        self.assertNotIn("reading", public)
        self.assertTrue(public["reading_matches_fixed_fixture"])
        self.assertNotIn("ni hao", json.dumps(public))
    def test_zero_error_or_fake_positive_rejected(self):
        for key, value in (("negative_control_commits", -1), ("positive_control_commits", 0),
                           ("negative_control_commits", False), ("positive_control_commits", True),
                           ("reading", "unknown"), ("replay_and_source_immutability", "FAIL"),
                           ("native_finalized_before_copy", False), ("positive_db_sha256", "a"*64)):
            with self.assertRaises(ValueError):
                g1.validate_report("observer", {**OBSERVER, key: value}, RUN)
    def test_all_failure_controls_mandatory(self):
        for key in ("missing_db", "missing_manifest", "missing_wal", "symlink_db", "wrong_digest", "truncated_manifest"):
            report = copy.deepcopy(OBSERVER)
            report[key] = "PASS"
            with self.assertRaises(ValueError):
                g1.validate_report("observer", report, RUN)

if __name__ == "__main__":
    unittest.main(verbosity=2)
