#!/usr/bin/env python3
"""Capture and evaluate emulator/device resource statistics via adb.

The script records memory and CPU availability for an attached Android
emulator or device. Snapshots are emitted as JSON so CI workflows can
archive them for later inspection. When resources fall below configured
thresholds the script returns a non-zero exit status so callers can abort
expensive instrumentation runs before they fail unpredictably.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

CPU_LINE_PREFIX = "cpu "


def build_adb_command(args: argparse.Namespace, *extra: str) -> List[str]:
    command = [args.adb]
    if args.serial:
        command.extend(["-s", args.serial])
    command.extend(extra)
    return command


def run_adb(args: argparse.Namespace, *extra: str) -> str:
    command = build_adb_command(args, *extra)
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0:
        raise RuntimeError(
            f"adb command {' '.join(command)} failed with code {completed.returncode}: {completed.stderr.strip()}"
        )
    return completed.stdout


def parse_meminfo(raw: str) -> Dict[str, int]:
    info: Dict[str, int] = {}
    for line in raw.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        key = key.strip()
        tokens = value.strip().split()
        if not tokens:
            continue
        number = tokens[0]
        try:
            info[key] = int(number)
        except ValueError:
            continue
    return info


def parse_cpu_line(raw: str) -> Tuple[int, int]:
    for line in raw.splitlines():
        if line.startswith(CPU_LINE_PREFIX):
            parts = line.split()
            values: List[int] = []
            for token in parts[1:]:
                try:
                    values.append(int(token))
                except ValueError:
                    values.append(0)
            if not values:
                break
            total = sum(values)
            idle = 0
            if len(values) > 3:
                idle = values[3]
            if len(values) > 4:
                idle += values[4]
            return total, idle
    raise RuntimeError("Unable to locate aggregate CPU stats in /proc/stat output")


def sample_cpu_usage(args: argparse.Namespace) -> float:
    before = run_adb(args, "shell", "cat", "/proc/stat")
    time.sleep(args.cpu_sample_delay)
    after = run_adb(args, "shell", "cat", "/proc/stat")

    before_total, before_idle = parse_cpu_line(before)
    after_total, after_idle = parse_cpu_line(after)
    delta_total = after_total - before_total
    delta_idle = after_idle - before_idle

    if delta_total <= 0:
        return 0.0

    usage = 1.0 - (delta_idle / delta_total)
    if usage < 0:
        usage = 0.0
    return min(usage * 100.0, 100.0)


def detect_cpu_cores(args: argparse.Namespace) -> int:
    try:
        raw = run_adb(args, "shell", "cat", "/sys/devices/system/cpu/present")
    except RuntimeError:
        raw = ""
    text = raw.strip()
    total = 0
    if text:
        for fragment in text.split(","):
            fragment = fragment.strip()
            if not fragment:
                continue
            if "-" in fragment:
                start_text, end_text = fragment.split("-", 1)
                try:
                    start = int(start_text)
                    end = int(end_text)
                except ValueError:
                    continue
                total += end - start + 1
            else:
                try:
                    int(fragment)
                except ValueError:
                    continue
                total += 1
    if total > 0:
        return total

    # Fallback to counting processor entries in /proc/cpuinfo.
    try:
        cpuinfo = run_adb(args, "shell", "cat", "/proc/cpuinfo")
    except RuntimeError:
        return 0
    count = 0
    for line in cpuinfo.splitlines():
        if line.lower().startswith("processor"):
            count += 1
    return count


@dataclass
class Snapshot:
    label: str
    timestamp: float
    mem_total_kb: int
    mem_available_kb: int
    mem_free_kb: int
    swap_free_kb: int
    cpu_usage_percent: float
    cpu_cores: int
    device_properties: Dict[str, str]
    raw_meminfo: str

    @property
    def mem_available_percent(self) -> float:
        if self.mem_total_kb <= 0:
            return 0.0
        return (self.mem_available_kb / self.mem_total_kb) * 100.0

    def to_dict(self) -> Dict[str, object]:
        return {
            "label": self.label,
            "timestamp": self.timestamp,
            "mem_total_kb": self.mem_total_kb,
            "mem_available_kb": self.mem_available_kb,
            "mem_free_kb": self.mem_free_kb,
            "swap_free_kb": self.swap_free_kb,
            "mem_available_percent": self.mem_available_percent,
            "cpu_usage_percent": self.cpu_usage_percent,
            "cpu_cores": self.cpu_cores,
            "device_properties": self.device_properties,
            "raw_meminfo": self.raw_meminfo,
        }


def gather_device_properties(args: argparse.Namespace) -> Dict[str, str]:
    props = {
        "ro.product.model": "",
        "ro.product.manufacturer": "",
        "ro.hardware": "",
        "ro.boot.hardware.sku": "",
        "ro.build.version.release": "",
    }
    for key in list(props.keys()):
        try:
            value = run_adb(args, "shell", "getprop", key)
        except RuntimeError:
            value = ""
        props[key] = value.strip()
    return props


def format_summary(snapshot: Snapshot, exhausted: bool, reasons: List[str]) -> str:
    total_mb = snapshot.mem_total_kb / 1024 if snapshot.mem_total_kb else 0
    available_mb = snapshot.mem_available_kb / 1024
    summary = (
        f"[device-resource] {snapshot.label}: available {available_mb:.1f} MiB / "
        f"{total_mb:.1f} MiB ({snapshot.mem_available_percent:.1f}%), "
        f"cpu {snapshot.cpu_usage_percent:.1f}% across {snapshot.cpu_cores or 'unknown'} cores"
    )
    if exhausted:
        summary += f" — resource exhaustion detected ({'; '.join(reasons)})"
    return summary


def capture_snapshot(args: argparse.Namespace) -> Snapshot:
    raw_meminfo = run_adb(args, "shell", "cat", "/proc/meminfo")
    meminfo = parse_meminfo(raw_meminfo)
    mem_total_kb = meminfo.get("MemTotal", 0)
    mem_available_kb = meminfo.get("MemAvailable", 0)
    mem_free_kb = meminfo.get("MemFree", 0)
    swap_free_kb = meminfo.get("SwapFree", 0)
    cpu_usage = sample_cpu_usage(args)
    cpu_cores = detect_cpu_cores(args)
    properties = gather_device_properties(args) if args.include_properties else {}
    return Snapshot(
        label=args.label,
        timestamp=time.time(),
        mem_total_kb=mem_total_kb,
        mem_available_kb=mem_available_kb,
        mem_free_kb=mem_free_kb,
        swap_free_kb=swap_free_kb,
        cpu_usage_percent=cpu_usage,
        cpu_cores=cpu_cores,
        device_properties=properties,
        raw_meminfo=raw_meminfo,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", default="snapshot", help="Label describing the snapshot context")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"), help="adb executable to invoke")
    parser.add_argument("--serial", help="Specific adb serial to target")
    parser.add_argument(
        "--output",
        type=Path,
        help="Optional path where the JSON snapshot should be written. If omitted the JSON is printed to stdout.",
    )
    parser.add_argument(
        "--cpu-sample-delay",
        type=float,
        default=1.0,
        help="Seconds to wait between CPU utilisation samples (default: %(default)s)",
    )
    parser.add_argument(
        "--min-available-percent",
        type=float,
        default=10.0,
        help="Fail when available memory drops below this percentage of total (default: %(default)s)",
    )
    parser.add_argument(
        "--min-available-mb",
        type=float,
        default=256.0,
        help="Fail when available memory drops below this threshold in MiB (default: %(default)s)",
    )
    parser.add_argument(
        "--max-cpu-percent",
        type=float,
        default=97.0,
        help="Fail when computed CPU utilisation exceeds this percentage (default: %(default)s)",
    )
    parser.add_argument(
        "--include-properties",
        action="store_true",
        help="Capture high-level device build properties for additional context",
    )
    parser.add_argument(
        "--allow-exhausted",
        action="store_true",
        help="Do not exit with an error when resources are exhausted (useful for post-failure inspection)",
    )
    parser.add_argument(
        "--fail-exit-code",
        type=int,
        default=78,
        help="Exit status to use when resource exhaustion is detected (default: %(default)s)",
    )
    return parser.parse_args()


def write_snapshot(snapshot: Snapshot, output: Path | None) -> None:
    data = snapshot.to_dict()
    if output is None:
        json.dump(data, sys.stdout, indent=2)
        sys.stdout.write("\n")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(data, indent=2))


def evaluate_thresholds(args: argparse.Namespace, snapshot: Snapshot) -> Tuple[bool, List[str]]:
    exhausted = False
    reasons: List[str] = []
    available_mb = snapshot.mem_available_kb / 1024
    if snapshot.mem_total_kb > 0 and snapshot.mem_available_percent < args.min_available_percent:
        exhausted = True
        reasons.append(
            f"available memory {snapshot.mem_available_percent:.1f}% below threshold {args.min_available_percent:.1f}%"
        )
    if available_mb < args.min_available_mb:
        exhausted = True
        reasons.append(f"available memory {available_mb:.1f} MiB below threshold {args.min_available_mb:.1f} MiB")
    if snapshot.cpu_usage_percent > args.max_cpu_percent:
        exhausted = True
        reasons.append(
            f"cpu utilisation {snapshot.cpu_usage_percent:.1f}% above threshold {args.max_cpu_percent:.1f}%"
        )
    return exhausted, reasons


def main() -> int:
    args = parse_args()
    try:
        snapshot = capture_snapshot(args)
    except RuntimeError as error:
        print(f"::error::Failed to capture device resources: {error}", file=sys.stderr)
        return 70

    exhausted, reasons = evaluate_thresholds(args, snapshot)
    summary = format_summary(snapshot, exhausted, reasons)
    print(summary)

    try:
        write_snapshot(snapshot, args.output)
    except OSError as error:
        print(f"::error::Failed to write snapshot: {error}", file=sys.stderr)
        return 73

    if exhausted and not args.allow_exhausted:
        for reason in reasons:
            print(f"::error::{reason}", file=sys.stderr)
        return args.fail_exit_code

    return 0


if __name__ == "__main__":
    sys.exit(main())
