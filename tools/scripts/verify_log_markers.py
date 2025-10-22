"""Verify that captured CI logs contain expected markers.

This helper is designed to guard against scenarios where large log files are
partially written or truncated before they are uploaded as workflow artifacts.
By asserting that well-known completion markers are present we gain confidence
that the tail of the log – where crash summaries and final instrumentation
status updates normally live – has been preserved.
"""

from __future__ import annotations

import argparse
import glob
import os
import pathlib
import sys
from dataclasses import dataclass
from typing import Iterable, Sequence


@dataclass(frozen=True)
class RequirementFailure:
    path: pathlib.Path
    missing: Sequence[str]


def _decode_log_bytes(raw: bytes) -> str:
    """Decode log bytes while being tolerant of mixed encodings.

    Android logcat output may contain stray bytes that are not valid UTF-8. We
    choose ``errors="replace"`` so that we retain the overall structure of the
    log while substituting unreadable bytes with the Unicode replacement
    character. The replacement characters do not affect substring checks and
    avoid raising exceptions that would mask the real issue.
    """

    return raw.decode("utf-8", errors="replace")


def _load_text(path: pathlib.Path) -> str:
    return _decode_log_bytes(path.read_bytes())


def _expand_paths(patterns: Iterable[str]) -> list[pathlib.Path]:
    paths: list[pathlib.Path] = []
    for pattern in patterns:
        matches = [pathlib.Path(match) for match in glob.glob(pattern, recursive=True)]
        if not matches:
            paths.append(pathlib.Path(pattern))
        else:
            paths.extend(matches)
    return paths


def _evaluate_requirements(text: str, required: Sequence[str], groups: Sequence[Sequence[str]]) -> list[str]:
    missing: list[str] = []

    for needle in required:
        if needle not in text:
            missing.append(needle)

    for group in groups:
        if group and not any(candidate in text for candidate in group):
            missing.append(" | ".join(group))

    return missing


def _parse_groups(values: Sequence[str]) -> list[list[str]]:
    groups: list[list[str]] = []
    for value in values:
        group = [segment.strip() for segment in value.split("||") if segment.strip()]
        if group:
            groups.append(group)
    return groups


def _parse_optional_bool(value: str | None) -> bool | None:
    if value is None:
        return None

    normalized = value.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    return None


def _virtualization_unavailable_from_env() -> bool:
    for key in (
        "NOVAPDF_VIRTUALIZATION_UNAVAILABLE",
        "NOVAPDF_TEST_VIRTUALIZATION_UNAVAILABLE",
        "ACTIONS_RUNNER_DISABLE_NESTED_VIRTUALIZATION",
    ):
        parsed = _parse_optional_bool(os.environ.get(key))
        if parsed is not None:
            return parsed
    return False


def verify_logs(paths: Sequence[pathlib.Path], required: Sequence[str], require_any: Sequence[Sequence[str]]) -> list[RequirementFailure]:
    failures: list[RequirementFailure] = []
    for path in paths:
        if not path.exists():
            failures.append(RequirementFailure(path=path, missing=["file is missing"]))
            continue

        try:
            contents = _load_text(path)
        except OSError as exc:
            failures.append(RequirementFailure(path=path, missing=[f"unable to read log: {exc}"]))
            continue

        missing = _evaluate_requirements(contents, required, require_any)
        if missing:
            failures.append(RequirementFailure(path=path, missing=missing))

    return failures


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Verify that CI logs contain expected markers")
    parser.add_argument(
        "--path",
        action="append",
        required=True,
        help="Path or glob to a log file to inspect. Repeat to check multiple logs.",
    )
    parser.add_argument(
        "--require",
        action="append",
        default=[],
        help="Substring that must appear in every matched log. Repeat for multiple requirements.",
    )
    parser.add_argument(
        "--require-any",
        action="append",
        default=[],
        metavar="A||B",
        help="Pipe-separated alternatives; at least one must appear in each log (e.g. 'TestRunner: run finished||INSTRUMENTATION_STATUS_CODE: -1').",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_arg_parser()
    args = parser.parse_args(argv)

    required: list[str] = args.require or []
    require_any = _parse_groups(args.require_any)
    paths = _expand_paths(args.path)

    if _virtualization_unavailable_from_env():
        print(
            "Skipping log marker verification because Android emulator virtualization is unavailable.",
            file=sys.stderr,
        )
        return 0

    failures = verify_logs(paths, required, require_any)
    if not failures:
        return 0

    for failure in failures:
        issues = ", ".join(failure.missing)
        print(f"::error file={failure.path}::Missing expected log markers: {issues}", file=sys.stderr)

    missing_paths = ", ".join(str(item.path) for item in failures)
    print(f"Log marker verification failed for: {missing_paths}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

