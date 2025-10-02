#!/usr/bin/env python3
"""Scan an Android logcat dump for crash or ANR signatures."""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
from typing import Iterable, List, Pattern, Tuple


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
            re.compile(r"E AndroidRuntime: FATAL EXCEPTION"),
            "AndroidRuntime reported a fatal exception while instrumentation tests were running",
        ),
        (
            re.compile(rf"Fatal signal \\d+ .*? \\(SIG[A-Z]+\\).*?{escaped}"),
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
    ]


def _find_issues(contents: str, signatures: Iterable[Tuple[Pattern[str], str]]) -> List[str]:
    return [message for pattern, message in signatures if pattern.search(contents)]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "logcat",
        type=pathlib.Path,
        nargs="?",
        default=pathlib.Path("logcat-after-tests.txt"),
        help="Path to the logcat dump to inspect (default: logcat-after-tests.txt)",
    )
    parser.add_argument(
        "--package",
        default=os.environ.get("PACKAGE_NAME", "com.***pdf.reader"),
        help="Android application package name to scan for (defaults to $PACKAGE_NAME or com.***pdf.reader)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if not args.logcat.exists():
        print(f"::error::Unable to locate captured logcat at {args.logcat}")
        return 1

    contents = args.logcat.read_text(encoding="utf-8", errors="ignore")
    issues = _find_issues(contents, _build_crash_signatures(args.package))

    if issues:
        for message in issues:
            print(f"::error::{message}")
        return 1

    print("Logcat is free from ANR/crash signatures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
