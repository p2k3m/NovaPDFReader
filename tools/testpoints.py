"""Utilities for parsing screenshot harness test point markers."""

from __future__ import annotations

from enum import Enum
from typing import Callable, Dict, Iterable, List, Optional, Tuple

PREFIX = "HARNESS TESTPOINT: "


class HarnessTestPoint(str, Enum):
    """Known harness test point markers emitted by the instrumentation."""

    PRE_INITIALIZATION = "pre_initialization"
    CACHE_READY = "cache_ready"
    UI_LOADED = "ui_loaded"
    READY_FOR_SCREENSHOT = "ready_for_screenshot"
    ERROR_SIGNALED = "error_signaled"

    @classmethod
    def from_label(cls, label: str) -> "HarnessTestPoint":
        try:
            return cls(label)
        except ValueError as error:
            raise ValueError(f"Unknown harness test point: {label}") from error


TestPointCallback = Callable[[HarnessTestPoint, Optional[str]], None]


def parse_testpoint(line: str) -> Optional[Tuple[HarnessTestPoint, Optional[str]]]:
    """Parses a line of instrumentation output for a test point marker."""

    stripped = line.strip()
    if not stripped.startswith(PREFIX):
        return None
    payload = stripped[len(PREFIX) :].strip()
    if not payload:
        return None
    if ":" in payload:
        label, detail = payload.split(":", 1)
        detail = detail.strip() or None
    else:
        label, detail = payload, None
    try:
        point = HarnessTestPoint.from_label(label.strip())
    except ValueError:
        return None
    return point, detail


class TestPointDispatcher:
    """Dispatches parsed test point events to registered callbacks."""

    __test__ = False  # Prevent pytest from collecting this helper as a test case.

    def __init__(self) -> None:
        self._callbacks: Dict[HarnessTestPoint, List[TestPointCallback]] = {}
        self._any_callbacks: List[TestPointCallback] = []
        self.events: List[Tuple[HarnessTestPoint, Optional[str]]] = []

    def register(self, point: HarnessTestPoint, callback: TestPointCallback) -> None:
        self._callbacks.setdefault(point, []).append(callback)

    def register_any(self, callback: TestPointCallback) -> None:
        self._any_callbacks.append(callback)

    def dispatch(self, point: HarnessTestPoint, detail: Optional[str]) -> None:
        self.events.append((point, detail))
        for callback in list(self._callbacks.get(point, [])):
            callback(point, detail)
        for callback in list(self._any_callbacks):
            callback(point, detail)

    def extend(self, events: Iterable[Tuple[HarnessTestPoint, Optional[str]]]) -> None:
        for point, detail in events:
            self.dispatch(point, detail)
