import argparse
from typing import List, Tuple

import pytest

from tools.capture_screenshots import HarnessContext, HarnessTestPoint


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
