#!/usr/bin/env python3
"""Fail when an Android artifact or generated source retains OFF write credentials."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path
from typing import BinaryIO, Iterable


FORBIDDEN_MARKERS = {
    "retired field OFF_USER_ID": b"OFF_USER_ID",
    "retired field OFF_PASSWORD": b"OFF_PASSWORD",
    "retired field OFF_WRITE_HOST": b"OFF_WRITE_HOST",
}
CHUNK_BYTES = 64 * 1024


def legacy_password(properties_path: Path | None) -> dict[str, bytes]:
    if properties_path is None:
        return {}
    if not properties_path.is_file():
        raise FileNotFoundError(properties_path)
    values: dict[str, bytes] = {}
    for line in properties_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        name, value = stripped.split("=", 1)
        name = name.strip()
        value = value.strip()
        if name == "password" and value:
            values["legacy password value"] = value.encode("utf-8")
    return values


def stream_findings(stream: BinaryIO, needles: dict[str, bytes]) -> set[str]:
    findings: set[str] = set()
    longest = max((len(needle) for needle in needles.values()), default=1)
    overlap = b""
    while chunk := stream.read(CHUNK_BYTES):
        combined = overlap + chunk
        findings.update(
            label for label, needle in needles.items() if label not in findings and needle in combined
        )
        if len(findings) == len(needles):
            break
        overlap = combined[-(longest - 1) :] if longest > 1 else b""
    return findings


def artifact_findings(artifact: Path, needles: dict[str, bytes]) -> set[str]:
    findings: set[str] = set()
    with zipfile.ZipFile(artifact) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            with archive.open(entry) as stream:
                remaining = {label: value for label, value in needles.items() if label not in findings}
                findings.update(stream_findings(stream, remaining))
                if len(findings) == len(needles):
                    break
    return findings


def generated_findings(directory: Path, needles: dict[str, bytes]) -> set[str]:
    findings: set[str] = set()
    if not directory.is_dir():
        raise NotADirectoryError(directory)
    for path in directory.rglob("*"):
        if not path.is_file():
            continue
        with path.open("rb") as stream:
            remaining = {label: value for label, value in needles.items() if label not in findings}
            findings.update(stream_findings(stream, remaining))
    return findings


def verify(
    artifact: Path,
    generated_directory: Path | None = None,
    properties_path: Path | None = None,
) -> set[str]:
    needles = dict(FORBIDDEN_MARKERS)
    needles.update(legacy_password(properties_path))
    return verify_needles(artifact, generated_directory, needles)


def verify_needles(
    artifact: Path, generated_directory: Path | None, needles: dict[str, bytes]
) -> set[str]:
    findings = artifact_findings(artifact, needles)
    if generated_directory is not None:
        findings.update(generated_findings(generated_directory, needles))
    return findings


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("artifact", type=Path, help="APK or AAB to inspect")
    result.add_argument("--generated-dir", type=Path, help="generated BuildConfig source root")
    result.add_argument(
        "--legacy-properties",
        type=Path,
        help="optional ignored legacy property file; its password is checked but never printed",
    )
    return result


def main(arguments: Iterable[str] | None = None) -> int:
    args = parser().parse_args(arguments)
    try:
        legacy_needles = legacy_password(args.legacy_properties)
        needles = dict(FORBIDDEN_MARKERS)
        needles.update(legacy_needles)
        findings = verify_needles(args.artifact, args.generated_dir, needles)
    except (OSError, UnicodeError, zipfile.BadZipFile) as error:
        print(f"artifact verification could not run: {type(error).__name__}", file=sys.stderr)
        return 2
    if findings:
        for label in sorted(findings):
            print(f"forbidden credential material found: {label}", file=sys.stderr)
        return 1
    print("retired OFF credential field names: checked and clean (3 markers)")
    if legacy_needles:
        print("legacy password value: checked and clean")
    elif args.legacy_properties is None:
        print("legacy password value: SKIPPED (no legacy properties supplied)")
    else:
        print("legacy password value: SKIPPED (supplied properties contain no non-empty password)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
