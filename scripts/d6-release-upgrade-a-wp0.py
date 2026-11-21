#!/usr/bin/env python3
"""D6-A WP0 exact input and state-machine preflight.

This gate checks frozen artifact bytes and sanitized cloud metadata only.
It never installs an APK, starts adb, or reads an Android data directory.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import zipfile
from pathlib import Path

ASSETS = (
    "assets/rime/default.yaml",
    "assets/rime/xyu_pinyin.schema.yaml",
    "assets/rime/pinyin_simp.dict.yaml",
)
ABIS = {"arm64-v8a", "x86_64"}
PROBE = b"RimeTestProbe"
DATA_VERSION = "pinyin-simp-0c6861e-3"
PACKAGE_NAME = "org.thanatophilia.xyuime"
CERTIFICATE_SHA256 = "fa62895fa256fb286acb6546a1663331af42fa08abac4c1ce3fa1645ee90d7d0"
# Frozen by docs/d6-release-upgrade-a-taskbook.md section 2.1, not CLI overrides.
EXPECTED = {
    "base": {
        "artifact": "xyu-ime-0.3.145",
        "apk": "xyu-ime-0.3.145.apk",
        "version_name": "0.3.145",
        "version_code": 145,
        "commit": "9c0df3e86eeb31e5d7646957089f18f0ee86f409",
        "sha256": "2810845541a0fbaf80ad9d3c0f7a9da1570695fdcdac95a60c88d8f8418c6804",
    },
    "candidate": {
        "artifact": "xyu-ime-0.3.149",
        "apk": "xyu-ime-0.3.149.apk",
        "version_name": "0.3.149",
        "version_code": 149,
        "commit": "42a230e4d41d787612e4c64bd5be75d5ad9f81cd",
        "sha256": "7ef1f6d27298cd3d7951bf52672cb11780579e8489d740bfce308a8bd671f2d6",
    },
}


class GateError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_path(path: Path, *, directory: bool = False) -> None:
    """Reject symlink components, including a symlinked artifact directory."""
    absolute = path if path.is_absolute() else Path.cwd() / path
    for component in (*reversed(absolute.parents), absolute):
        if component.is_symlink():
            raise GateError(f"symlink is not allowed: {component}")
    mode = path.stat().st_mode
    if not (stat.S_ISDIR(mode) if directory else stat.S_ISREG(mode)):
        raise GateError(f"not a regular {'directory' if directory else 'file'}: {path}")


def parse_build_text(text: str) -> dict:
    values: dict[str, str] = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        if ": " not in line:
            raise GateError("malformed BUILD field")
        key, value = line.split(": ", 1)
        if not key or key != key.strip() or not value or value != value.strip():
            raise GateError("malformed BUILD key/value")
        if key in values:
            raise GateError(f"duplicate BUILD field: {key}")
        values[key] = value
    if any(key not in values for key in ("Commit", "Version")):
        raise GateError("missing BUILD fields")
    if not re.fullmatch(r"[0-9a-f]{40}", values["Commit"]):
        raise GateError("malformed BUILD Commit")
    match = re.fullmatch(r"([^ ]+) \(([1-9][0-9]*)\)", values["Version"])
    if not match:
        raise GateError("malformed BUILD Version")
    return {"commit": values["Commit"], "version_name": match.group(1),
            "version_code": int(match.group(2))}


def parse_build(path: Path) -> dict:
    require_path(path)
    return parse_build_text(path.read_text(encoding="utf-8"))


def parse_sums(text: str) -> dict[str, str]:
    """Parse exact GNU text/binary SHA256SUMS records, never prefix matches."""
    values: dict[str, str] = {}
    for line in text.splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *]([^\r\n]+)", line)
        if not match:
            raise GateError("malformed SHA256SUMS record")
        digest, name = match.groups()
        if (name != name.strip() or "\\" in name or name.startswith("/")
                or any(part in ("", ".", "..") for part in name.split("/"))
                or any(ord(char) < 32 or ord(char) == 127 for char in name)):
            raise GateError("unsafe SHA256SUMS filename")
        if name in values:
            raise GateError(f"duplicate SHA256SUMS filename: {name}")
        values[name] = digest.lower()
    if not values:
        raise GateError("empty SHA256SUMS")
    return values


def validate_frozen(kind: str, build: dict, apk: dict) -> None:
    expected = EXPECTED[kind]
    for field in ("commit", "version_name", "version_code"):
        if type(build.get(field)) is not type(expected[field]) or build[field] != expected[field]:
            raise GateError(f"{kind}: BUILD {field} differs from frozen input")
    if apk.get("path") != expected["apk"] or apk.get("sha256") != expected["sha256"]:
        raise GateError(f"{kind}: APK name/byte hash differs from frozen input")
    if apk.get("data_versions") != [DATA_VERSION]:
        raise GateError(f"{kind}: unexpected DATA_VERSION")
    if apk.get("abis") != sorted(ABIS):
        raise GateError(f"{kind}: unexpected ABIs")
    assets = apk.get("assets")
    if (not isinstance(assets, dict) or set(assets) != set(ASSETS)
            or any(not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
                   for value in assets.values())):
        raise GateError(f"{kind}: invalid asset hashes")


def inspect_apk(path: Path) -> dict:
    require_path(path)
    actual_sha = sha256(path)
    with zipfile.ZipFile(path) as apk:
        entries = apk.infolist()
        names = set(apk.namelist())
        if len(names) != len(entries):
            raise GateError(f"{path.name}: duplicate ZIP member")
        for entry in entries:
            if (stat.S_ISLNK(entry.external_attr >> 16) or entry.flag_bits & 1
                    or entry.filename.startswith("/") or "\\" in entry.filename
                    or ".." in entry.filename.split("/")):
                raise GateError(f"{path.name}: unsafe ZIP member")
        abis = {name.split("/")[1] for name in names
                if name.startswith("lib/") and name.endswith(".so")}
        if abis != ABIS:
            raise GateError(f"{path.name}: unexpected ABIs {sorted(abis)}")
        for abi in ABIS:
            if f"lib/{abi}/libxyu_rime.so" not in names:
                raise GateError(f"{path.name}: missing Rime JNI for {abi}")
        assets = {}
        for name in ASSETS:
            if name not in names:
                raise GateError(f"{path.name}: missing {name}")
            assets[name] = hashlib.sha256(apk.read(name)).hexdigest()
        dex_names = sorted(name for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name))
        if not dex_names:
            raise GateError(f"{path.name}: no DEX")
        dex = b"".join(apk.read(name) for name in dex_names)
        native = b"".join(apk.read(name) for name in names
                           if name.startswith("lib/") and name.endswith(".so"))
        if PROBE in dex or PROBE in native:
            raise GateError(f"{path.name}: debug probe present")
        versions = sorted(set(item.decode("ascii") for item in
                              re.findall(rb"pinyin-simp-[A-Za-z0-9._-]+", dex)))
    return {"path": path.name, "sha256": actual_sha, "abis": sorted(abis),
            "assets": assets, "data_versions": versions}


def walk_error(error: OSError) -> None:
    raise error


def check_artifact(kind: str, root: Path) -> dict:
    expected = EXPECTED[kind]
    require_path(root, directory=True)
    # Reject ambiguity anywhere in the artifact, but allow README/screenshots/logs.
    apks = []
    for parent, dirs, files in os.walk(root, followlinks=False, onerror=walk_error):
        for name in dirs + files:
            entry = Path(parent) / name
            if entry.is_symlink():
                raise GateError(f"symlink in artifact: {entry}")
            if entry.suffix.lower() == ".apk":
                require_path(entry)
                apks.append(entry.relative_to(root).as_posix())
    if apks != [expected["apk"]]:
        raise GateError(f"{kind}: expected exactly the frozen APK, found {sorted(apks)}")
    build = parse_build(root / "BUILD.txt")
    sums_path = root / "SHA256SUMS.txt"
    require_path(sums_path)
    sums = parse_sums(sums_path.read_text(encoding="utf-8"))
    summed_apks = {name: digest for name, digest in sums.items() if name.lower().endswith(".apk")}
    if summed_apks != {expected["apk"]: expected["sha256"]}:
        raise GateError(f"{kind}: SHA256SUMS does not pin sole frozen APK")
    apk_info = inspect_apk(root / expected["apk"])
    validate_frozen(kind, build, apk_info)
    return {"kind": kind, "artifact": expected["artifact"], "build": build,
            "apk": apk_info, "frozen_sha256": expected["sha256"]}


def unique_object(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise GateError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def validate_cloud_report(report: dict, base: dict, candidate: dict) -> dict:
    """Cross-check static metadata; this does NOT execute aapt/apksigner."""
    if not isinstance(report, dict) or report.get("result") != "PASS" or report.get("mode") != "same":
        raise GateError("cloud preflight is not PASS/same")
    for key, local in (("base", base), ("candidate", candidate)):
        validate_frozen(key, local["build"], local["apk"])
        item = report.get(key)
        if not isinstance(item, dict):
            raise GateError(f"cloud report {key} is missing")
        expected = {
            "path": local["apk"]["path"], "sha256": local["apk"]["sha256"],
            "package_name": PACKAGE_NAME, "certificate_sha256": CERTIFICATE_SHA256,
            "version_name": local["build"]["version_name"],
            "version_code": local["build"]["version_code"],
            "abis": local["apk"]["abis"], "data_versions": local["apk"]["data_versions"],
            "rime_asset_sha256": local["apk"]["assets"],
        }
        for field, value in expected.items():
            if type(item.get(field)) is not type(value) or item[field] != value:
                raise GateError(f"cloud report {key} {field} mismatch")
        # The original real report omits signer_count. Do not invent live proof.
        if "signer_count" in item and (type(item["signer_count"]) is not int or item["signer_count"] != 1):
            raise GateError(f"cloud report {key}: expected sole signer")
    if base["apk"]["assets"] != candidate["apk"]["assets"]:
        raise GateError("145/149 Rime assets differ")
    comparison = report.get("comparison")
    if not isinstance(comparison, dict) or not all(comparison.get(name) is True for name in
            ("same_package_and_signer", "version_code_increased",
             "rime_assets_unchanged", "debug_probe_absent")):
        raise GateError("cloud report comparison flags are incomplete")
    if comparison.get("expected_candidate_data_version") != DATA_VERSION:
        raise GateError("cloud report expected DATA_VERSION mismatch")
    return {"result": "PASS", "mode": "same", "scope": "Static historical cloud report cross-check only",
            "live_signature_verified": False, "device_gate_satisfied": False,
            "signer_evidence": "Frozen sole certificate digest matched; signer count/v2 verification rely on original real aapt/apksigner report, not rerun here",
            "report_authenticated": False}


def check_cloud_report(path: Path, base: dict, candidate: dict) -> dict:
    require_path(path)
    report = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    return validate_cloud_report(report, base, candidate)


# This is a frozen execution contract, NOT a device runner or completed evidence.
CHECKPOINTS = {
    "T0": "145 offline input; valid existing target DB, stored commits=0",
    "T1": "Exactly three real target commits; measured C>0; freeze reading/count",
    "T2": "145 cold restart; target count=C, reading unchanged; no further target learning",
    "T3": "145 final cold pre-upgrade baseline; markers and recent symbols S3,S1,S2",
    "T4": "149 installed -r but never started; UID/install time/data path continuous; user bytes equal T3; count=C",
    "T5": "149 first use; no target commit; count=C; markers/recent/transactions valid",
    "T6": "149 first additional cold restart; count=C; offline input and preservation",
    "T7": "149 second additional cold restart; count=C; offline input and preservation",
}
STAGES = ("G1",) + tuple(f"{repeat}/{checkpoint}" for repeat in ("repeat-01", "repeat-02")
                         for checkpoint in ("DEVICE_GATE", "T0", "G2", "T1", "T2", "T3", "G3",
                                            "T4", "T5", "T6", "T7", "G4")) + ("G5",)


def next_stage(history: list[dict]) -> str | None:
    """Pure fail-closed transition contract: ordered PASS prefix, FAIL stops."""
    failed = False
    if len(history) > len(STAGES):
        raise GateError("state history exceeds frozen plan")
    for index, item in enumerate(history):
        if (failed or not isinstance(item, dict) or item.get("stage") != STAGES[index]
                or item.get("result") not in ("PASS", "FAIL")):
            raise GateError("out-of-order, skipped or post-failure state")
        failed = item["result"] == "FAIL"
    return None if failed or len(history) == len(STAGES) else STAGES[len(history)]


def state_machine_plan() -> dict:
    return {"status": "PLAN_ONLY_NOT_EXECUTED", "next_gate": next_stage([]),
            "stages": [{"stage": stage, "result": "NOT_RUN"} for stage in STAGES],
            "checkpoints": dict(CHECKPOINTS),
            "gate_requirements": {
                "G1": "Independent UI fixture and observer zero/positive/error calibration",
                "DEVICE_GATE": "Fresh disposable API36/x86_64 AVD; rerun live safety/identity gates before install",
                "G2": "T0 valid 145 offline negative control; missing/corrupt DB is not zero",
                "G3": "T1-T3 learning, cold persistence, markers/recent order; otherwise no upgrade",
                "G4": "T4-T7 true replacement and preservation without target relearning",
                "G5": "Both independent repeats and regressions/privacy checks PASS",
            },
            "failure_policy": "Stop at first FAIL; all downstream NOT_RUN; no replacement APK or retry-until-pass",
            "cold_snapshot_rule": "No source processes before/after capture; seal original, observe fresh copy only; never restore to source"}


def prepare_output(output: Path, inputs: tuple[Path, ...], roots: tuple[Path, ...]) -> None:
    # Do not unlink an input or write inside an artifact; refuse an existing output
    # on these unsafe paths. For a safe output, remove old PASS before any checks.
    absolute = output if output.is_absolute() else Path.cwd() / output
    for component in (*reversed(absolute.parents), absolute):
        if component.is_symlink():
            raise GateError(f"output symlink is not allowed: {component}")
    if (any(output.resolve() == item.resolve() for item in inputs)
            or any(output.resolve().is_relative_to(root.resolve()) for root in roots)):
        raise GateError("output must not overlap inputs/artifact directories")
    if output.exists():
        require_path(output)
        if any(item.exists() and os.path.samefile(output, item) for item in inputs):
            raise GateError("output is a hard link to an input")
        output.unlink()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-dir", type=Path, required=True)
    parser.add_argument("--candidate-dir", type=Path, required=True)
    parser.add_argument("--cloud-preflight-json", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    temporary = None
    try:
        roots = (args.base_dir, args.candidate_dir)
        inputs = (args.cloud_preflight_json,) + tuple(root / name for root, kind in
                  zip(roots, ("base", "candidate")) for name in
                  ("BUILD.txt", "SHA256SUMS.txt", EXPECTED[kind]["apk"]))
        prepare_output(args.output, inputs, roots)
        base = check_artifact("base", args.base_dir)
        candidate = check_artifact("candidate", args.candidate_dir)
        cloud = check_cloud_report(args.cloud_preflight_json, base, candidate)
        result = {"result": "PASS", "scope": "D6-A WP0 exact artifacts only; no device actions",
                  "base": base, "candidate": candidate, "cloud_preflight": cloud,
                  "device_actions": False, "release_data_access": False,
                  "live_signature_verified": False, "device_gate_satisfied": False,
                  "state_machine": state_machine_plan()}
        rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.output.parent,
                                         prefix=".wp0-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(rendered)
        os.replace(temporary, args.output)
        sys.stdout.write(rendered)
        return 0
    except (OSError, KeyError, ValueError, TypeError, RuntimeError, zipfile.BadZipFile) as exc:
        print(f"D6-A WP0 failed: {exc}", file=sys.stderr)
        return 2
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
