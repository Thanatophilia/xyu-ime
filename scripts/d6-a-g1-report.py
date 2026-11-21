#!/usr/bin/env python3
"""Parse one private instrumentation result and copy only allowlisted G1 metadata."""
from __future__ import annotations
import argparse
import json
import re
import sys
from pathlib import Path


def instrumentation_ok(text: str) -> bool:
    return bool(re.search(r"^OK \(1 test\)\s*$", text, re.MULTILINE)) and not any(
        token in text for token in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "shortMsg=")
    )


def validate_report(kind: str, report: dict, run: str) -> dict:
    common = {"result", "run", "phase", "scope", "process"}
    if not isinstance(report, dict) or report.get("result") != "PASS" or report.get("run") != run:
        raise ValueError("invalid_result_or_run")
    if report.get("phase") != "calibrate" or not re.fullmatch(r"\d+:\d+", str(report.get("process", ""))):
        raise ValueError("invalid_phase_or_process")
    if kind == "editor":
        if set(report) != common | {"mechanism_assertions"} or type(report["mechanism_assertions"]) is not int or report["mechanism_assertions"] != 7:
            raise ValueError("invalid_editor_calibration_schema")
        if report["scope"] != "synthetic editor mechanism only; not release IME input":
            raise ValueError("wrong_editor_scope")
        return report
    controls = {
        "missing_db": "MISSING_DB", "symlink_db": "INVALID_COPY",
        "wrong_digest": "COPY_MISMATCH", "missing_manifest": "INCOMPLETE_DB",
        "truncated_manifest": "CORRUPT_DB", "missing_wal": "INCOMPLETE_DB",
    }
    fields = common | set(controls) | {"fixture_id", "sdk", "abi", "negative_control_commits",
        "negative_db_sha256", "positive_control_commits", "positive_db_sha256", "reading",
        "synthetic_selections", "replay_and_source_immutability", "native_finalized_before_copy"}
    if set(report) != fields:
        raise ValueError("invalid_observer_schema")
    if report["fixture_id"] != "synthetic-pinyin-01" or report["sdk"] != 36 or report["abi"] != "x86_64":
        raise ValueError("invalid_observer_fixture")
    for name, expected in controls.items():
        if report[name] != expected:
            raise ValueError("observer_error_control_not_passed")
    if type(report["negative_control_commits"]) is not int or report["negative_control_commits"] != 0:
        raise ValueError("invalid_negative_control")
    if type(report["positive_control_commits"]) is not int or report["positive_control_commits"] <= 0:
        raise ValueError("invalid_positive_control")
    if report["synthetic_selections"] != 3 or report["reading"] != "ni hao ni hao":
        raise ValueError("invalid_fixed_reading_or_selections")
    for name in ("negative_db_sha256", "positive_db_sha256"):
        if not isinstance(report[name], str) or not re.fullmatch(r"[0-9a-f]{64}", report[name]):
            raise ValueError("invalid_copy_digest")
    if (report["negative_db_sha256"] == report["positive_db_sha256"] or
        report["replay_and_source_immutability"] != "PASS" or report["native_finalized_before_copy"] is not True or
        report["scope"] != "synthetic calibration only; work-copy WAL recovery is not source flush evidence"):
        raise ValueError("invalid_immutability_or_scope")
    sanitized = {key: value for key, value in report.items() if key != "reading"}
    sanitized["reading_matches_fixed_fixture"] = True
    return sanitized


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--raw", type=Path, required=True)
    p.add_argument("--kind", choices=("editor", "observer"), required=True)
    p.add_argument("--report", type=Path)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--run", required=True)
    args = p.parse_args()
    args.output.unlink(missing_ok=True)
    try:
        if not instrumentation_ok(args.raw.read_text(errors="replace")):
            raise ValueError("instrumentation_not_one_successful_test")
        if args.report is None:
            print("G1 instrumentation status PASS")
            return 0
        report = validate_report(args.kind, json.loads(args.report.read_text()), args.run)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(f"G1 {args.kind} explicit report PASS")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        args.output.unlink(missing_ok=True)
        code = str(exc)
        if not re.fullmatch(r"[a-zA-Z0-9_]+", code):
            code = "report_io_or_schema_failure"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.with_name(args.output.stem + "-failure.json").write_text(json.dumps({
            "result": "FAIL", "kind": args.kind, "run": args.run, "error": code,
            "scope": "G1 calibration only; release experiments NOT_RUN"
        }, indent=2) + "\n")
        print(f"G1 report rejected: {code}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
