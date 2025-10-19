from __future__ import annotations

"""Prune stale baseline profile and benchmark runs from the repository."""

import argparse
import datetime as _datetime
import json
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Sequence, Tuple

UTC = _datetime.timezone.utc
DEFAULT_RETENTION_DAYS = 90
DEFAULT_RUNS_DIR = Path("docs/performance/baselineprofile/runs")


@dataclass(frozen=True)
class RunInfo:
    """Describes a captured macrobenchmark run."""

    path: Path
    recorded_at: _datetime.datetime
    source: str


@dataclass(frozen=True)
class RetireReport:
    """Summary of the retention sweep."""

    removed: List[RunInfo]
    kept: List[RunInfo]
    skipped: List[Tuple[Path, str]]
    cutoff: _datetime.datetime
    now: _datetime.datetime


def _parse_timestamp(value: str) -> _datetime.datetime:
    normalized = value.strip()
    if not normalized:
        raise ValueError("timestamp is empty")
    if normalized.endswith("Z"):
        normalized = normalized[:-1] + "+00:00"
    result = _datetime.datetime.fromisoformat(normalized)
    if result.tzinfo is None:
        result = result.replace(tzinfo=UTC)
    else:
        result = result.astimezone(UTC)
    return result


def _parse_directory_timestamp(name: str) -> _datetime.datetime | None:
    if "T" not in name:
        return None
    date_part, time_part = name.split("T", 1)
    time_part = time_part.rstrip("Z")
    if not date_part or not time_part:
        return None
    candidate = time_part.replace("-", ":")
    try:
        return _parse_timestamp(f"{date_part}T{candidate}Z")
    except ValueError:
        return None


def _load_metadata(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except json.JSONDecodeError as exc:
        raise ValueError(f"metadata is not valid JSON: {exc}") from exc


def _resolve_recorded_at(run_dir: Path) -> Tuple[_datetime.datetime | None, str | None]:
    metadata_path = run_dir / "metadata.json"
    try:
        metadata = _load_metadata(metadata_path)
    except ValueError as exc:
        return None, f"{metadata_path.name} unreadable ({exc})"

    if metadata:
        raw = metadata.get("recorded_at")
        if isinstance(raw, str) and raw.strip():
            try:
                return _parse_timestamp(raw), "metadata.json"
            except ValueError as exc:
                return None, f"invalid recorded_at in metadata ({exc})"

    fallback = _parse_directory_timestamp(run_dir.name)
    if fallback is not None:
        return fallback, "directory name"
    return None, "recorded_at missing"


def retire_runs(
    runs_dir: Path,
    retention_days: int = DEFAULT_RETENTION_DAYS,
    *,
    now: _datetime.datetime | None = None,
    dry_run: bool = False,
) -> RetireReport:
    if retention_days <= 0:
        raise ValueError("retention_days must be positive")

    resolved_now = (now or _datetime.datetime.now(tz=UTC)).astimezone(UTC)
    cutoff = resolved_now - _datetime.timedelta(days=retention_days)

    removed: List[RunInfo] = []
    kept: List[RunInfo] = []
    skipped: List[Tuple[Path, str]] = []

    if not runs_dir.exists():
        return RetireReport(removed=removed, kept=kept, skipped=skipped, cutoff=cutoff, now=resolved_now)

    for entry in sorted(runs_dir.iterdir()):
        if not entry.is_dir():
            continue

        recorded_at, source = _resolve_recorded_at(entry)
        if recorded_at is None or source is None:
            skipped.append((entry, source or "recorded_at missing"))
            continue

        info = RunInfo(path=entry, recorded_at=recorded_at, source=source)
        if recorded_at < cutoff:
            removed.append(info)
            if not dry_run:
                shutil.rmtree(entry)
        else:
            kept.append(info)

    return RetireReport(removed=removed, kept=kept, skipped=skipped, cutoff=cutoff, now=resolved_now)


def _format_timestamp(value: _datetime.datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _format_run(info: RunInfo) -> str:
    return f"{info.path} (recorded_at={_format_timestamp(info.recorded_at)}, source={info.source})"


def _print_report(report: RetireReport, *, dry_run: bool) -> None:
    action = "Would remove" if dry_run else "Removed"
    if report.removed:
        for info in report.removed:
            print(f"{action} {info.path} (recorded_at={_format_timestamp(info.recorded_at)})")
    else:
        print("No stale runs detected.")

    if report.kept:
        print("Retained runs:")
        for info in report.kept:
            print(f"  - {_format_run(info)}")

    if report.skipped:
        for path, reason in report.skipped:
            print(f"::warning::Skipped {path}: {reason}", file=sys.stderr)

    print(
        f"Retention cutoff: {_format_timestamp(report.cutoff)} (retention={report.now - report.cutoff})"
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--runs-dir",
        type=Path,
        default=DEFAULT_RUNS_DIR,
        help="Directory containing timestamped benchmark runs.",
    )
    parser.add_argument(
        "--retention-days",
        type=int,
        default=DEFAULT_RETENTION_DAYS,
        help="Number of days to retain benchmark runs.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Preview deletions without removing any directories.",
    )
    parser.add_argument(
        "--now",
        dest="now",
        help="Override the current timestamp (ISO-8601). Useful for tests and reproducible sweeps.",
    )
    args = parser.parse_args(argv)

    try:
        override_now = _parse_timestamp(args.now) if args.now else None
        report = retire_runs(
            args.runs_dir,
            retention_days=args.retention_days,
            now=override_now,
            dry_run=args.dry_run,
        )
    except ValueError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1

    _print_report(report, dry_run=args.dry_run)
    return 0


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    raise SystemExit(main())
