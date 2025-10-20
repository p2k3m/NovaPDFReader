import argparse
import json
from typing import List, Tuple

import pytest

import tools.capture_screenshots as capture_screenshots

from tools.capture_screenshots import (
    HARNESS_PHASE_PREFIX,
    HarnessContext,
    HarnessTestPoint,
)


def test_observe_line_tracks_state_machine() -> None:
    ctx = HarnessContext(args=argparse.Namespace())
    observed: List[Tuple[HarnessTestPoint, str | None]] = []
    ctx.on_any_testpoint(lambda point, detail: observed.append((point, detail)))

    ctx.observe_line("HARNESS TESTPOINT: cache_ready: directories=/tmp/harness")
    ctx.observe_line("INSTRUMENTATION_ABORTED: System has crashed")
    ctx.observe_line("Process crashed while executing instrumentation")
    ctx.observe_line("Unable to find instrumentation info for com.example")

    assert observed == [(HarnessTestPoint.CACHE_READY, "directories=/tmp/harness")]
    assert ctx.system_crash_detected
    assert ctx.process_crash_detected
    assert ctx.missing_instrumentation_detected


def test_collects_ready_and_done_flags() -> None:
    ctx = HarnessContext(args=argparse.Namespace())

    ctx.maybe_collect_ready_flag("Writing screenshot ready flag to /tmp/ready.txt")
    ctx.maybe_collect_ready_flag("Writing screenshot ready flag to /tmp/ready.txt")
    ctx.maybe_collect_done_flags("completion signal at /tmp/done.txt, /tmp/other.txt ")

    assert ctx.ready_flags == ["/tmp/ready.txt"]
    assert ctx.done_flags == ["/tmp/done.txt", "/tmp/other.txt"]


def test_collect_package_prefers_normalized_value(capsys: pytest.CaptureFixture[str]) -> None:
    ctx = HarnessContext(args=argparse.Namespace())

    ctx.maybe_collect_package("Resolved screenshot harness package name: com.example.app")
    assert ctx.package == "com.example.app"

    ctx.maybe_collect_package("Resolved screenshot harness package name: ???")
    captured = capsys.readouterr().err
    assert "Unable to determine screenshot harness package" in captured

    # Second warning should be suppressed once emitted.
    ctx.maybe_collect_package("Resolved screenshot harness package name: ???")
    assert capsys.readouterr().err == ""


def test_register_instrumentation_component_tracks_candidates() -> None:
    ctx = HarnessContext(args=argparse.Namespace())

    ctx.register_instrumentation_component("com.example.test/androidx.test.runner.AndroidJUnitRunner")

    assert "com.example.test" in ctx.candidate_packages
    assert "com.example.test/androidx.test.runner.AndroidJUnitRunner" in ctx.instrumentation_components


def test_add_candidate_package_normalizes_value() -> None:
    ctx = HarnessContext(args=argparse.Namespace())
    ctx.add_candidate_package("com.example.App!")

    assert "com.example.App" in ctx.candidate_packages


def test_candidate_packages_returns_copy() -> None:
    ctx = HarnessContext(args=argparse.Namespace())
    ctx.add_candidate_package("com.example.app")

    copy = ctx.candidate_packages
    copy.add("com.other")

    assert "com.other" not in ctx.candidate_packages


def test_guidance_emitted_once(capsys: pytest.CaptureFixture[str]) -> None:
    ctx = HarnessContext(args=argparse.Namespace())
    ctx.system_crash_detected = True
    ctx.missing_instrumentation_detected = True

    ctx.maybe_emit_system_crash_guidance()
    ctx.maybe_emit_system_crash_guidance()
    ctx.maybe_emit_missing_instrumentation_guidance()
    ctx.maybe_emit_missing_instrumentation_guidance()

    output = capsys.readouterr().err
    assert output.count("system_server crashed") == 1
    assert output.count("Screenshot harness instrumentation is not installed") == 1


def test_observe_line_emits_phase_alerts(capsys: pytest.CaptureFixture[str]) -> None:
    ctx = HarnessContext(args=argparse.Namespace())

    retry_payload = {
        "event": "harness_phase",
        "type": "retry",
        "component": "Foo",
        "operation": "bar",
        "attempt": 1,
        "timestampMs": 1,
        "context": {"docId": "abc"},
        "nextAttempt": 2,
        "errorType": "java.lang.RuntimeException",
        "errorMessage": "boom",
    }
    ctx.observe_line(f"{HARNESS_PHASE_PREFIX}{json.dumps(retry_payload)}")

    output = capsys.readouterr().err
    assert "Harness phase RETRY: Foo.bar" in output
    assert "context: docId=abc" in output
    assert "scheduling retry attempt 2" in output


def test_phase_guidance_summarizes_attempts(capsys: pytest.CaptureFixture[str]) -> None:
    ctx = HarnessContext(args=argparse.Namespace())

    def emit(event: dict) -> None:
        ctx.observe_line(f"{HARNESS_PHASE_PREFIX}{json.dumps(event)}")

    emit(
        {
            "event": "harness_phase",
            "type": "start",
            "component": "Foo",
            "operation": "bar",
            "attempt": 1,
            "timestampMs": 1,
            "context": {"docId": "alpha"},
        }
    )
    emit(
        {
            "event": "harness_phase",
            "type": "checkpoint",
            "component": "Foo",
            "operation": "bar",
            "attempt": 1,
            "timestampMs": 2,
            "checkpoint": "download",
            "context": {"docId": "alpha", "page": 1},
        }
    )
    emit(
        {
            "event": "harness_phase",
            "type": "abort",
            "component": "Foo",
            "operation": "bar",
            "attempt": 1,
            "timestampMs": 3,
            "context": {"docId": "alpha", "page": 1},
            "errorType": "java.lang.RuntimeException",
            "errorMessage": "boom",
        }
    )
    emit(
        {
            "event": "harness_phase",
            "type": "retry",
            "component": "Foo",
            "operation": "bar",
            "attempt": 1,
            "timestampMs": 4,
            "context": {"docId": "alpha", "page": 1},
            "nextAttempt": 2,
        }
    )
    emit(
        {
            "event": "harness_phase",
            "type": "start",
            "component": "Foo",
            "operation": "bar",
            "attempt": 2,
            "timestampMs": 5,
            "context": {"docId": "alpha"},
        }
    )
    emit(
        {
            "event": "harness_phase",
            "type": "complete",
            "component": "Foo",
            "operation": "bar",
            "attempt": 2,
            "timestampMs": 6,
            "context": {"docId": "alpha", "page": 10},
            "detail": "done",
        }
    )

    ctx.maybe_emit_phase_guidance()
    output = capsys.readouterr().err
    assert "Harness phase timeline:" in output
    assert "Foo.bar attempt 1" in output
    assert "checkpoint: download" in output
    assert "error: java.lang.RuntimeException: boom" in output
    assert "next attempt: 2" in output
    assert "Foo.bar attempt 2" in output
    assert "detail: done" in output

    ctx.maybe_emit_phase_guidance()
    assert capsys.readouterr().err == ""


def test_resolve_instrumentation_component_emits_error(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    args = argparse.Namespace(instrumentation="com.example/.Runner", skip_auto_install=False)

    monkeypatch.setattr(
        capture_screenshots,
        "_resolve_instrumentation_component_once",
        lambda *unused_args, **unused_kwargs: None,
    )
    monkeypatch.setattr(
        capture_screenshots,
        "_normalize_instrumentation_component",
        lambda *_: None,
    )
    monkeypatch.setattr(
        capture_screenshots,
        "_prefer_requested_instrumentation_component",
        lambda *_: None,
    )
    monkeypatch.setattr(
        capture_screenshots,
        "auto_install_debug_apks",
        lambda *_: capture_screenshots.AutoInstallResult(
            attempted=False, succeeded=False
        ),
    )
    monkeypatch.setattr(
        capture_screenshots,
        "_virtualization_unavailable",
        lambda: False,
    )

    assert capture_screenshots.resolve_instrumentation_component(args) is None

    error_output = capsys.readouterr().err
    expected = "Failed to detect screenshot harness instrumentation component."
    assert expected in error_output
    assert f"::error::{expected}" in error_output


def test_emit_missing_instrumentation_error_warns_on_virtualization(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(capture_screenshots, "_virtualization_unavailable", lambda: True)

    capture_screenshots._emit_missing_instrumentation_error(
        capture_screenshots.AutoInstallResult(attempted=True, succeeded=False)
    )

    output = capsys.readouterr().err
    assert (
        "Failed to detect screenshot harness instrumentation component after Gradle installation."
        in output
    )
    warning = (
        "Android emulator virtualization is unavailable in this environment. "
        "Connect a physical device or enable virtualization to install the screenshot harness."
    )
    assert warning in output
    assert f"::warning::{warning}" in output


def test_emit_missing_instrumentation_error_uses_result_virtualization_flag(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(capture_screenshots, "_virtualization_unavailable", lambda: False)

    capture_screenshots._emit_missing_instrumentation_error(
        capture_screenshots.AutoInstallResult(
            attempted=True, succeeded=True, virtualization_unavailable=True
        )
    )

    output = capsys.readouterr().err
    assert (
        "Failed to detect screenshot harness instrumentation component after Gradle installation."
        in output
    )
    warning = (
        "Android emulator virtualization is unavailable in this environment. "
        "Connect a physical device or enable virtualization to install the screenshot harness."
    )
    assert warning in output
    assert f"::warning::{warning}" in output
