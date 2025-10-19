import json

from tools.capture_screenshots import (
    HARNESS_PHASE_PREFIX,
    HarnessPhaseEvent,
    parse_phase_event,
)


def _line(payload: dict) -> str:
    return f"{HARNESS_PHASE_PREFIX}{json.dumps(payload)}"


def test_parse_phase_event_valid() -> None:
    payload = {
        "event": "harness_phase",
        "type": "checkpoint",
        "component": "Foo",
        "operation": "bar",
        "attempt": 2,
        "timestampMs": 12345,
        "checkpoint": "load_cache",
        "detail": "directories=/tmp",
        "context": {"docId": "abc", "page": 3},
        "nextAttempt": 3,
    }
    event = parse_phase_event(_line(payload))
    assert isinstance(event, HarnessPhaseEvent)
    assert event.type == "checkpoint"
    assert event.component == "Foo"
    assert event.operation == "bar"
    assert event.attempt == 2
    assert event.timestamp_ms == 12345
    assert event.checkpoint == "load_cache"
    assert event.detail == "directories=/tmp"
    assert event.context == {"docId": "abc", "page": "3"}
    assert event.next_attempt == 3


def test_parse_phase_event_rejects_invalid_payloads() -> None:
    assert parse_phase_event("some unrelated line") is None
    assert parse_phase_event(f"{HARNESS_PHASE_PREFIX}{{not json}}") is None
    assert parse_phase_event(_line({"event": "other"})) is None
    assert (
        parse_phase_event(
            _line({"event": "harness_phase", "type": "start", "component": "x"})
        )
        is None
    )
    assert (
        parse_phase_event(
            _line(
                {
                    "event": "harness_phase",
                    "type": "start",
                    "component": "x",
                    "operation": "y",
                    "attempt": "not-a-number",
                }
            )
        )
        is None
    )
