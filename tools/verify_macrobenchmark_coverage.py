#!/usr/bin/env python3
"""Verify that macrobenchmark instrumentation exercises all required flows."""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable, List, Sequence, Set, Tuple

DEFAULT_SEARCH_ROOTS: Sequence[Path] = (
    Path("baselineprofile/build/outputs/androidTest-results/connected"),
    Path("baselineprofile/build/reports/androidTests/connected"),
)

REQUIRED_TESTS: Set[Tuple[str, str]] = {
    ("com.novapdf.reader.baselineprofile.BaselineProfileGenerator", "generate"),
    ("com.novapdf.reader.baselineprofile.FrameRateBenchmark", "scrollDocument"),
    (
        "com.novapdf.reader.baselineprofile.FrameRateBenchmark",
        "steadyStateScrollMaintainsFps",
    ),
    ("com.novapdf.reader.baselineprofile.MemoryBenchmark", "coldStartMemory"),
    (
        "com.novapdf.reader.baselineprofile.MemoryBenchmark",
        "peakMemoryWithinThreshold",
    ),
    ("com.novapdf.reader.baselineprofile.RenderBenchmark", "renderFirstPage"),
    (
        "com.novapdf.reader.baselineprofile.RenderBenchmark",
        "timeToFirstPageWithinThreshold",
    ),
    ("com.novapdf.reader.baselineprofile.StartupBenchmark", "coldStartup"),
}


def discover_reports(search_roots: Iterable[Path]) -> List[Path]:
    reports: List[Path] = []
    for root in search_roots:
        if not root.exists():
            continue
        if root.is_file():
            if root.suffix.lower() == ".xml":
                reports.append(root)
            continue
        for candidate in root.rglob("*.xml"):
            reports.append(candidate)
    return reports


def parse_report(path: Path) -> Set[Tuple[str, str, str]]:
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:  # pragma: no cover - defensive branch
        print(f"::warning::Skipping unreadable instrumentation report {path}: {exc}")
        return set()

    root = tree.getroot()
    tests: Set[Tuple[str, str, str]] = set()
    for testcase in root.iter("testcase"):
        classname = (testcase.get("classname") or "").strip()
        name = (testcase.get("name") or "").strip()
        status = (testcase.get("status") or "").strip()
        if not classname or not name:
            continue
        outcome = "passed"
        if any(child.tag in {"failure", "error"} for child in testcase):
            outcome = "failed"
        elif any(child.tag == "skipped" for child in testcase) or status.lower() == "skipped":
            outcome = "skipped"
        tests.add((classname, name, outcome))
    return tests


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        action="append",
        dest="roots",
        help="Additional directories to search for instrumentation XML reports.",
    )
    args = parser.parse_args(argv)

    search_roots = list(DEFAULT_SEARCH_ROOTS)
    if args.roots:
        search_roots.extend(Path(root) for root in args.roots)

    reports = discover_reports(search_roots)
    if not reports:
        print(
            "::error::Macrobenchmark results were not found. Ensure the baselineprofile instrumentation output directory exists.",
            file=sys.stderr,
        )
        for root in search_roots:
            print(f"Searched: {root}")
        return 1

    executed: Set[Tuple[str, str]] = set()
    failures: List[str] = []
    skips: List[str] = []

    for report in reports:
        for classname, name, outcome in parse_report(report):
            key = (classname, name)
            if outcome == "failed":
                failures.append(f"{classname}.{name} (see {report})")
            elif outcome == "skipped":
                skips.append(f"{classname}.{name} (see {report})")
            else:
                executed.add(key)

    if failures:
        for failure in failures:
            print(f"::error::Required macrobenchmark test failed: {failure}")
        return 1

    if skips:
        for skipped in skips:
            print(f"::error::Required macrobenchmark test was skipped: {skipped}")
        return 1

    missing = sorted(REQUIRED_TESTS.difference(executed))
    if missing:
        for classname, name in missing:
            print(
                "::error::Connected macrobenchmark instrumentation did not exercise "
                f"required scenario {classname}.{name}.",
                file=sys.stderr,
            )
        print(
            "Run ./gradlew :baselineprofile:connectedBenchmarkAndroidTest locally to verify the missing scenarios and "
            "confirm that benchmark flows continue to match real user journeys.",
            file=sys.stderr,
        )
        return 1

    for classname, name in sorted(executed.intersection(REQUIRED_TESTS)):
        print(f"Confirmed macrobenchmark execution: {classname}.{name}")

    return 0


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    raise SystemExit(main())
