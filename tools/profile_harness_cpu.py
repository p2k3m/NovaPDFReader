#!/usr/bin/env python3
"""Automate CPU sampling around instrumentation harness runs.

This script follows the manual profiling flow described in developer
instructions: it captures three `adb shell top` snapshots (before, during, and
after) around an arbitrary harness command. Optional hooks run the existing
`device_resource_snapshot.py` helper to gather structured CPU/memory metrics
before and after the harness as well.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import IO, List, Sequence

ROOT = Path(__file__).resolve().parent
RESOURCE_SNAPSHOT_SCRIPT = ROOT / "device_resource_snapshot.py"


class HarnessProfilerError(RuntimeError):
    """Raised when the profiling workflow fails."""


def build_adb_command(adb: str, serial: str | None, *extra: str) -> List[str]:
    command = [adb]
    if serial:
        command.extend(["-s", serial])
    command.extend(extra)
    return command


def run_subprocess(command: Sequence[str], *, capture_output: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=capture_output, check=False)


def ensure_success(result: subprocess.CompletedProcess[str], *, context: str) -> None:
    if result.returncode == 0:
        return
    stderr = result.stderr or ""
    stdout = result.stdout or ""
    message = [f"{context} failed with exit code {result.returncode}."]
    if stdout.strip():
        message.append(f"stdout:\n{stdout.strip()}")
    if stderr.strip():
        message.append(f"stderr:\n{stderr.strip()}")
    raise HarnessProfilerError("\n".join(message))


def capture_top_snapshot(*, adb: str, serial: str | None, label: str, output_dir: Path, processes: int, interval: float) -> Path:
    output_path = output_dir / f"top-{label}.txt"
    command = build_adb_command(
        adb,
        serial,
        "shell",
        "top",
        "-n",
        "1",
        "-m",
        str(processes),
        "-d",
        f"{interval:g}",
    )
    with output_path.open("w", encoding="utf-8") as destination:
        result = subprocess.run(command, text=True, stdout=destination, stderr=subprocess.PIPE)
    if result.returncode != 0:
        stderr = result.stderr.strip()
        raise HarnessProfilerError(
            f"Failed to collect '{label}' top snapshot (exit {result.returncode}).{os.linesep}{stderr}"
        )
    return output_path


def run_resource_snapshot(*, label: str, args: argparse.Namespace, output_dir: Path) -> Path:
    script = args.resource_snapshot_script
    if not script.exists():
        raise HarnessProfilerError(f"Resource snapshot script not found at {script}")
    output_path = output_dir / f"resources-{label}.json"
    command = [sys.executable, str(script), "--output", str(output_path)]
    if args.adb:
        command.extend(["--adb", args.adb])
    if args.serial:
        command.extend(["--serial", args.serial])
    result = run_subprocess(command, capture_output=True)
    ensure_success(result, context=f"Resource snapshot ({label})")
    return output_path


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Profile CPU usage around an instrumentation harness run.")
    parser.add_argument(
        "--adb",
        default="adb",
        help="Path to the adb executable (default: %(default)s)",
    )
    parser.add_argument(
        "--serial",
        help="ADB device serial to target (default: use whatever adb selects)",
    )
    parser.add_argument(
        "--output-dir",
        default=".",
        help="Directory to store profiling artifacts (default: current directory)",
    )
    parser.add_argument(
        "--process-count",
        type=int,
        default=20,
        help="Number of processes to include in each top snapshot (default: %(default)s)",
    )
    parser.add_argument(
        "--top-interval",
        type=float,
        default=1.0,
        help="Sampling interval (seconds) passed to top -d (default: %(default)s)",
    )
    parser.add_argument(
        "--harness-start-delay",
        type=float,
        default=1.0,
        help="Seconds to wait after launching the harness before capturing the 'during' snapshot",
    )
    parser.add_argument(
        "--resource-snapshots",
        action="store_true",
        help="Also run device_resource_snapshot.py before and after the harness",
    )
    parser.add_argument(
        "--resource-snapshot-script",
        default=str(RESOURCE_SNAPSHOT_SCRIPT),
        help="Override path to device_resource_snapshot.py (default: %(default)s)",
    )
    parser.add_argument(
        "--harness-log",
        help="Optional path to write the harness stdout/stderr stream",
    )
    parser.add_argument(
        "harness_command",
        nargs=argparse.REMAINDER,
        help="Command used to start the instrumentation harness (provide after '--')",
    )
    args = parser.parse_args()

    if not args.harness_command:
        parser.error("No harness command provided. Pass it after '--' so arbitrary flags are preserved.")

    return args


def main() -> int:
    args = parse_arguments()

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    harness_log_path = Path(args.harness_log).resolve() if args.harness_log else None
    if harness_log_path and not harness_log_path.parent.exists():
        harness_log_path.parent.mkdir(parents=True, exist_ok=True)

    print("Capturing pre-harness top snapshot...")
    before_path = capture_top_snapshot(
        adb=args.adb,
        serial=args.serial,
        label="before",
        output_dir=output_dir,
        processes=args.process_count,
        interval=args.top_interval,
    )
    print(f"Saved: {before_path}")

    if args.resource_snapshots:
        print("Collecting baseline resource snapshot...")
        snapshot_path = run_resource_snapshot(label="before", args=args, output_dir=output_dir)
        print(f"Saved: {snapshot_path}")

    harness_command = args.harness_command
    if harness_command and harness_command[0] == "--":
        harness_command = harness_command[1:]
    if not harness_command:
        raise HarnessProfilerError("Harness command is empty after stripping '--'.")

    log_handle: IO[str] | None = None
    if harness_log_path:
        log_handle = harness_log_path.open("w", encoding="utf-8")
        stdout_target: IO[str] | int = log_handle
        stderr_target: IO[str] | int = subprocess.STDOUT
        print(f"Harness output will be written to {harness_log_path}")
    else:
        stdout_target = None
        stderr_target = None

    print(f"Launching harness: {' '.join(harness_command)}")
    harness_process = subprocess.Popen(harness_command, stdout=stdout_target, stderr=stderr_target)

    try:
        time.sleep(max(args.harness_start_delay, 0))
        print("Capturing mid-run top snapshot...")
        during_path = capture_top_snapshot(
            adb=args.adb,
            serial=args.serial,
            label="during",
            output_dir=output_dir,
            processes=args.process_count,
            interval=args.top_interval,
        )
        print(f"Saved: {during_path}")
    finally:
        print("Waiting for harness to finish...")
        return_code = harness_process.wait()
        if log_handle is not None:
            log_handle.close()

    print("Capturing post-harness top snapshot...")
    after_path = capture_top_snapshot(
        adb=args.adb,
        serial=args.serial,
        label="after",
        output_dir=output_dir,
        processes=args.process_count,
        interval=args.top_interval,
    )
    print(f"Saved: {after_path}")

    if args.resource_snapshots:
        print("Collecting post-run resource snapshot...")
        snapshot_path = run_resource_snapshot(label="after", args=args, output_dir=output_dir)
        print(f"Saved: {snapshot_path}")

    if return_code != 0:
        raise HarnessProfilerError(f"Harness command exited with code {return_code}")

    print("Profiling complete.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except HarnessProfilerError as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
