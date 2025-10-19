import json

from tools.capture_screenshots import HARNESS_PHASE_PREFIX
from tools.device_fleet_health import (
    DeviceRecord,
    filter_devices,
    gather_phase_metrics,
    gather_testpoint_metrics,
    parse_devices_output,
)
from tools.testpoints import HarnessTestPoint


def test_parse_devices_output_extracts_attributes() -> None:
    raw = """
    List of devices attached
    emulator-5554 device product:sdk_gphone model:sdk_gphone device:generic transport_id:1
    123456F offline
    """
    records = parse_devices_output(raw)
    assert records == [
        DeviceRecord(
            serial="emulator-5554",
            state="device",
            attributes={
                "product": "sdk_gphone",
                "model": "sdk_gphone",
                "device": "generic",
                "transport_id": "1",
            },
        ),
        DeviceRecord(serial="123456F", state="offline", attributes={}),
    ]


def test_filter_devices_limits_to_requested_serials() -> None:
    records = [
        DeviceRecord(serial="A", state="device", attributes={}),
        DeviceRecord(serial="B", state="device", attributes={}),
    ]
    filtered = filter_devices(records, ["B"])
    assert filtered == [DeviceRecord(serial="B", state="device", attributes={})]


def test_gather_phase_metrics_counts_events_and_errors() -> None:
    event_payload = {
        "event": "harness_phase",
        "type": "error",
        "component": "Downloader",
        "operation": "fetch",
        "attempt": 1,
        "context": {"docId": "abc"},
        "errorType": "IOException",
        "errorMessage": "timeout",
    }
    lines = [
        f"{HARNESS_PHASE_PREFIX}{json.dumps(event_payload)}",
        "irrelevant",
    ]
    summary = gather_phase_metrics(lines)
    assert summary.counts == {"error": 1}
    assert len(summary.errors) == 1
    error = summary.errors[0]
    assert error.component == "Downloader"
    assert error.error_type == "IOException"


def test_gather_testpoint_metrics_counts_occurrences() -> None:
    lines = [
        "HARNESS TESTPOINT: ready_for_screenshot",
        "HARNESS TESTPOINT: ready_for_screenshot",
        "HARNESS TESTPOINT: cache_ready",
    ]
    summary = gather_testpoint_metrics(lines)
    assert summary.counts == {
        HarnessTestPoint.READY_FOR_SCREENSHOT: 2,
        HarnessTestPoint.CACHE_READY: 1,
    }
