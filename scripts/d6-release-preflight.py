#!/usr/bin/env python3
"""D6 preflight for real release-to-release APK upgrades.

This is a metadata gate only: it does not install APKs or inspect user data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ASSETS = (
    "assets/rime/default.yaml",
    "assets/rime/xyu_pinyin.schema.yaml",
    "assets/rime/pinyin_simp.dict.yaml",
)
EXPECTED_ABIS = {"arm64-v8a", "x86_64"}
VERSION_RE = re.compile(rb"pinyin-simp-[A-Za-z0-9._-]+")
PROBE = b"RimeTestProbe"


class PreflightError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_tool(command: list[str]) -> str:
    result = subprocess.run(command, text=True, capture_output=True, timeout=45, check=False)
    if result.returncode:
        raise PreflightError(f"Tool failed ({Path(command[0]).name}): {result.stderr.strip()}")
    return result.stdout


def parse_signer(certificate: str) -> str:
    if not re.search(r"^Verified using v2 scheme .*: true$", certificate, re.MULTILINE):
        raise PreflightError("APK Signature Scheme v2 is not verified")
    counts = re.findall(r"^Number of signers: (\d+)$", certificate, re.MULTILINE)
    signers = re.findall(r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F:]+)$",
                         certificate, re.MULTILINE)
    if counts != ["1"] or len(signers) != 1 or signers[0][0] != "1":
        raise PreflightError("Release APK must have exactly one signer certificate")
    digest = signers[0][1].replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise PreflightError("Signer certificate SHA-256 must be exactly 64 hex digits")
    return digest


def inspect_apk(path: Path, aapt: str, apksigner: str) -> dict:
    if not path.is_file():
        raise PreflightError(f"APK does not exist: {path}")
    badging = run_tool([aapt, "dump", "badging", str(path)])
    match = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'",
        badging, re.MULTILINE,
    )
    if not match:
        raise PreflightError(f"Cannot read package/version metadata from {path.name}")
    manifest_tree = run_tool([aapt, "dump", "xmltree", str(path), "AndroidManifest.xml"])
    manifest_flags = [
        flag_match.group(1) for line in manifest_tree.splitlines()
        if (flag_match := re.search(
            r"android:(debuggable|testOnly)[^=]*=.*(?:true|0xffffffff)",
            line, re.IGNORECASE,
        ))
    ]
    if manifest_flags:
        raise PreflightError(f"Release manifest enables {', '.join(sorted(set(manifest_flags)))} in {path.name}")
    certificate = run_tool([apksigner, "verify", "--verbose", "--print-certs", str(path)])
    signer_digest = parse_signer(certificate)

    try:
        with zipfile.ZipFile(path) as apk:
            names = set(apk.namelist())
            abis = {name.split("/")[1] for name in names
                    if name.startswith("lib/") and name.endswith(".so") and len(name.split("/")) >= 3}
            if abis != EXPECTED_ABIS:
                raise PreflightError(f"Unexpected native ABI set in {path.name}: {sorted(abis)}")
            for abi in EXPECTED_ABIS:
                if f"lib/{abi}/libxyu_rime.so" not in names:
                    raise PreflightError(f"Missing Rime JNI library for {abi} in {path.name}")
            asset_hashes = {}
            for name in ASSETS:
                if name not in names:
                    raise PreflightError(f"Missing bundled Rime asset {name} in {path.name}")
                asset_hashes[name] = hashlib.sha256(apk.read(name)).hexdigest()
            dex_entries = [name for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
            if not dex_entries:
                raise PreflightError(f"No DEX files in {path.name}")
            dex_data = b"".join(apk.read(name) for name in sorted(dex_entries))
            native_data = b"".join(apk.read(name) for name in sorted(names)
                                     if name.startswith("lib/") and name.endswith(".so"))
            if PROBE in dex_data or PROBE in native_data:
                raise PreflightError(f"Debug-only Rime probe is present in {path.name}")
            versions = sorted(set(item.decode("ascii") for item in VERSION_RE.findall(dex_data)))
    except zipfile.BadZipFile as exc:
        raise PreflightError(f"Invalid APK archive: {path.name}") from exc

    if "application-debuggable" in badging or "application-testOnly" in badging:
        raise PreflightError(f"Debuggable/testOnly APK is not an acceptable release: {path.name}")
    return {
        "path": path.name,
        "sha256": sha256_file(path),
        "package_name": match.group(1),
        "version_code": int(match.group(2)),
        "version_name": match.group(3),
        "certificate_sha256": signer_digest,
        "abis": sorted(abis),
        "rime_asset_sha256": asset_hashes,
        "data_versions": versions,
    }


def validate_pair(baseline: dict, base: dict, candidate: dict,
                  mode: str, resource_version: str | None) -> dict:
    accepted = baseline["accepted_source"]
    expected_identity = {
        "package_name": accepted["package_name"],
        "certificate_sha256": accepted["signer_certificate_sha256"].lower(),
    }
    if base["sha256"] != accepted["apk_sha256"]:
        raise PreflightError("Frozen base APK SHA-256 does not match docs/d6-baseline.json")
    for label, apk in (("base", base), ("candidate", candidate)):
        for field, value in expected_identity.items():
            if apk[field] != value:
                raise PreflightError(f"{label} APK {field} differs from the frozen release identity")
    if base["version_code"] != int(accepted["version_code"]):
        raise PreflightError("Base APK versionCode does not match the frozen baseline")
    if candidate["version_code"] <= base["version_code"]:
        raise PreflightError("Candidate versionCode must be greater than the frozen base")
    if base["data_versions"] != [accepted["data_version"]]:
        raise PreflightError(f"Unexpected DATA_VERSION in frozen APK: {base['data_versions']}")
    if base["rime_asset_sha256"] != candidate["rime_asset_sha256"]:
        raise PreflightError("Rime assets changed; this is not an isolated version-only upgrade test")
    if mode == "same":
        expected = accepted["data_version"]
    elif mode == "resource-bump":
        if not resource_version or not re.fullmatch(r"[A-Za-z0-9._-]+", resource_version):
            raise PreflightError("resource-bump requires a valid --resource-version")
        if resource_version == accepted["data_version"]:
            raise PreflightError("Resource-bump version must differ from the frozen version")
        expected = resource_version
    else:
        raise PreflightError(f"Unsupported upgrade mode: {mode}")
    if candidate["data_versions"] != [expected]:
        raise PreflightError(f"Candidate DATA_VERSION must be exactly {expected!r}; found {candidate['data_versions']}")
    return {
        "result": "PASS",
        "mode": mode,
        "base": base,
        "candidate": candidate,
        "comparison": {
            "same_package_and_signer": True,
            "version_code_increased": True,
            "rime_assets_unchanged": True,
            "expected_candidate_data_version": expected,
            "debug_probe_absent": True,
        },
        "scope": "Artifact preflight only; no installation or user-data preservation claim.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-json", type=Path, required=True)
    parser.add_argument("--base-apk", type=Path, required=True)
    parser.add_argument("--candidate-apk", type=Path, required=True)
    parser.add_argument("--mode", choices=("same", "resource-bump"), required=True)
    parser.add_argument("--resource-version")
    parser.add_argument("--aapt", required=True, help="Android build-tools aapt path")
    parser.add_argument("--apksigner", required=True, help="Android build-tools apksigner path")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        baseline = json.loads(args.baseline_json.read_text(encoding="utf-8"))
        base = inspect_apk(args.base_apk, args.aapt, args.apksigner)
        candidate = inspect_apk(args.candidate_apk, args.aapt, args.apksigner)
        report = validate_pair(baseline, base, candidate, args.mode, args.resource_version)
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        sys.stdout.write(rendered)
        return 0
    except (OSError, KeyError, ValueError, PreflightError, subprocess.TimeoutExpired) as exc:
        print(f"D6 release preflight failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
