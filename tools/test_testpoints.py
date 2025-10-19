from __future__ import annotations

import argparse

from tools.capture_screenshots import HarnessContext
from tools.testpoints import HarnessTestPoint, TestPointDispatcher, parse_testpoint


def test_parse_testpoint_with_detail() -> None:
    line = "HARNESS TESTPOINT: cache_ready: directories=/tmp/harness"
    parsed = parse_testpoint(line)
    assert parsed == (HarnessTestPoint.CACHE_READY, "directories=/tmp/harness")


def test_parse_testpoint_without_detail() -> None:
    line = "HARNESS TESTPOINT: pre_initialization"
    parsed = parse_testpoint(line)
    assert parsed == (HarnessTestPoint.PRE_INITIALIZATION, None)


def test_parse_testpoint_unknown_marker() -> None:
    line = "HARNESS TESTPOINT: unexpected_marker"
    assert parse_testpoint(line) is None


def test_dispatcher_records_and_notifies() -> None:
    dispatcher = TestPointDispatcher()
    received: list[tuple[HarnessTestPoint, str | None]] = []

    dispatcher.register(HarnessTestPoint.UI_LOADED, lambda point, detail: received.append((point, detail)))
    dispatcher.register_any(lambda point, detail: received.append((point, detail)))

    dispatcher.dispatch(HarnessTestPoint.UI_LOADED, "page=1/10")

    assert received == [
        (HarnessTestPoint.UI_LOADED, "page=1/10"),
        (HarnessTestPoint.UI_LOADED, "page=1/10"),
    ]
    assert dispatcher.events == [(HarnessTestPoint.UI_LOADED, "page=1/10")]


def test_harness_context_records_testpoints() -> None:
    ctx = HarnessContext(args=argparse.Namespace())
    observed: list[tuple[HarnessTestPoint, str | None]] = []
    ctx.on_any_testpoint(lambda point, detail: observed.append((point, detail)))

    ctx.observe_line("HARNESS TESTPOINT: ready_for_screenshot: payload=foo")
    ctx.observe_line("HARNESS TESTPOINT: unknown_marker")

    assert observed == [(HarnessTestPoint.READY_FOR_SCREENSHOT, "payload=foo")]
    assert ctx.testpoints.events == [(HarnessTestPoint.READY_FOR_SCREENSHOT, "payload=foo")]
