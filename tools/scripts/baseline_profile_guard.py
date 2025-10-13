#!/usr/bin/env python3
"""Utilities for detecting when the baseline profile should be regenerated.

This script centralises the heuristics that determine whether a pull request or
local commit needs to refresh ``app/src/main/baseline-prof.txt``.  It can be run
in three different contexts:

* GitHub Actions, where it emits outputs that downstream steps can consume.
* Local developer runs (``python3 tools/scripts/baseline_profile_guard.py``).
* As part of the repository pre-commit hook, which passes ``--staged`` and
  ``--fail-when-outdated`` so that performance-sensitive changes cannot be
  committed without staging an updated baseline profile.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, List, Optional, Sequence, Set

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
BASELINE_PATH = Path("app/src/main/baseline-prof.txt")

RELEVANT_FILENAMES = {
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
    "gradle.lockfile",
}

RELEVANT_GLOB_SUFFIXES = (
    ".gradle",
    ".gradle.kts",
    ".kt",
    ".kts",
    ".java",
    ".c",
    ".cpp",
    ".mm",
    ".m",
    ".h",
    ".hpp",
    ".rs",
    ".swift",
    ".xml",
    ".aidl",
    ".toml",
    ".yaml",
    ".yml",
    ".json",
)

GRADLE_VERSION_FILES = {
    "gradle/libs.versions.toml",
    "gradle/libs.versions.yaml",
    "gradle/libs.versions.yml",
    "gradle/libs.versions.json",
}


@dataclass
class EvaluationResult:
    baseline_path: Path = BASELINE_PATH
    baseline_touched: bool = False
    needs_profile: bool = False
    impacting_files: List[str] = field(default_factory=list)
    skipped: bool = False
    message: str = ""

    def to_outputs(self) -> str:
        lines = [
            f"baseline_touched={'true' if self.baseline_touched else 'false'}",
            f"needs_profile={'true' if self.needs_profile else 'false'}",
            f"reminder={'true' if self.should_remind else 'false'}",
            f"skipped={'true' if self.skipped else 'false'}",
        ]
        return "\n".join(lines)

    @property
    def should_remind(self) -> bool:
        return (
            not self.skipped
            and self.needs_profile
            and not self.baseline_touched
        )


def run_git_diff(args: Sequence[str]) -> List[str]:
    try:
        completed = subprocess.run(
            ["git", "diff", *args, "--name-only"],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            "Unable to determine changed files; ensure Git is available and the "
            "repository has been initialised."
        ) from exc

    files = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    return files


def discover_changed_files(base: Optional[str], staged: bool) -> List[str]:
    if staged:
        return run_git_diff(["--cached"])
    if base:
        return run_git_diff([f"{base}...HEAD"])
    return run_git_diff([])


def is_relevant_path(path: str) -> bool:
    if path == str(BASELINE_PATH):
        return False

    if "/src/main/" in path:
        return True

    if path in RELEVANT_FILENAMES:
        return True

    if path in GRADLE_VERSION_FILES:
        return True

    lower = path.lower()
    return lower.endswith(RELEVANT_GLOB_SUFFIXES)


def evaluate_changes(
    changed_files: Iterable[str],
    baseline_path: Path = BASELINE_PATH,
) -> EvaluationResult:
    result = EvaluationResult()

    impacting: Set[str] = set()

    for raw_path in changed_files:
        if not raw_path:
            continue
        normalized = raw_path.replace("\\", "/")

        if normalized == str(baseline_path):
            result.baseline_touched = True
            continue

        if is_relevant_path(normalized):
            result.needs_profile = True
            impacting.add(normalized)

    result.impacting_files = sorted(impacting)

    if result.should_remind:
        impacted_lines = ""
        if result.impacting_files:
            impacted_lines = "\n".join(
                f"- {path}" for path in result.impacting_files
            )
            impacted_lines = f"The following files triggered the reminder:\n\n{impacted_lines}\n"

        result.message = (
            "Code or dependency changes detected without updating "
            f"{baseline_path}.\n\n"
            f"{impacted_lines}Run ./gradlew :app:generateReleaseBaselineProfile "
            "--stacktrace and commit the refreshed profile.\n\nEnsure the "
            "macrobenchmark scenarios continue to exercise the affected user "
            "flows so the refreshed profile captures the new execution paths."
        ).strip()
    else:
        result.message = (
            "Baseline profile reminder check passed "
            f"(baseline_touched={result.baseline_touched}, "
            f"needs_profile={result.needs_profile})."
        )

    return result


def append_step_summary(summary_path: str, message: str) -> None:
    if not summary_path:
        return

    summary_file = Path(summary_path)
    summary_file.parent.mkdir(parents=True, exist_ok=True)
    with summary_file.open("a", encoding="utf-8") as fh:
        fh.write("## Baseline profile reminder\n")
        fh.write(f"{message}\n")


def write_github_outputs(output_path: str, result: EvaluationResult) -> None:
    if not output_path:
        return

    with open(output_path, "a", encoding="utf-8") as fh:
        fh.write(f"{result.to_outputs()}\n")
        if result.message:
            fh.write("message<<__BASELINE_MESSAGE__\n")
            fh.write(f"{result.message}\n")
            fh.write("__BASELINE_MESSAGE__\n")
        if result.impacting_files:
            fh.write("impacting_files<<__BASELINE_IMPACTING__\n")
            fh.write("\n".join(result.impacting_files))
            fh.write("\n__BASELINE_IMPACTING__\n")


def emit_ci_warning(result: EvaluationResult) -> None:
    if result.should_remind:
        encoded = result.message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::warning::{encoded}")


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base",
        metavar="REF",
        help="Git reference to diff against (for CI runs).",
    )
    parser.add_argument(
        "--staged",
        action="store_true",
        help="Inspect staged changes instead of working tree changes.",
    )
    parser.add_argument(
        "--github-output",
        help="Path to the GitHub Actions output file (optional).",
    )
    parser.add_argument(
        "--github-step-summary",
        help="Path to the GitHub Actions step summary file (optional).",
    )
    parser.add_argument(
        "--fail-when-outdated",
        action="store_true",
        help="Exit with a non-zero status when the baseline profile needs an update",
    )
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="Suppress human-readable output unless remediation is required.",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)

    os.chdir(REPO_ROOT)

    if not BASELINE_PATH.exists():
        print(
            f"::error::Baseline profile not found at {BASELINE_PATH}. Ensure the app module has generated a baseline profile.",
            file=sys.stderr,
        )
        return 1

    changed_files = discover_changed_files(args.base, args.staged)

    if not changed_files:
        result = EvaluationResult(message="Baseline profile reminder skipped because no files changed.")
        result.skipped = True
        write_github_outputs(args.github_output or "", result)
        if not args.quiet:
            print(result.message)
        return 0

    result = evaluate_changes(changed_files)

    write_github_outputs(args.github_output or "", result)

    if result.should_remind:
        append_step_summary(args.github_step_summary or "", result.message)
        if not args.quiet:
            print(result.message, file=sys.stderr)
        emit_ci_warning(result)
        return 1 if args.fail_when_outdated else 0

    if not args.quiet:
        print(result.message)

    return 0


if __name__ == "__main__":  # pragma: no cover - script entry point
    sys.exit(main())
