#!/usr/bin/env python3
"""Orchestrate device health checks across the connected fleet."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

from tools.capture_screenshots import (
    HARNESS_HEALTHCHECK_TEST,
    HARNESS_HEALTHCHECK_TIMEOUT_SECONDS,
    HarnessPhaseEvent,
    parse_phase_event,
    resolve_instrumentation_component,
    launch_instrumentation,
    parse_extra_args,
    ensure_test_package_argument,
)
from tools.device_resource_snapshot import (
    Snapshot,
    capture_snapshot,
)
from tools.testpoints import HarnessTestPoint, parse_testpoint


@dataclass
class DeviceRecord:
    serial: str
    state: str
    attributes: Dict[str, str] = field(default_factory=dict)


@dataclass
class PhaseSummary:
    counts: Dict[str, int] = field(default_factory=dict)
    errors: List[HarnessPhaseEvent] = field(default_factory=list)


@dataclass
class TestpointSummary:
    counts: Dict[HarnessTestPoint, int] = field(default_factory=dict)


@dataclass
class HealthcheckResult:
    serial: str
    component: Optional[str]
    passed: bool
    timed_out: bool
    exit_code: Optional[int]
    duration_seconds: float
    output_lines: List[str]
    phase_summary: PhaseSummary
    testpoint_summary: TestpointSummary
    snapshot: Optional[Snapshot]
    failure_reason: Optional[str]
    device_state: str
    device_attributes: Dict[str, str]

    def to_dict(self) -> Dict[str, object]:
        phase_errors = [
            {
                "type": event.type,
                "component": event.component,
                "operation": event.operation,
                "attempt": event.attempt,
                "error_type": event.error_type,
                "error_message": event.error_message,
                "context": event.context,
            }
            for event in self.phase_summary.errors
        ]
        testpoint_counts = {
            point.value: count for point, count in self.testpoint_summary.counts.items()
        }
        snapshot_dict = self.snapshot.to_dict() if self.snapshot else None
        return {
            "serial": self.serial,
            "device_state": self.device_state,
            "device_attributes": self.device_attributes,
            "component": self.component,
            "passed": self.passed,
            "timed_out": self.timed_out,
            "exit_code": self.exit_code,
            "duration_seconds": self.duration_seconds,
            "failure_reason": self.failure_reason,
            "phase_counts": self.phase_summary.counts,
            "phase_errors": phase_errors,
            "testpoint_counts": testpoint_counts,
            "resource_snapshot": snapshot_dict,
            "output": self.output_lines,
        }


def parse_devices_output(raw: str) -> List[DeviceRecord]:
    records: List[DeviceRecord] = []
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("List of devices"):
            continue
        parts = stripped.split()
        if not parts:
            continue
        serial = parts[0]
        if len(parts) == 1:
            continue
        state = parts[1]
        attributes: Dict[str, str] = {}
        for token in parts[2:]:
            if ":" not in token:
                continue
            key, value = token.split(":", 1)
            if key and value:
                attributes[key] = value
        records.append(DeviceRecord(serial=serial, state=state, attributes=attributes))
    return records


def list_connected_devices(adb: str) -> List[DeviceRecord]:
    result = subprocess.run(
        [adb, "devices", "-l"], capture_output=True, text=True, check=True
    )
    return parse_devices_output(result.stdout)


def gather_phase_metrics(lines: Iterable[str]) -> PhaseSummary:
    summary = PhaseSummary()
    for line in lines:
        event = parse_phase_event(line)
        if not event:
            continue
        summary.counts[event.type] = summary.counts.get(event.type, 0) + 1
        if event.type.lower() == "error":
            summary.errors.append(event)
    return summary


def gather_testpoint_metrics(lines: Iterable[str]) -> TestpointSummary:
    summary = TestpointSummary()
    for line in lines:
        parsed = parse_testpoint(line)
        if not parsed:
            continue
        point, _detail = parsed
        summary.counts[point] = summary.counts.get(point, 0) + 1
    return summary


def build_instrumentation_args(
    global_args: argparse.Namespace, serial: str
) -> argparse.Namespace:
    return argparse.Namespace(
        adb=global_args.adb,
        serial=serial,
        instrumentation=global_args.instrumentation,
        skip_auto_install=global_args.skip_auto_install,
        test=global_args.healthcheck_test,
        test_package=global_args.test_package,
    )


def collect_resource_snapshot(
    global_args: argparse.Namespace, serial: str
) -> Optional[Snapshot]:
    if global_args.skip_resource_snapshot:
        return None
    resource_args = argparse.Namespace(
        label="pre_healthcheck",
        adb=global_args.adb,
        serial=serial,
        cpu_sample_delay=global_args.cpu_sample_delay,
        include_properties=True,
    )
    try:
        return capture_snapshot(resource_args)
    except Exception as error:
        print(
            f"Failed to capture resource snapshot for {serial}: {error}",
            file=sys.stderr,
        )
        return None


def execute_healthcheck(
    global_args: argparse.Namespace,
    record: DeviceRecord,
    extra_args: List[Tuple[str, str]],
) -> HealthcheckResult:
    instrumentation_args = build_instrumentation_args(global_args, record.serial)
    component: Optional[str] = None
    failure_reason: Optional[str] = None

    try:
        component = resolve_instrumentation_component(instrumentation_args)
    except Exception as error:  # pragma: no cover - defensive
        failure_reason = f"Instrumentation resolution failed: {error}"
        print(failure_reason, file=sys.stderr)

    if not component:
        return HealthcheckResult(
            serial=record.serial,
            component=None,
            passed=False,
            timed_out=False,
            exit_code=None,
            duration_seconds=0.0,
            output_lines=[],
            phase_summary=PhaseSummary(),
            testpoint_summary=TestpointSummary(),
            snapshot=collect_resource_snapshot(global_args, record.serial),
            failure_reason=failure_reason or "Instrumentation component not found",
            device_state=record.state,
            device_attributes=record.attributes,
        )

    augmented_extra_args = ensure_test_package_argument(
        instrumentation_args, extra_args, component
    )

    snapshot = collect_resource_snapshot(global_args, record.serial)

    start_time = time.time()
    process = launch_instrumentation(
        instrumentation_args,
        augmented_extra_args,
        component,
        test_override=global_args.healthcheck_test,
    )
    timed_out = False
    output = ""
    try:
        output, _ = process.communicate(timeout=global_args.timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        process.kill()
        tail_output, _ = process.communicate()
        output = tail_output

    duration = time.time() - start_time
    exit_code = process.returncode
    lines = output.splitlines()
    phase_summary = gather_phase_metrics(lines)
    testpoint_summary = gather_testpoint_metrics(lines)

    passed = not timed_out and exit_code == 0 and not phase_summary.errors

    if not passed and not failure_reason:
        if timed_out:
            failure_reason = "Instrumentation timed out"
        elif exit_code not in (0, None):
            failure_reason = f"Instrumentation exited with code {exit_code}"
        elif phase_summary.errors:
            failure_reason = "Harness reported phase errors"
        else:
            failure_reason = "Instrumentation reported failure"

    return HealthcheckResult(
        serial=record.serial,
        component=component,
        passed=passed,
        timed_out=timed_out,
        exit_code=exit_code,
        duration_seconds=duration,
        output_lines=lines[-global_args.output_line_limit :],
        phase_summary=phase_summary,
        testpoint_summary=testpoint_summary,
        snapshot=snapshot,
        failure_reason=failure_reason,
        device_state=record.state,
        device_attributes=record.attributes,
    )


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--adb",
        default="adb",
        help="adb executable to invoke (default: %(default)s)",
    )
    parser.add_argument(
        "--instrumentation",
        default="com.novapdf.reader.test/dagger.hilt.android.testing.HiltTestRunner",
        help="Instrumentation component to execute for the health check",
    )
    parser.add_argument(
        "--healthcheck-test",
        default=HARNESS_HEALTHCHECK_TEST,
        help="Fully qualified instrumentation test method used for the health check",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=HARNESS_HEALTHCHECK_TIMEOUT_SECONDS,
        help="Seconds to wait for each device health check",
    )
    parser.add_argument(
        "--skip-auto-install",
        action="store_true",
        help="Skip automatic installation of debug APKs when instrumentation is missing",
    )
    parser.add_argument(
        "--test-package",
        help="Explicit instrumentation test package name to provide via extras",
    )
    parser.add_argument(
        "--extra-arg",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="Additional instrumentation argument to pass (repeatable)",
    )
    parser.add_argument(
        "--device",
        action="append",
        dest="devices",
        help="Restrict the health check to specific device serials (repeatable)",
    )
    parser.add_argument(
        "--output-json",
        type=Path,
        help="Optional path where the aggregated health metrics should be written",
    )
    parser.add_argument(
        "--cpu-sample-delay",
        type=float,
        default=1.0,
        help="Seconds to wait between CPU usage samples for resource snapshots",
    )
    parser.add_argument(
        "--skip-resource-snapshot",
        action="store_true",
        help="Disable resource snapshot capture for faster execution",
    )
    parser.add_argument(
        "--output-line-limit",
        type=int,
        default=200,
        help="Maximum number of instrumentation output lines to retain per device",
    )
    return parser


def filter_devices(
    records: List[DeviceRecord], devices: Optional[List[str]]
) -> List[DeviceRecord]:
    if not devices:
        return records
    wanted = set(devices)
    return [record for record in records if record.serial in wanted]


def summarise_results(results: List[HealthcheckResult]) -> None:
    total = len(results)
    passed = sum(1 for result in results if result.passed)
    failed = total - passed
    print(f"Processed {total} device health checks: {passed} passed, {failed} failed")
    for result in results:
        status = "PASSED" if result.passed else "FAILED"
        suffix = f" ({result.failure_reason})" if result.failure_reason else ""
        print(
            f"  {result.serial}: {status} in {result.duration_seconds:.1f}s — "
            f"state={result.device_state}{suffix}"
        )


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_argument_parser()
    args = parser.parse_args(argv)

    try:
        device_records = list_connected_devices(args.adb)
    except subprocess.CalledProcessError as error:
        print(f"Unable to enumerate devices: {error}", file=sys.stderr)
        return 2

    device_records = filter_devices(device_records, args.devices)

    if not device_records:
        print("No matching devices connected.", file=sys.stderr)
        return 1

    extra_args = parse_extra_args(args.extra_arg)

    results: List[HealthcheckResult] = []
    for record in device_records:
        if record.state != "device":
            results.append(
                HealthcheckResult(
                    serial=record.serial,
                    component=None,
                    passed=False,
                    timed_out=False,
                    exit_code=None,
                    duration_seconds=0.0,
                    output_lines=[],
                    phase_summary=PhaseSummary(),
                    testpoint_summary=TestpointSummary(),
                    snapshot=None,
                    failure_reason=f"Device state is {record.state}",
                    device_state=record.state,
                    device_attributes=record.attributes,
                )
            )
            continue
        results.append(execute_healthcheck(args, record, extra_args))

    summarise_results(results)

    if args.output_json:
        payload = {
            "generated_at": time.time(),
            "results": [result.to_dict() for result in results],
        }
        try:
            args.output_json.write_text(json.dumps(payload, indent=2), encoding="utf-8")
            print(f"Wrote fleet health metrics to {args.output_json}")
        except OSError as error:
            print(f"Failed to write {args.output_json}: {error}", file=sys.stderr)
            return 1

    return 0 if all(result.passed for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
