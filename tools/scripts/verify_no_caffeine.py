#!/usr/bin/env python3
"""Fail the build if Caffeine artifacts sneak back into build outputs.

The script scans Gradle build directories for any references to the
`com.github.benmanes.caffeine` package or file paths. It is intended to run in CI
so we can surface a clear error message before the app ever launches on a
restricted Android runtime.
"""

from __future__ import annotations

import argparse
import os
import sys
import textwrap
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List

FORBIDDEN_TOKENS = (
    "com.github.benmanes.caffeine",
    "com/github/benmanes/caffeine",
    "CaffeineBitmapCache",
)

TEXT_SUFFIXES = {
    ".txt",
    ".kts",
    ".kt",
    ".java",
    ".gradle",
    ".gradle.kts",
    ".xml",
    ".json",
    ".properties",
    ".cfg",
    ".conf",
    ".ini",
    ".pro",
    ".rc",
    ".csv",
    ".list",
    ".map",
    ".mf",
    ".module",
    ".version",
    ".yaml",
    ".yml",
    ".proguard",
    ".sha1",
    ".sha256",
    ".log",
}

ARCHIVE_SUFFIXES = {
    ".aar",
    ".apk",
    ".ap_",
    ".jar",
    ".zip",
}

EXCLUDED_DIR_NAMES = {"reports", "intermediates", "tmp", "generated", "kotlin", "ksp"}


@dataclass
class Finding:
    path: Path
    detail: str


def iter_build_directories(root: Path) -> Iterable[Path]:
    for candidate in root.rglob("build"):
        if not candidate.is_dir():
            continue
        # Skip Gradle's own caches.
        if any(part.startswith(".") for part in candidate.parts):
            continue
        if ".gradle" in candidate.parts:
            continue
        yield candidate


def scan_text_file(path: Path) -> List[Finding]:
    try:
        with path.open("r", encoding="utf-8", errors="ignore") as handle:
            data = handle.read()
    except OSError:
        return []

    findings: List[Finding] = []
    for token in FORBIDDEN_TOKENS:
        if token in data:
            findings.append(Finding(path=path, detail=f"token '{token}'"))
    return findings


def scan_archive(path: Path) -> List[Finding]:
    findings: List[Finding] = []
    try:
        with zipfile.ZipFile(path) as archive:
            for entry in archive.namelist():
                lower_entry = entry.lower()
                if any(fragment in lower_entry for fragment in FORBIDDEN_TOKENS):
                    findings.append(Finding(path=path, detail=f"archive entry '{entry}'"))
    except zipfile.BadZipFile:
        return []
    except OSError:
        return []
    return findings


def scan_directory(directory: Path) -> List[Finding]:
    findings: List[Finding] = []
    for item in directory.rglob("*"):
        if item.is_dir():
            if item.name.lower().startswith("tmp"):
                continue
            if item.name in EXCLUDED_DIR_NAMES:
                continue
            continue

        lower_name = item.name.lower()
        if "caffeine" in lower_name:
            findings.append(Finding(path=item, detail="filename contains 'caffeine'"))
            continue

        suffix = item.suffix.lower()
        if suffix in ARCHIVE_SUFFIXES:
            findings.extend(scan_archive(item))
            continue

        if suffix in TEXT_SUFFIXES:
            findings.extend(scan_text_file(item))
            continue

    return findings


def format_findings(findings: Iterable[Finding]) -> str:
    bullet_list = "\n".join(
        f"- {finding.path}: {finding.detail}" for finding in findings
    )
    return textwrap.dedent(
        f"""\
        Caffeine artifacts detected in Gradle build outputs. Remove the dependency before
        rebuilding. Findings:\n{bullet_list}
        """
    ).strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Verify Caffeine is absent from build outputs")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path.cwd(),
        help="Workspace root to scan (defaults to current working directory)",
    )
    parser.add_argument(
        "--github",
        action="store_true",
        help="Emit GitHub Actions annotations for easier triage",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root: Path = args.root.resolve()

    build_dirs = sorted(iter_build_directories(root))
    if not build_dirs:
        print("No Gradle build directories found; skipping Caffeine scan.")
        return 0

    all_findings: List[Finding] = []
    for directory in build_dirs:
        all_findings.extend(scan_directory(directory))

    if all_findings:
        message = format_findings(all_findings)
        if args.github:
            for finding in all_findings:
                print(
                    f"::error file={finding.path}::Forbidden Caffeine reference found ({finding.detail})",
                    file=sys.stderr,
                )
        print(message, file=sys.stderr)
        return 1

    print("Caffeine cache dependency not detected in build outputs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
