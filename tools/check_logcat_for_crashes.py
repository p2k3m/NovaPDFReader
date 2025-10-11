#!/usr/bin/env python3
"""Scan an Android logcat dump for crash or ANR signatures."""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
import zipfile
from typing import Iterable, List, Pattern, Sequence, Tuple


def _build_crash_signatures(package_name: str) -> List[Tuple[Pattern[str], str]]:
    escaped = re.escape(package_name)
    return [
        (
            re.compile(rf"ANR in {escaped}"),
            f"Detected Application Not Responding dialog for {package_name} during instrumentation tests",
        ),
        (
            re.compile(rf"Application is not responding: Process {escaped}"),
            f"Detected system level 'Application is not responding' warning for {package_name}",
        ),
        (
            re.compile(rf"FATAL EXCEPTION: .*Process: {escaped}"),
            f"Detected fatal crash in {package_name} during instrumentation tests",
        ),
        (
            re.compile(
                rf"E AndroidRuntime: FATAL EXCEPTION[\s\S]+?Process:\s+{escaped}",
                re.MULTILINE,
            ),
            "AndroidRuntime reported a fatal exception while instrumentation tests were running",
        ),
        (
            re.compile(rf"Fatal signal \d+ .*? \(SIG[A-Z]+\).*?{escaped}"),
            f"Detected native crash (fatal signal) for {package_name} during instrumentation tests",
        ),
        (
            re.compile(rf"Process {escaped} has died"),
            f"System server logged that {package_name} process died during instrumentation tests",
        ),
        (
            re.compile(rf"Force finishing activity {escaped}"),
            "Activity manager force-finished NovaPDF Reader during instrumentation tests",
        ),
        (
            re.compile(r"Fatal signal \d+ .*?\(SIGSEGV\)", re.IGNORECASE),
            "Detected fatal native crash (SIGSEGV) during instrumentation tests",
        ),
    ]


def _find_issues(contents: str, signatures: Iterable[Tuple[Pattern[str], str]]) -> List[str]:
    return [message for pattern, message in signatures if pattern.search(contents)]


def _iter_log_sources(path: pathlib.Path) -> Iterable[Tuple[str, str]]:
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                lower_name = entry.filename.lower()
                if not lower_name.endswith((".txt", ".log")):
                    continue
                try:
                    data = archive.read(entry)
                except KeyError:
                    continue
                try:
                    text = data.decode("utf-8", errors="ignore")
                except Exception:
                    continue
                yield (f"{path}::{entry.filename}", text)
    else:
        yield (str(path), path.read_text(encoding="utf-8", errors="ignore"))


def _scan_logs_for_issues(
    paths: Sequence[pathlib.Path], signatures: Iterable[Tuple[Pattern[str], str]]
) -> List[str]:
    issues: List[str] = []
    seen = set()
    for path in paths:
        if not path.exists():
            issues.append(f"Unable to locate captured log at {path}")
            continue

        for source, text in _iter_log_sources(path):
            for message in _find_issues(text, signatures):
                formatted = f"{message} (source: {source})"
                if formatted in seen:
                    continue
                seen.add(formatted)
                issues.append(formatted)

    return issues


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "logs",
        type=pathlib.Path,
        nargs="*",
        help="Paths to logcat or bugreport files to inspect (default: logcat-after-tests.txt)",
    )
    parser.add_argument(
        "--package",
        default=os.environ.get("PACKAGE_NAME", "com.novapdf.reader"),
        help="Android application package name to scan for (defaults to $PACKAGE_NAME or com.novapdf.reader)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    paths = args.logs or [pathlib.Path("logcat-after-tests.txt")]
    issues = _scan_logs_for_issues(paths, _build_crash_signatures(args.package))

    if issues:
        for message in issues:
            print(f"::error::{message}")
        return 1

    inspected = ", ".join(str(path) for path in paths)
    print(f"Logs ({inspected}) are free from ANR/crash signatures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
