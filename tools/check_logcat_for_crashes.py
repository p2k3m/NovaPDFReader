#!/usr/bin/env python3
"""Scan an Android logcat dump for crash or ANR signatures."""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
import zipfile
from dataclasses import dataclass
from typing import Iterable, List, Optional, Pattern, Sequence, Tuple


@dataclass(frozen=True)
class IssueReport:
    message: str
    snippet: str
    source: str


def _build_crash_signatures(package_name: str) -> List[Tuple[Pattern[str], str]]:
    escaped = re.escape(package_name)
    return [
        (
            re.compile(rf"ANR in {escaped}"),
            f"Detected Application Not Responding dialog for {package_name} during instrumentation tests",
        ),
        (
            re.compile(rf"E ActivityManager: ANR in {escaped}"),
            f"ActivityManager reported an ANR for {package_name}",
        ),
        (
            re.compile(rf"E ActivityTaskManager: ANR in {escaped}"),
            f"ActivityTaskManager reported an ANR for {package_name}",
        ),
        (
            re.compile(rf"Not responding: .*{escaped}", re.IGNORECASE),
            f"System server logged a generic ANR message referencing {package_name}",
        ),
        (
            re.compile(rf"Application is not responding: Process {escaped}"),
            f"Detected system level 'Application is not responding' warning for {package_name}",
        ),
        (
            re.compile(rf"Application Not Responding: .*{escaped}", re.IGNORECASE),
            f"Explicit Application Not Responding banner detected for {package_name}",
        ),
        (
            re.compile(rf"ANR.*Process:\s*{escaped}", re.IGNORECASE),
            f"ANR report referenced process {package_name}",
        ),
        (
            re.compile(
                rf"Input dispatching timed out(?:(?!\n\d{{2}}-\d{{2}}\s).)*{escaped}",
                re.IGNORECASE | re.DOTALL,
            ),
            "Detected input dispatch timeout indicative of an ANR during instrumentation tests",
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
            re.compile(
                rf"Fatal signal \d+.*?\(SIG[A-Z]+\).*?{escaped}",
                re.DOTALL,
            ),
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


LOG_PREFIX_RE = re.compile(r"\b([VDIWEF])\s+([A-Za-z0-9_.$-]+):")


def _log_prefix(line: str) -> Optional[str]:
    match = LOG_PREFIX_RE.search(line)
    if match:
        return f"{match.group(1)} {match.group(2)}:"
    return None


def _instrumentation_prefix(line: str) -> Optional[str]:
    stripped = line.strip()
    if not stripped:
        return None
    if stripped.upper().startswith("INSTRUMENTATION_"):
        return "INSTRUMENTATION_"
    if stripped.startswith("Instrumentation "):
        return "Instrumentation "
    return None


def _is_related_log_line(
    line: str,
    prefix: Optional[str],
    instrumentation_prefix: Optional[str],
    base_line: str,
) -> bool:
    stripped = line.strip()
    if not stripped:
        return False
    if instrumentation_prefix:
        return stripped.upper().startswith(instrumentation_prefix.upper())
    if prefix:
        if _log_prefix(line) == prefix:
            return True
        if stripped.startswith(
            (
                "at ",
                "Caused by:",
                "Suppressed:",
                "Process:",
                "Process ",
                "pid ",
                "java.",
                "kotlin.",
                "android.",
                "com.",
                "org.",
            )
        ):
            return True
        return False
    if re.match(r"\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}", stripped):
        return False
    if re.match(r"[VDIWEF]\s+[A-Za-z0-9_.$-]+:", stripped):
        return False
    return True


def _extract_log_snippet(contents: str, match: re.Match[str]) -> str:
    lines_with_newlines = contents.splitlines(True)
    positions: List[Tuple[int, int]] = []
    cursor = 0
    for segment in lines_with_newlines:
        length = len(segment)
        positions.append((cursor, cursor + length))
        cursor += length

    line_index = 0
    for index, (start, end) in enumerate(positions):
        if match.start() < end:
            line_index = index
            break

    base_line = lines_with_newlines[line_index].rstrip("\r\n")
    prefix = _log_prefix(base_line)
    instrumentation_prefix = _instrumentation_prefix(base_line)

    indices = [line_index]

    idx = line_index - 1
    while idx >= 0:
        candidate = lines_with_newlines[idx].rstrip("\r\n")
        if _is_related_log_line(candidate, prefix, instrumentation_prefix, base_line):
            indices.insert(0, idx)
            idx -= 1
            continue
        break

    idx = line_index + 1
    while idx < len(lines_with_newlines):
        candidate = lines_with_newlines[idx].rstrip("\r\n")
        if _is_related_log_line(candidate, prefix, instrumentation_prefix, base_line):
            indices.append(idx)
            idx += 1
            continue
        break

    snippet = "".join(lines_with_newlines[i] for i in indices).rstrip("\n")
    return snippet


def _find_issues(
    contents: str, signatures: Iterable[Tuple[Pattern[str], str]]
) -> List[Tuple[str, str]]:
    issues: List[Tuple[str, str]] = []
    for pattern, message in signatures:
        for match in pattern.finditer(contents):
            snippet = _extract_log_snippet(contents, match)
            issues.append((message, snippet))
    return issues


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
) -> List[IssueReport]:
    issues: List[IssueReport] = []
    seen = set()
    for path in paths:
        if not path.exists():
            issues.append(
                IssueReport(
                    message=f"Unable to locate captured log at {path}",
                    snippet="",
                    source=str(path),
                )
            )
            continue

        for source, text in _iter_log_sources(path):
            for message, snippet in _find_issues(text, signatures):
                fingerprint = (message, snippet)
                if fingerprint in seen:
                    continue
                seen.add(fingerprint)
                issues.append(
                    IssueReport(message=message, snippet=snippet, source=source)
                )

    return issues


def _parse_bool(value: Optional[str]) -> Optional[bool]:
    if value is None:
        return None
    normalized = value.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    return None


def _virtualization_unavailable_from_env() -> Optional[bool]:
    """Infer virtualization availability from CI environment variables."""

    for key in (
        "NOVAPDF_VIRTUALIZATION_UNAVAILABLE",
        "NOVAPDF_TEST_VIRTUALIZATION_UNAVAILABLE",
        "ACTIONS_RUNNER_DISABLE_NESTED_VIRTUALIZATION",
    ):
        parsed = _parse_bool(os.environ.get(key))
        if parsed is not None:
            return parsed
    return None


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
    parser.add_argument(
        "--virtualization-unavailable",
        dest="virtualization_unavailable",
        action="store_true",
        help="Skip crash detection when Android emulator virtualization is unavailable.",
    )
    parser.add_argument(
        "--virtualization-available",
        dest="virtualization_unavailable",
        action="store_false",
        help="Force crash detection even if virtualization-related environment markers are present.",
    )
    parser.set_defaults(virtualization_unavailable=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    virtualization_unavailable = args.virtualization_unavailable
    if virtualization_unavailable is None:
        virtualization_unavailable = _virtualization_unavailable_from_env()

    if virtualization_unavailable:
        print(
            "Skipping crash scan because Android emulator virtualization is unavailable.",
            file=sys.stderr,
        )
        return 0

    paths = args.logs or [pathlib.Path("logcat-after-tests.txt")]
    issues = _scan_logs_for_issues(paths, _build_crash_signatures(args.package))

    if issues:
        for issue in issues:
            print(f"::error::{issue.message} (source: {issue.source})")
            if issue.snippet:
                print("Relevant log excerpt:")
                print(issue.snippet)
                print("--- end excerpt ---")
        return 1

    inspected = ", ".join(str(path) for path in paths)
    print(f"Logs ({inspected}) are free from ANR/crash signatures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
