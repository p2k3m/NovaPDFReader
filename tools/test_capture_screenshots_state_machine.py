import argparse
import json
import subprocess
from pathlib import Path
from typing import List, Tuple

import pytest

import tools.capture_screenshots as capture_screenshots

from tools.capture_screenshots import (
    HARNESS_PHASE_PREFIX,
    HarnessContext,
    HarnessSystemCrash,
    HarnessTestPoint,
    stream_instrumentation_output,
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


def test_stream_instrumentation_output_aborts_on_system_crash(
    capsys: pytest.CaptureFixture[str],
) -> None:
    ctx = HarnessContext(args=argparse.Namespace())

    class FakeStdout:
        def __init__(self, lines: List[str]):
            self._iterator = iter(lines)

        def __iter__(self) -> "FakeStdout":
            return self

        def __next__(self) -> str:
            return next(self._iterator)

        def close(self) -> None:  # pragma: no cover - interface compatibility
            pass

    class FakeProcess:
        def __init__(self, lines: List[str]):
            self.stdout = FakeStdout(lines)

    lines = [
        "HARNESS TESTPOINT: cache_ready: directories=/tmp/harness\n",
        "INSTRUMENTATION_ABORTED: System has crashed\n",
    ]
    process = FakeProcess(lines)

    with pytest.raises(HarnessSystemCrash):
        list(
            stream_instrumentation_output(
                process,
                ctx,
                start_timeout=None,
                abort_on_system_crash=True,
            )
        )

    assert ctx.system_crash_detected
    captured = capsys.readouterr()
    assert "System has crashed" in captured.out


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


def test_resolve_instrumentation_component_warns_when_adb_lacks_package_service(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    args = argparse.Namespace(instrumentation="com.example/.Runner", skip_auto_install=False)

    def fake_adb_command_output(*_unused_args, **_unused_kwargs) -> str:
        raise subprocess.CalledProcessError(
            returncode=1,
            cmd=["adb", "shell", "pm", "list", "instrumentation"],
            output="cmd: Can't find service: package\n",
        )

    monkeypatch.setattr(capture_screenshots, "adb_command_output", fake_adb_command_output)
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
    monkeypatch.setattr(capture_screenshots, "_virtualization_unavailable", lambda: False)

    assert capture_screenshots.resolve_instrumentation_component(args) is None

    assert getattr(args, "_novapdf_virtualization_unavailable", False)

    output = capsys.readouterr().err
    warning = (
        "Android emulator virtualization is unavailable in this environment. "
        "Connect a physical device or enable virtualization to install the screenshot harness."
    )
    assert warning in output
    assert f"::warning::{warning}" in output


@pytest.mark.parametrize(
    "error_output",
    [
        (
            "java.lang.NullPointerException: Attempt to invoke virtual method 'void "
            "android.content.pm.PackageManagerInternal.freeStorage' on a null object"
        ),
        "android.os.ParcelableException: java.io.IOException: Session dir already exists: /data/app/vmdl123.tmp",
    ],
)
def test_resolve_instrumentation_component_warns_when_installation_fails_with_virtualization_signatures(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    error_output: str,
) -> None:
    args = argparse.Namespace(instrumentation="com.example/.Runner", skip_auto_install=False)

    def fake_adb_command_output(*_unused_args, **_unused_kwargs) -> str:
        raise subprocess.CalledProcessError(
            returncode=1,
            cmd=["adb", "shell", "pm", "list", "instrumentation"],
            output=error_output,
        )

    monkeypatch.setattr(capture_screenshots, "adb_command_output", fake_adb_command_output)
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
        lambda *_: capture_screenshots.AutoInstallResult(attempted=False, succeeded=False),
    )
    monkeypatch.setattr(capture_screenshots, "_virtualization_unavailable", lambda: False)

    assert capture_screenshots.resolve_instrumentation_component(args) is None

    assert getattr(args, "_novapdf_virtualization_unavailable", False)

    output = capsys.readouterr().err
    warning = (
        "Android emulator virtualization is unavailable in this environment. "
        "Connect a physical device or enable virtualization to install the screenshot harness."
    )
    assert warning in output
    assert f"::warning::{warning}" in output


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


def test_auto_install_debug_apks_detects_virtualization_with_ansi(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    args = argparse.Namespace(skip_auto_install=False)

    gradle_wrapper = tmp_path / "gradlew"
    gradle_wrapper.write_text("", encoding="utf-8")

    monkeypatch.setattr(capture_screenshots, "_gradle_wrapper_path", lambda: gradle_wrapper)

    ansi_output = (
        "\x1b[33mSkipping task installDebugAndroidTest because the current environment "
        "does not support Android emulator virtualization.\x1b[0m"
    )
    monkeypatch.setattr(
        capture_screenshots,
        "_run_gradle_install",
        lambda command, cwd: (True, ansi_output),
    )

    result = capture_screenshots.auto_install_debug_apks(args)

    assert result.virtualization_unavailable
    assert getattr(args, "_novapdf_virtualization_unavailable", False)


def test_run_instrumentation_once_skips_when_virtualization_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    args = argparse.Namespace(
        extra_arg=[],
        document_factory=None,
        storage_client_factory=None,
        instrumentation="com.example/.Runner",
        skip_auto_install=False,
        timeout=5,
    )

    monkeypatch.setattr(capture_screenshots, "parse_extra_args", lambda _: [])
    monkeypatch.setattr(
        capture_screenshots,
        "ensure_test_package_argument",
        lambda _args, extras, _component: extras,
    )
    monkeypatch.setattr(
        capture_screenshots,
        "derive_fallback_package",
        lambda *_unused_args, **_unused_kwargs: None,
    )

    def fake_resolve(resolve_args: argparse.Namespace) -> None:
        setattr(
            resolve_args,
            "_novapdf_last_auto_install_result",
            capture_screenshots.AutoInstallResult(
                attempted=True, succeeded=False, virtualization_unavailable=True
            ),
        )
        return None

    monkeypatch.setattr(
        capture_screenshots,
        "resolve_instrumentation_component",
        fake_resolve,
    )
    monkeypatch.setattr(capture_screenshots, "_virtualization_unavailable", lambda: False)

    exit_code, ctx = capture_screenshots.run_instrumentation_once(args)

    assert exit_code == 0
    assert ctx.virtualization_unavailable
    assert ctx.capture_completed


def test_run_instrumentation_once_skips_when_args_marked_virtualization(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    args = argparse.Namespace(
        extra_arg=[],
        document_factory=None,
        storage_client_factory=None,
        instrumentation="com.example/.Runner",
        skip_auto_install=False,
        timeout=5,
    )

    monkeypatch.setattr(capture_screenshots, "parse_extra_args", lambda *_: [])
    monkeypatch.setattr(
        capture_screenshots,
        "ensure_test_package_argument",
        lambda _args, extras, _component: extras,
    )
    monkeypatch.setattr(
        capture_screenshots,
        "derive_fallback_package",
        lambda *_unused_args, **_unused_kwargs: None,
    )

    def fake_resolve(resolve_args: argparse.Namespace) -> None:
        setattr(
            resolve_args,
            "_novapdf_last_auto_install_result",
            capture_screenshots.AutoInstallResult(
                attempted=True, succeeded=False, virtualization_unavailable=False
            ),
        )
        setattr(resolve_args, "_novapdf_virtualization_unavailable", True)
        return None

    monkeypatch.setattr(
        capture_screenshots,
        "resolve_instrumentation_component",
        fake_resolve,
    )
    monkeypatch.setattr(capture_screenshots, "_virtualization_unavailable", lambda: False)

    exit_code, ctx = capture_screenshots.run_instrumentation_once(args)

    assert exit_code == 0
    assert ctx.virtualization_unavailable
    assert ctx.capture_completed
