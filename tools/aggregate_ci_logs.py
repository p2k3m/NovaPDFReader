#!/usr/bin/env python3
"""Aggregate CI log files into an exportable archive or directory tree.

The NovaPDFReader CI harness generates a variety of log artefacts (logcat
captures, instrumentation transcripts, device watchdog output, etc.) during
every run.  For long-term analysis we need a consistent way to collate those
files, stamp them with the relevant device/test/config/run metadata, and store
them in a durable location such as cloud object storage.  This helper handles
the aggregation step: it gathers log files from one or more paths, normalises
their layout, records a manifest, and emits either a directory hierarchy or a
compressed archive that callers can upload.

Example usage (writes to a directory):

    tools/aggregate_ci_logs.py \
        --device "pixel6-api34" \
        --test "connectedAndroidTest" \
        --config "release" \
        --run-id "$GITHUB_RUN_ID" \
        --output exported-logs \
        --path artifacts/logs --path logcat-after-tests.txt

Example usage (creates a tar.gz archive ready for upload):

    tools/aggregate_ci_logs.py \
        --device emulator-api33 \
        --test screenshotHarness \
        --config withAccessibility \
        --run-id "$BUILD_NUMBER" \
        --output ci-logs.tar.gz \
        --archive-format tar

The resulting exports always contain a ``manifest.json`` file describing the
archived logs, which downstream tooling can use to correlate runs and monitor
long-term stability trends.
"""

from __future__ import annotations

import argparse
import datetime as _datetime
import glob
import hashlib
import io
import json
import shutil
import sys
import tarfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterator, List, Sequence, Tuple


@dataclass(frozen=True)
class ExportMetadata:
    """Identifies a specific CI run in a structured way."""

    device: str
    test: str
    config: str
    run_id: str

    @property
    def path(self) -> Path:
        """Return the relative path prefix for this export."""

        return Path(self.device) / self.test / self.config / self.run_id


@dataclass
class LogFile:
    """Represents a single log file staged for export."""

    source: Path
    relative_path: Path
    size_bytes: int
    sha256: str


def _expand_pathspec(pathspec: str) -> List[Path]:
    """Expand a path or glob expression into concrete :class:`Path` objects."""

    path = Path(pathspec).expanduser()
    text = str(path)
    if any(char in text for char in "*?[]"):
        return [Path(match) for match in glob.glob(text)]
    return [path]


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _normalise_prefixes(paths: Sequence[Path]) -> Dict[Path, Path]:
    """Assign stable per-root prefixes so files keep their provenance."""

    prefixes: Dict[Path, Path] = {}
    seen: Dict[str, int] = {}
    for raw in paths:
        root = raw
        if root.is_file():
            root = root.parent
        name = raw.name if raw.is_dir() else raw.parent.name or raw.name
        if not name:
            name = "logs"
        count = seen.get(name, 0)
        seen[name] = count + 1
        if count:
            name = f"{name}-{count+1}"
        prefixes[raw] = Path(name)
    return prefixes


def _iter_log_files(paths: Sequence[Path]) -> Iterator[Tuple[Path, Path]]:
    """Yield (source, relative_path) pairs for files under the given roots."""

    prefixes = _normalise_prefixes(paths)
    for root in paths:
        if root.is_dir():
            base = root
            prefix = prefixes[root]
            for entry in sorted(base.rglob("*")):
                if entry.is_file():
                    relative = entry.relative_to(base)
                    if prefix:
                        relative = prefix / relative
                    yield entry, relative
        elif root.is_file():
            prefix = prefixes[root]
            relative = Path(root.name)
            if prefix and prefix != Path(root.parent.name):
                relative = prefix / relative
            yield root, relative


def collect_log_files(paths: Sequence[Path]) -> List[LogFile]:
    """Collect metadata for log files rooted at the provided paths."""

    collected: List[LogFile] = []
    used_paths: Dict[Path, int] = {}
    for source, relative in _iter_log_files(paths):
        # Disambiguate duplicate relative paths by appending a numeric suffix to
        # the filename portion.
        final_relative = relative
        counter = used_paths.get(relative, 0)
        if counter:
            stem = relative.stem
            suffix = relative.suffix
            parent = relative.parent
            while True:
                counter += 1
                candidate = parent / f"{stem}-{counter}{suffix}"
                if candidate not in used_paths:
                    final_relative = candidate
                    used_paths[relative] = counter
                    used_paths[candidate] = 1
                    break
        else:
            used_paths[relative] = 1

        size = source.stat().st_size
        checksum = _hash_file(source)
        collected.append(LogFile(source=source, relative_path=final_relative, size_bytes=size, sha256=checksum))
    return collected


def _build_manifest(metadata: ExportMetadata, files: Sequence[LogFile], manifest_name: str) -> Tuple[Path, bytes]:
    """Generate the manifest JSON payload for the aggregated logs."""

    total_bytes = sum(file.size_bytes for file in files)
    now = _datetime.datetime.now(tz=_datetime.timezone.utc).replace(microsecond=0)
    manifest = {
        "device": metadata.device,
        "test": metadata.test,
        "config": metadata.config,
        "run_id": metadata.run_id,
        "generated_at": now.isoformat().replace("+00:00", "Z"),
        "total_files": len(files),
        "total_bytes": total_bytes,
        "files": [
            {
                "relative_path": file.relative_path.as_posix(),
                "size_bytes": file.size_bytes,
                "sha256": file.sha256,
                "source": str(file.source),
            }
            for file in files
        ],
    }
    payload = json.dumps(manifest, indent=2, sort_keys=True).encode("utf-8")
    return Path(manifest_name), payload


def _write_directory_export(metadata: ExportMetadata, files: Sequence[LogFile], output: Path, manifest_name: str) -> Path:
    """Write collected logs to a directory hierarchy."""

    root = output / metadata.path
    root.mkdir(parents=True, exist_ok=True)
    for file in files:
        destination = root / file.relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file.source, destination)
    manifest_path, payload = _build_manifest(metadata, files, manifest_name)
    manifest_file = root / manifest_path
    manifest_file.write_bytes(payload)
    return root


def _write_tar_export(metadata: ExportMetadata, files: Sequence[LogFile], output: Path, manifest_name: str) -> Path:
    """Write collected logs into a tar.gz archive."""

    archive_path = output
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    prefix = metadata.path
    manifest_path, payload = _build_manifest(metadata, files, manifest_name)
    with tarfile.open(archive_path, "w:gz") as archive:
        for file in files:
            arcname = (prefix / file.relative_path).as_posix()
            archive.add(file.source, arcname=arcname)
        info = tarfile.TarInfo(name=(prefix / manifest_path).as_posix())
        info.size = len(payload)
        info.mtime = int(_datetime.datetime.now().timestamp())
        info.mode = 0o644
        archive.addfile(info, io.BytesIO(payload))
    return archive_path


def _write_zip_export(metadata: ExportMetadata, files: Sequence[LogFile], output: Path, manifest_name: str) -> Path:
    """Write collected logs into a zip archive."""

    archive_path = output
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    prefix = metadata.path
    manifest_path, payload = _build_manifest(metadata, files, manifest_name)
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in files:
            arcname = (prefix / file.relative_path).as_posix()
            archive.write(file.source, arcname=arcname)
        archive.writestr((prefix / manifest_path).as_posix(), payload)
    return archive_path


def export_logs(
    metadata: ExportMetadata,
    files: Sequence[LogFile],
    output: Path,
    *,
    archive_format: str,
    manifest_name: str,
) -> Path:
    """Export collected log files using the requested format."""

    if archive_format == "dir":
        return _write_directory_export(metadata, files, output, manifest_name)
    if archive_format == "tar":
        return _write_tar_export(metadata, files, output, manifest_name)
    if archive_format == "zip":
        return _write_zip_export(metadata, files, output, manifest_name)
    raise ValueError(f"Unsupported archive format: {archive_format}")


def _parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Aggregate CI log files for archival")
    parser.add_argument("--device", required=True, help="Device or emulator identifier (e.g. pixel6-api34)")
    parser.add_argument("--test", required=True, help="Test suite or scenario name")
    parser.add_argument("--config", required=True, help="Harness configuration label")
    parser.add_argument("--run-id", required=True, help="Unique identifier for this CI run")
    parser.add_argument(
        "--path",
        action="append",
        dest="paths",
        help="File or directory containing log artefacts (supports glob patterns)",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Destination directory or archive path for the aggregated logs",
    )
    parser.add_argument(
        "--archive-format",
        choices=["dir", "tar", "zip"],
        default="dir",
        help="Export format: directory (default), tar.gz archive, or zip archive",
    )
    parser.add_argument(
        "--manifest-name",
        default="manifest.json",
        help="Filename to use for the generated manifest",
    )
    parser.add_argument(
        "--allow-missing",
        action="store_true",
        help="Do not fail when no log files are discovered",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    raw_paths = args.paths or []
    expanded: List[Path] = []
    for raw in raw_paths:
        matches = _expand_pathspec(raw)
        if not matches:
            print(f"Warning: path '{raw}' did not match any files", file=sys.stderr)
        expanded.extend(Path(match).resolve() for match in matches)

    if not expanded and not args.allow_missing:
        print("No log files were provided; use --allow-missing to write an empty manifest", file=sys.stderr)
        return 1

    metadata = ExportMetadata(device=args.device, test=args.test, config=args.config, run_id=args.run_id)
    files = collect_log_files(expanded)
    if not files and not args.allow_missing:
        print("No log files discovered in provided paths", file=sys.stderr)
        return 1

    output_path = Path(args.output).expanduser().resolve()
    result = export_logs(metadata, files, output_path, archive_format=args.archive_format, manifest_name=args.manifest_name)
    print(f"Exported {len(files)} log files to {result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
