#!/usr/bin/env python3
"""Capture NovaPDF screenshots using the instrumentation handshake.

The script launches the screenshot harness test, waits for the ready
handshake flags, captures a PNG via ``adb exec-out screencap``, and signals
completion back to the device. Screenshots are written with deterministic file
names following the ``<docId>_page<NNNN>.png`` convention where the document ID
portion is sanitized to avoid problematic filesystem characters.
"""
from __future__ import annotations

import argparse
import json
import os
import queue
import re
import shlex
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple

from tools.testpoints import (
    HarnessTestPoint,
    TestPointCallback,
    TestPointDispatcher,
    parse_testpoint,
)

SAFE_PUNCTUATION = {".", "-", "_"}
MULTIPLE_UNDERSCORES = re.compile(r"_+")
MULTIPLE_PERIODS = re.compile(r"\.+")
PACKAGE_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._]+$")
HARNESS_ENV_PATH = Path(__file__).resolve().parents[1] / "config" / "screenshot-harness.env"
HARNESS_START_TIMEOUT_SECONDS = 10
HARNESS_HEALTHCHECK_TEST = (
    "com.novapdf.reader.HarnessHealthcheckTest#harnessDependencyGraph"
)
HARNESS_HEALTHCHECK_TIMEOUT_SECONDS = 30
LOGCAT_TAIL_LINES = 200
NATIVE_CRASH_ARTIFACT_ROOT = Path("native-crash-artifacts")
HARNESS_PHASE_PREFIX = "HARNESS PHASE: "


@dataclass
class HarnessPhaseEvent:
    """Structured representation of harness phase lifecycle events."""

    type: str
    component: str
    operation: str
    attempt: int
    timestamp_ms: Optional[int]
    context: Dict[str, str]
    checkpoint: Optional[str] = None
    detail: Optional[str] = None
    next_attempt: Optional[int] = None
    error_type: Optional[str] = None
    error_message: Optional[str] = None


def parse_phase_event(line: str) -> Optional[HarnessPhaseEvent]:
    stripped = line.strip()
    if not stripped.startswith(HARNESS_PHASE_PREFIX):
        return None
    payload = stripped[len(HARNESS_PHASE_PREFIX) :].strip()
    if not payload:
        return None
    try:
        data = json.loads(payload)
    except json.JSONDecodeError:
        return None
    if data.get("event") != "harness_phase":
        return None

    type_value = data.get("type")
    component = data.get("component")
    operation = data.get("operation")
    attempt_value = data.get("attempt")
    if not isinstance(type_value, str) or not type_value:
        return None
    if not isinstance(component, str) or not component:
        return None
    if not isinstance(operation, str) or not operation:
        return None

    attempt = _coerce_int(attempt_value)
    if attempt is None:
        return None

    context_raw = data.get("context") or {}
    context: Dict[str, str] = {}
    if isinstance(context_raw, dict):
        for key, value in context_raw.items():
            if not isinstance(key, str):
                continue
            if isinstance(value, (str, int, float, bool)):
                context[key] = str(value)

    return HarnessPhaseEvent(
        type=type_value,
        component=component,
        operation=operation,
        attempt=attempt,
        timestamp_ms=_coerce_int(data.get("timestampMs")),
        context=context,
        checkpoint=data.get("checkpoint"),
        detail=data.get("detail"),
        next_attempt=_coerce_int(data.get("nextAttempt")),
        error_type=data.get("errorType"),
        error_message=data.get("errorMessage"),
    )


def _coerce_int(value: object) -> Optional[int]:
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return None
    return None


def _format_context(context: Dict[str, str]) -> str:
    if not context:
        return ""
    return ", ".join(f"{key}={value}" for key, value in sorted(context.items()))


def load_harness_environment(path: Path = HARNESS_ENV_PATH) -> None:
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return
    except OSError as error:  # pragma: no cover - defensive
        print(
            f"Failed to read screenshot harness environment from {path}: {error}",
            file=sys.stderr,
        )
        return

    for index, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in stripped:
            print(
                f"Ignoring invalid screenshot harness environment entry on line {index}: {stripped}",
                file=sys.stderr,
            )
            continue
        key, value = stripped.split("=", 1)
        key = key.strip()
        if not key:
            continue
        os.environ.setdefault(key, value.strip())


def _compile_wildcard_regex(pattern: str) -> Optional[re.Pattern[str]]:
    if "*" not in pattern:
        return None
    try:
        return re.compile(f"^{re.escape(pattern).replace(r'\\*', '.*')}$")
    except re.error:
        return None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--adb",
        default="adb",
        help="Path to the adb executable (default: %(default)s)",
    )
    parser.add_argument(
        "--serial",
        help="ADB device serial to target",
    )
    parser.add_argument(
        "--instrumentation",
        default="com.novapdf.reader.test/dagger.hilt.android.testing.HiltTestRunner",
        help="Fully qualified instrumentation component to execute",
    )
    parser.add_argument(
        "--test",
        default="com.novapdf.reader.ScreenshotHarnessTest#openThousandPageDocumentForScreenshots",
        help="Fully qualified test method to invoke",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("screenshots"),
        help="Directory where screenshots will be written (default: %(default)s)",
    )
    parser.add_argument(
        "--document-factory",
        help=(
            "Fully qualified class implementing HarnessDocumentFactory to override the document "
            "opened by the harness"
        ),
    )
    parser.add_argument(
        "--storage-client-factory",
        help=(
            "Fully qualified class implementing HarnessStorageClientFactory to override the "
            "storage client used by the harness"
        ),
    )
    parser.add_argument(
        "--extra-arg",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="Additional key=value arguments forwarded to the instrumentation",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=600,
        help="Timeout in seconds for the instrumentation run (default: %(default)s)",
    )
    parser.add_argument(
        "--skip-auto-install",
        action="store_true",
        help=(
            "Skip the automatic Gradle installation of the debug and androidTest APKs "
            "when the screenshot harness instrumentation is missing"
        ),
    )
    parser.add_argument(
        "--test-package",
        help=(
            "Explicit test application package name to pass to the screenshot harness. "
            "Falls back to the NOVAPDF_SCREENSHOT_TEST_PACKAGE environment variable or "
            "the resolved instrumentation component when omitted."
        ),
    )
    args = parser.parse_args()

    normalized_component = _normalize_instrumentation_component(
        args, args.instrumentation
    )
    if normalized_component != args.instrumentation:
        print(
            "Normalizing requested instrumentation component "
            f"{args.instrumentation} -> {normalized_component}",
            file=sys.stderr,
        )
        args.instrumentation = normalized_component

    return args


class HarnessStartTimeout(RuntimeError):
    """Raised when the instrumentation fails to emit output within the startup deadline."""


def adb_command(args: argparse.Namespace, *cmd: str, text: bool = True, **kwargs):
    base_cmd: List[str] = [args.adb]
    if args.serial:
        base_cmd.extend(["-s", args.serial])
    base_cmd.extend(cmd)
    return subprocess.run(base_cmd, check=True, text=text, **kwargs)


def adb_command_output(args: argparse.Namespace, *cmd: str) -> str:
    result = adb_command(args, *cmd, capture_output=True)
    return result.stdout


def _resolve_sanitized_package(
    args: Optional[argparse.Namespace], pattern: str
) -> Optional[str]:
    if args is None:
        return None

    glob = re.escape(pattern).replace(r"\*", ".*")
    try:
        output = adb_command_output(args, "shell", "pm", "list", "packages")
    except subprocess.CalledProcessError:
        return None

    regex = re.compile(f"^{glob}$")
    matches: List[str] = []
    for line in output.splitlines():
        stripped = line.strip()
        if not stripped.startswith("package:"):
            continue
        package_name = stripped[len("package:") :].strip()
        if regex.match(package_name):
            matches.append(package_name)

    if not matches:
        return None

    if len(matches) > 1:
        suffix = pattern.split("*")[-1]
        if suffix:
            for match in matches:
                if match.endswith(suffix):
                    return match

    return matches[0]


def normalize_package_name(
    candidate: str, *, args: Optional[argparse.Namespace] = None
) -> Optional[str]:
    value = candidate.strip()
    if not value:
        return None

    if PACKAGE_NAME_PATTERN.match(value):
        return value

    if "*" in value:
        resolved = _resolve_sanitized_package(args, value)
        if resolved:
            return resolved

    sanitized = re.sub(r"[^A-Za-z0-9._]", "", value)
    if sanitized and PACKAGE_NAME_PATTERN.match(sanitized):
        return sanitized

    return None


def _resolve_sanitized_instrumentation_component(
    args: Optional[argparse.Namespace], pattern: str
) -> Optional[str]:
    if args is None:
        return None

    glob = re.escape(pattern).replace(r"\*", ".*")
    try:
        output = adb_command_output(args, "shell", "pm", "list", "instrumentation")
    except subprocess.CalledProcessError:
        return None

    regex = re.compile(f"^{glob}$")
    matches: List[str] = []
    for line in output.splitlines():
        stripped = line.strip()
        if not stripped.startswith("instrumentation:"):
            continue
        component = stripped[len("instrumentation:") :].split(" ", 1)[0].strip()
        if component and regex.match(component):
            matches.append(component)

    if not matches:
        return None

    if len(matches) == 1:
        return matches[0]

    suffix = pattern.split("/", 1)[-1]
    if suffix:
        suffix_regex = re.escape(suffix).replace(r"\*", ".*")
        try:
            runner_regex = re.compile(f"{suffix_regex}$")
        except re.error:
            runner_regex = None
        if runner_regex:
            for match in matches:
                if runner_regex.search(match):
                    return match

    return matches[0]


def _normalize_instrumentation_component(
    args: argparse.Namespace, component: str
) -> str:
    package, separator, runner = component.partition("/")
    if not package:
        return component

    normalized_package = normalize_package_name(package, args=args)
    if normalized_package and normalized_package != package:
        print(
            f"Resolved sanitized instrumentation package {package} to {normalized_package}",
            file=sys.stderr,
        )
        if separator:
            return f"{normalized_package}/{runner}"
        return normalized_package

    if "*" in package:
        resolved = _resolve_sanitized_instrumentation_component(args, component)
        if resolved:
            return resolved

    return component


def _prefer_requested_instrumentation_component(
    args: argparse.Namespace, requested: str, resolved: str
) -> str:
    """Prefer the caller requested component when the resolved one is sanitized."""

    if not resolved:
        return resolved

    resolved_package, _, _ = resolved.partition("/")
    if PACKAGE_NAME_PATTERN.match(resolved_package) and "*" not in resolved_package:
        return resolved

    requested = requested.strip()
    if not requested:
        return resolved

    requested_normalized = _normalize_instrumentation_component(args, requested)
    requested_package, _, _ = requested_normalized.partition("/")
    if not requested_package or "*" in requested_package:
        return resolved

    if requested_normalized != resolved:
        print(
            "Resolved instrumentation component contains sanitized package name; "
            f"using requested component {requested_normalized}",
            file=sys.stderr,
        )
    return requested_normalized


def launch_instrumentation(
    args: argparse.Namespace,
    extra_args: Iterable[Tuple[str, str]],
    component: str,
    *,
    test_override: Optional[str] = None,
) -> subprocess.Popen:
    if not component:
        raise RuntimeError(
            "Screenshot harness instrumentation is not installed on the target device"
        )
    command: List[str] = [args.adb]
    if args.serial:
        command.extend(["-s", args.serial])
    instrumentation_cmd = [
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "runScreenshotHarness",
        "true",
        "-e",
        "captureProgrammaticScreenshots",
        "false",
        "-e",
        "class",
        test_override or args.test,
    ]
    for key, value in extra_args:
        instrumentation_cmd.extend(["-e", key, value])
    instrumentation_cmd.append(component)
    command.extend(instrumentation_cmd)
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    return process


def _extract_instrumentation_components(output: str) -> List[str]:
    components: List[str] = []
    for line in output.splitlines():
        stripped = line.strip()
        if not stripped.startswith("instrumentation:"):
            continue
        candidate = stripped[len("instrumentation:") :].split(" ", 1)[0].strip()
        if candidate:
            components.append(candidate)
    return components


def _extract_instrumentation_target(
    output: str, component: str
) -> Optional[str]:
    target_package: Optional[str] = None
    current: Optional[str] = None
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("Instrumentation "):
            current = stripped[len("Instrumentation ") :].split(":", 1)[0]
            continue
        if current != component:
            continue
        if stripped.startswith("targetPackage="):
            candidate = stripped[len("targetPackage=") :].strip()
            if candidate:
                target_package = candidate
                break
    return target_package


def ensure_instrumentation_target_installed(
    args: argparse.Namespace, component: str
) -> None:
    package, _slash, _runner = component.partition("/")
    if not package:
        return
    try:
        dumpsys_output = adb_command_output(
            args, "shell", "dumpsys", "package", package
        )
    except subprocess.CalledProcessError:
        return

    target_package = _extract_instrumentation_target(dumpsys_output, component)
    if not target_package:
        return

    try:
        adb_command_output(args, "shell", "pm", "path", target_package)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(
            "Screenshot harness target package "
            f"{target_package} is not installed; install the app APK "
            "(for example with `./gradlew :app:installDebug`) before running the harness."
        ) from error


def _resolve_instrumentation_component_once(
    args: argparse.Namespace,
    requested: str,
    package: str,
    runner: str,
    queries: List[Optional[str]],
    *,
    suppress_guidance: bool,
) -> Optional[str]:
    fallback_component: Optional[str] = None

    package_regex = _compile_wildcard_regex(package) if package else None
    runner_regex = _compile_wildcard_regex(runner) if runner else None

    expected_literal: Optional[str] = None
    expected_regex: Optional[re.Pattern[str]] = None
    if package and runner:
        if not package_regex and not runner_regex:
            expected_literal = f"{package}/{runner}"
        else:
            expected_regex = _compile_wildcard_regex(f"{package}/{runner}")

    for query in queries:
        try:
            if query:
                output = adb_command_output(
                    args, "shell", "pm", "list", "instrumentation", query
                )
            else:
                output = adb_command_output(args, "shell", "pm", "list", "instrumentation")
        except subprocess.CalledProcessError:
            continue

        components = _extract_instrumentation_components(output)
        if not components:
            continue

        for component in components:
            if expected_literal and component == expected_literal:
                return component
            if expected_regex and expected_regex.match(component):
                return component

        if package:
            for component in components:
                component_package, _sep, component_runner = component.partition("/")
                if package_regex:
                    if not package_regex.match(component_package):
                        continue
                elif component_package != package:
                    continue

                if runner:
                    if runner_regex:
                        if runner_regex.match(component_runner):
                            return component
                    elif component_runner == runner:
                        return component
                else:
                    return component

        if not fallback_component:
            fallback_component = components[0]

    if fallback_component:
        if requested and requested != fallback_component:
            print(
                "Requested instrumentation",
                f"{requested} not installed; using {fallback_component}",
                file=sys.stderr,
            )
        return fallback_component

    if package and not suppress_guidance:
        message_lines = [
            "Unable to locate the screenshot harness instrumentation on the device.",
            "Install the debug APKs before capturing screenshots, for example:",
            "  ./gradlew :app:installDebug :app:installDebugAndroidTest",
        ]
        for line in message_lines:
            print(line, file=sys.stderr)
    return None


def _gradle_wrapper_path() -> Optional[Path]:
    project_root = Path(__file__).resolve().parents[1]
    if os.name == "nt":
        candidate = project_root / "gradlew.bat"
    else:
        candidate = project_root / "gradlew"
    if candidate.exists():
        return candidate
    return None


def _parse_optional_bool(value: Optional[str]) -> Optional[bool]:
    if value is None:
        return None
    normalized = value.strip().lower()
    if not normalized:
        return None
    if normalized in {"1", "true", "t", "yes", "y", "on"}:
        return True
    if normalized in {"0", "false", "f", "no", "n", "off"}:
        return False
    return None


def _virtualization_unavailable() -> bool:
    require_connected_device = _parse_optional_bool(
        os.getenv("NOVAPDF_REQUIRE_CONNECTED_DEVICE")
    )
    if require_connected_device is True:
        return False

    nested_virtualization_disabled = _parse_optional_bool(
        os.getenv("ACTIONS_RUNNER_DISABLE_NESTED_VIRTUALIZATION")
    )
    if nested_virtualization_disabled is True:
        return True

    acceleration_preference = os.getenv("ACTIONS_RUNNER_ACCELERATION_PREFERENCE", "").strip()
    if acceleration_preference.lower() in {"software", "none"}:
        return True

    return False


def _emit_missing_instrumentation_error(after_auto_install: bool) -> None:
    suffix = " after Gradle installation" if after_auto_install else ""
    message = (
        "Failed to detect screenshot harness instrumentation component"
        f"{suffix}."
    )
    print(message, file=sys.stderr)
    print(f"::error::{message}", file=sys.stderr)

    if _virtualization_unavailable():
        guidance = (
            "Android emulator virtualization is unavailable in this environment. "
            "Connect a physical device or enable virtualization to install the "
            "screenshot harness."
        )
        print(guidance, file=sys.stderr)
        print(f"::warning::{guidance}", file=sys.stderr)


def auto_install_debug_apks(args: argparse.Namespace) -> bool:
    if getattr(args, "skip_auto_install", False):
        return False

    gradlew = _gradle_wrapper_path()
    if not gradlew:
        print(
            "Unable to locate the Gradle wrapper; skipping automatic debug APK installation.",
            file=sys.stderr,
        )
        return False

    command = [str(gradlew), ":app:installDebug", ":app:installDebugAndroidTest"]
    try:
        print(
            "Installing debug APKs via Gradle to satisfy screenshot harness dependencies...",
            file=sys.stderr,
        )
        subprocess.run(command, cwd=str(gradlew.parent), check=True)
        return True
    except subprocess.CalledProcessError as error:
        print(
            "Gradle installation of debug APKs failed; instrumentation may remain unavailable.",
            file=sys.stderr,
        )
        print(f"Gradle exited with code {error.returncode}", file=sys.stderr)
        return False


def resolve_instrumentation_component(args: argparse.Namespace) -> Optional[str]:
    requested = args.instrumentation.strip()
    package = ""
    runner = ""
    if "/" in requested:
        package, runner = requested.split("/", 1)
    else:
        package, runner = requested, ""
    package = package.strip()
    runner = runner.strip()

    queries: List[Optional[str]] = []
    if package:
        queries.append(package)
    queries.append(None)

    component = _resolve_instrumentation_component_once(
        args,
        requested,
        package,
        runner,
        queries,
        suppress_guidance=not getattr(args, "skip_auto_install", False),
    )
    if component:
        normalized = _normalize_instrumentation_component(args, component)
        return _prefer_requested_instrumentation_component(args, requested, normalized)

    if getattr(args, "skip_auto_install", False):
        return None

    auto_install_result = auto_install_debug_apks(args)
    component = _resolve_instrumentation_component_once(
        args,
        requested,
        package,
        runner,
        queries,
        suppress_guidance=False,
    )
    if component:
        normalized = _normalize_instrumentation_component(args, component)
        return _prefer_requested_instrumentation_component(args, requested, normalized)

    _emit_missing_instrumentation_error(after_auto_install=auto_install_result)
    return None


def sanitize_cache_name(value: str, fallback: Optional[str] = None) -> str:
    candidate = value.strip()
    sanitized = _sanitize(candidate)
    if sanitized:
        return sanitized
    if fallback:
        fallback_sanitized = _sanitize(fallback.strip())
        if fallback_sanitized:
            return fallback_sanitized
    return "document"


def _sanitize(value: str) -> str:
    if not value:
        return ""
    mapped = []
    for char in value:
        if char.isalnum() or char in SAFE_PUNCTUATION:
            mapped.append(char)
        elif char.isspace():
            mapped.append("_")
        else:
            mapped.append("_")
    normalized = "".join(mapped)
    normalized = MULTIPLE_UNDERSCORES.sub("_", normalized)
    normalized = MULTIPLE_PERIODS.sub(".", normalized)
    return normalized.strip("_.")


class HarnessContext:
    def __init__(self, fallback_package: Optional[str] = None, *, args: Optional[argparse.Namespace] = None) -> None:
        self._args: Optional[argparse.Namespace] = args
        self.package: Optional[str] = None
        self.ready_flags: List[str] = []
        self.done_flags: List[str] = []
        self.capture_completed: bool = False
        self.system_crash_detected: bool = False
        self.process_crash_detected: bool = False
        self.missing_instrumentation_detected: bool = False
        self._sanitized_package_warning_emitted: bool = False
        self._system_crash_guidance_emitted: bool = False
        self._missing_instrumentation_guidance_emitted: bool = False
        self.testpoints = TestPointDispatcher()
        self.instrumentation_components: Set[str] = set()
        self._candidate_packages: Set[str] = set()
        self.phase_events: List[HarnessPhaseEvent] = []
        self._phase_attempt_events: Dict[Tuple[str, str, int], List[HarnessPhaseEvent]] = {}
        self._phase_guidance_emitted: bool = False

        if fallback_package:
            self._maybe_set_package(fallback_package, suppress_warning=True)

    def observe_line(self, line: str) -> None:
        stripped = line.strip()
        parsed = parse_testpoint(stripped)
        if parsed:
            point, detail = parsed
            self.testpoints.dispatch(point, detail)
        phase_event = parse_phase_event(stripped)
        if phase_event:
            self._handle_phase_event(phase_event)
        if "System has crashed" in stripped:
            self.system_crash_detected = True
        if stripped.startswith("INSTRUMENTATION_ABORTED") and "System has crashed" in stripped:
            self.system_crash_detected = True
        if "Process crashed" in stripped:
            self.process_crash_detected = True
        if "Unable to find instrumentation info for" in stripped:
            self.missing_instrumentation_detected = True

    def on_testpoint(self, point: HarnessTestPoint, callback: TestPointCallback) -> None:
        self.testpoints.register(point, callback)

    def on_any_testpoint(self, callback: TestPointCallback) -> None:
        self.testpoints.register_any(callback)

    def maybe_emit_system_crash_guidance(self) -> None:
        if not self.system_crash_detected or self._system_crash_guidance_emitted:
            return
        print(
            "Android system_server crashed while running the screenshot harness; capture logcat "
            "artifacts before retrying.",
            file=sys.stderr,
        )
        print(
            "Helpful steps:",
            file=sys.stderr,
        )
        print("  adb logcat -d > logcat.txt", file=sys.stderr)
        print(
            "  tools/check_logcat_for_crashes.py --logcat logcat.txt",
            file=sys.stderr,
        )
        print(
            "Restart the emulator or device once diagnostics are collected, then rerun the harness.",
            file=sys.stderr,
        )
        self._system_crash_guidance_emitted = True

    def maybe_emit_missing_instrumentation_guidance(self) -> None:
        if not self.missing_instrumentation_detected or self._missing_instrumentation_guidance_emitted:
            return
        print(
            "Screenshot harness instrumentation is not installed on the target device.",
            file=sys.stderr,
        )
        print(
            "Install the debug APKs before capturing screenshots, for example:",
            file=sys.stderr,
        )
        print(
            "  ./gradlew :app:installDebug :app:installDebugAndroidTest",
            file=sys.stderr,
        )
        self._missing_instrumentation_guidance_emitted = True

    def maybe_emit_phase_guidance(self) -> None:
        if self._phase_guidance_emitted or not self._phase_attempt_events:
            return
        print("Harness phase timeline:", file=sys.stderr)
        for (component, operation, attempt), events in sorted(
            self._phase_attempt_events.items(),
            key=lambda item: (item[0][0], item[0][1], item[0][2]),
        ):
            phases = " -> ".join(event.type for event in events)
            print(
                f"  {component}.{operation} attempt {attempt}: {phases}",
                file=sys.stderr,
            )
            final = events[-1]
            context_event = next((event for event in reversed(events) if event.context), final)
            context = _format_context(context_event.context)
            if context:
                print(f"    context: {context}", file=sys.stderr)
            checkpoint_event = next(
                (event for event in reversed(events) if event.checkpoint), None
            )
            if checkpoint_event and checkpoint_event.checkpoint:
                print(f"    checkpoint: {checkpoint_event.checkpoint}", file=sys.stderr)
            detail_event = next((event for event in reversed(events) if event.detail), None)
            if detail_event and detail_event.detail:
                print(f"    detail: {detail_event.detail}", file=sys.stderr)
            error_event = next(
                (
                    event
                    for event in reversed(events)
                    if event.error_type or event.error_message
                ),
                None,
            )
            if error_event:
                print(
                    "    error: {}: {}".format(
                        error_event.error_type or "<unknown>",
                        error_event.error_message or "<none>",
                    ),
                    file=sys.stderr,
                )
            if final.type == "retry" and final.next_attempt:
                print(
                    f"    next attempt: {final.next_attempt}",
                    file=sys.stderr,
                )
        self._phase_guidance_emitted = True

    def maybe_collect_ready_flag(self, line: str) -> None:
        match = re.search(r"Writing screenshot ready flag to (.+)", line)
        if match:
            path = match.group(1).strip()
            if path and path not in self.ready_flags:
                self.ready_flags.append(path)

    def maybe_collect_done_flags(self, line: str) -> None:
        match = re.search(r"completion signal at (.+)", line)
        if not match:
            return
        raw = match.group(1)
        candidates = [candidate.strip() for candidate in raw.split(",")]
        self.done_flags = [candidate for candidate in candidates if candidate]

    def maybe_collect_package(self, line: str) -> None:
        match = re.search(r"Resolved screenshot harness package name: (.+)", line)
        if match:
            candidate = match.group(1).strip()
            if not candidate:
                return
            if self._maybe_set_package(candidate):
                return
            if not self._sanitized_package_warning_emitted:
                fallback = self.package or "<unknown>"
                print(
                    "Unable to determine screenshot harness package from instrumentation output; "
                    f"continuing with {fallback}",
                    file=sys.stderr,
                )
                self._sanitized_package_warning_emitted = True

    def _maybe_set_package(self, candidate: str, *, suppress_warning: bool = False) -> bool:
        normalized = self._normalize_package(candidate)
        if normalized:
            self.package = normalized
            self._candidate_packages.add(normalized)
            return True
        if not suppress_warning and not self._sanitized_package_warning_emitted:
            fallback = self.package or candidate or "<unknown>"
            print(
                "Unable to determine screenshot harness package from instrumentation output; "
                f"continuing with {fallback}",
                file=sys.stderr,
            )
            self._sanitized_package_warning_emitted = True
        return False

    def _normalize_package(self, candidate: str) -> Optional[str]:
        value = candidate.strip()
        if not value:
            return None
        normalized = normalize_package_name(value, args=self._args)
        if not normalized:
            return None
        if normalized != value and "*" in value:
            print(
                f"Resolved sanitized screenshot harness package {value} to {normalized}",
                file=sys.stderr,
            )
        return normalized

    def add_candidate_package(self, candidate: str) -> None:
        normalized = self._normalize_package(candidate)
        if normalized:
            self._candidate_packages.add(normalized)

    def register_instrumentation_component(self, component: str) -> None:
        component = component.strip()
        if not component:
            return
        self.instrumentation_components.add(component)
        package = component.split("/", 1)[0].strip()
        if package:
            self.add_candidate_package(package)

    @property
    def candidate_packages(self) -> Set[str]:
        return set(self._candidate_packages)

    def _handle_phase_event(self, event: "HarnessPhaseEvent") -> None:
        self.phase_events.append(event)
        key = (event.component, event.operation, event.attempt)
        self._phase_attempt_events.setdefault(key, []).append(event)
        if event.type in {"abort", "retry"}:
            self._emit_phase_alert(event)

    def _emit_phase_alert(self, event: "HarnessPhaseEvent") -> None:
        headline = (
            f"Harness phase {event.type.upper()}: "
            f"{event.component}.{event.operation} (attempt {event.attempt})"
        )
        if event.checkpoint and event.type == "abort":
            headline = f"{headline} at checkpoint {event.checkpoint}"
        elif event.checkpoint:
            headline = f"{headline} checkpoint {event.checkpoint}"
        if event.detail:
            headline = f"{headline} - {event.detail}"
        print(headline, file=sys.stderr)
        context = _format_context(event.context)
        if context:
            print(f"  context: {context}", file=sys.stderr)
        if event.error_type or event.error_message:
            print(
                "  error: {}: {}".format(
                    event.error_type or "<unknown>",
                    event.error_message or "<none>",
                ),
                file=sys.stderr,
            )
        if event.type == "retry" and event.next_attempt:
            print(
                f"  scheduling retry attempt {event.next_attempt}",
                file=sys.stderr,
            )


def read_ready_payload(args: argparse.Namespace, ctx: HarnessContext) -> Optional[str]:
    if not ctx.package or not ctx.ready_flags:
        return None
    for flag in ctx.ready_flags:
        try:
            output = adb_command_output(
                args, "shell", "run-as", ctx.package, "cat", flag
            ).strip()
        except subprocess.CalledProcessError:
            continue
        if output:
            return output
    return None


def capture_screenshot(args: argparse.Namespace, ctx: HarnessContext, payload: str) -> Path:
    try:
        data = json.loads(payload)
    except json.JSONDecodeError:
        data = {"status": payload}

    document_id = str(data.get("sanitizedDocumentId") or data.get("documentId") or "document")
    sanitized_doc_id = sanitize_cache_name(document_id, fallback=str(data.get("documentId", "document")))
    page_number = int(data.get("pageNumber") or (int(data.get("pageIndex", 0)) + 1))
    file_name = f"{sanitized_doc_id}_page{page_number:04d}.png"

    args.output_dir.mkdir(parents=True, exist_ok=True)
    output_path = args.output_dir / file_name

    capture = adb_command(
        args,
        "exec-out",
        "screencap",
        "-p",
        text=False,
        capture_output=True,
    )
    output_path.write_bytes(capture.stdout)
    return output_path


def signal_completion(args: argparse.Namespace, ctx: HarnessContext) -> None:
    if not ctx.package or not ctx.done_flags:
        return
    for flag in ctx.done_flags:
        try:
            command = f"> {shlex.quote(flag)}"
            adb_command(
                args,
                "shell",
                "run-as",
                ctx.package,
                "sh",
                "-c",
                command,
            )
        except subprocess.CalledProcessError:
            # Fall back to echo if the shell redirection failed.
            adb_command(
                args,
                "shell",
                "run-as",
                ctx.package,
                "sh",
                "-c",
                f"echo done > {shlex.quote(flag)}",
            )


def stream_instrumentation_output(
    process: subprocess.Popen,
    ctx: HarnessContext,
    *,
    start_timeout: int = HARNESS_START_TIMEOUT_SECONDS,
) -> Iterable[str]:
    assert process.stdout is not None

    output_queue: queue.Queue[Optional[str]] = queue.Queue()
    sentinel = object()

    def _reader() -> None:
        try:
            assert process.stdout is not None
            for line in process.stdout:
                output_queue.put(line)
        finally:
            output_queue.put(sentinel)

    reader_thread = threading.Thread(
        target=_reader, name="screenshot-harness-output", daemon=True
    )
    reader_thread.start()

    started = False
    deadline: Optional[float]
    if start_timeout is None:
        deadline = None
    else:
        deadline = time.monotonic() + max(0, start_timeout)

    while True:
        try:
            if not started and deadline is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise HarnessStartTimeout(
                        "Screenshot harness did not emit output within "
                        f"{start_timeout} seconds"
                    )
                item = output_queue.get(timeout=remaining)
            else:
                item = output_queue.get()
        except queue.Empty:  # pragma: no cover - defensive
            raise HarnessStartTimeout(
                "Screenshot harness did not emit output within "
                f"{start_timeout} seconds"
            )

        if item is sentinel:
            break

        started = True
        line = item
        sys.stdout.write(line)
        sys.stdout.flush()
        ctx.observe_line(line)
        yield line


def emit_startup_diagnostics(
    args: argparse.Namespace,
    component: Optional[str],
    ctx: HarnessContext,
) -> None:
    print(
        "Screenshot harness did not reach the startup handshake in time; collecting diagnostics...",
        file=sys.stderr,
    )

    candidates: List[str] = []
    if component:
        package, _slash, _runner = component.partition("/")
        if package:
            candidates.append(package)
    if ctx.package:
        candidates.append(ctx.package)

    unique_candidates: List[str] = []
    for candidate in candidates:
        if candidate and candidate not in unique_candidates:
            unique_candidates.append(candidate)

    if unique_candidates:
        print("Candidate harness packages:", file=sys.stderr)
        for package in unique_candidates:
            try:
                pid_output = adb_command_output(args, "shell", "pidof", package).strip()
            except subprocess.CalledProcessError:
                print(f"  {package}: not running", file=sys.stderr)
                continue
            status = pid_output or "<unknown>"
            print(f"  {package}: pid(s) {status}", file=sys.stderr)

    ps_commands = (("shell", "ps", "-A"), ("shell", "ps"))
    captured_ps = False
    for command in ps_commands:
        try:
            output = adb_command_output(args, *command)
        except subprocess.CalledProcessError:
            continue
        print(f"Process table ({' '.join(command)}):", file=sys.stderr)
        print(output, file=sys.stderr, end="" if output.endswith("\n") else "\n")
        captured_ps = True
        break
    if not captured_ps:
        print("Unable to capture process table from device", file=sys.stderr)

    try:
        logcat_output = adb_command_output(
            args,
            "shell",
            "logcat",
            "-d",
            "-v",
            "brief",
            "-t",
            str(LOGCAT_TAIL_LINES),
        )
    except subprocess.CalledProcessError as error:
        print(f"Failed to capture logcat: {error}", file=sys.stderr)
    else:
        print(
            f"Recent logcat (last {LOGCAT_TAIL_LINES} lines):",
            file=sys.stderr,
        )
        print(
            logcat_output,
            file=sys.stderr,
            end="" if logcat_output.endswith("\n") else "\n",
        )


def collect_native_crash_artifacts(
    args: argparse.Namespace,
    ctx: HarnessContext,
    component: Optional[str],
    reason: str,
) -> None:
    script_path = Path(__file__).resolve().with_name("collect_native_crash_artifacts.sh")
    if not script_path.exists():
        print(
            "Native crash artifact collector missing; skipping tombstone export",
            file=sys.stderr,
        )
        return

    sanitized_reason = _sanitize(reason) or "diagnostics"
    timestamp = time.strftime("%Y%m%d-%H%M%S")

    try:
        NATIVE_CRASH_ARTIFACT_ROOT.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        print(
            f"Unable to prepare native crash artifact directory {NATIVE_CRASH_ARTIFACT_ROOT}: {error}",
            file=sys.stderr,
        )
        return

    output_dir: Optional[Path] = None
    for attempt in range(64):
        suffix = f"-{attempt}" if attempt else ""
        candidate = NATIVE_CRASH_ARTIFACT_ROOT / f"{timestamp}-{sanitized_reason}{suffix}"
        try:
            candidate.mkdir(parents=True, exist_ok=False)
        except FileExistsError:
            continue
        except OSError as error:
            print(
                f"Unable to create native crash artifact directory {candidate}: {error}",
                file=sys.stderr,
            )
            return
        output_dir = candidate
        break

    if output_dir is None:
        fallback = f"{timestamp}-{sanitized_reason}-{int(time.time() * 1000)}"
        output_dir = NATIVE_CRASH_ARTIFACT_ROOT / fallback
        try:
            output_dir.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            print(
                f"Unable to create native crash artifact directory {output_dir}: {error}",
                file=sys.stderr,
            )
            return

    env = os.environ.copy()
    if getattr(args, "serial", None):
        env["ANDROID_SERIAL"] = args.serial
    if ctx.package:
        env.setdefault("PACKAGE_NAME", ctx.package)
    if component:
        test_package = component.split("/", 1)[0].strip()
        if test_package:
            env.setdefault("TEST_PACKAGE_NAME", test_package)
    env.setdefault("COLLECT_NATIVE_LIBS", "false")
    env.setdefault("PDFIUM_ONLY", "false")

    print(
        f"Collecting native crash artifacts after {reason}; saving to {output_dir}",
        file=sys.stderr,
    )

    try:
        result = subprocess.run(
            [str(script_path), str(output_dir)],
            check=False,
            env=env,
        )
    except FileNotFoundError as error:
        print(f"Unable to execute {script_path}: {error}", file=sys.stderr)
        return

    if result.returncode != 0:
        print(
            f"Native crash artifact collection exited with code {result.returncode}",
            file=sys.stderr,
        )


def run_harness_healthcheck(
    args: argparse.Namespace,
    extra_args: Iterable[Tuple[str, str]],
    component: Optional[str],
) -> None:
    if not component:
        return

    print("Attempting harness healthcheck instrumentation run...", file=sys.stderr)
    try:
        process = launch_instrumentation(
            args,
            extra_args,
            component,
            test_override=HARNESS_HEALTHCHECK_TEST,
        )
    except Exception as error:  # pragma: no cover - defensive
        print(f"Unable to launch harness healthcheck: {error}", file=sys.stderr)
        return

    if process.stdout is None:
        print("Harness healthcheck failed to provide output stream", file=sys.stderr)
        return

    try:
        output, _ = process.communicate(timeout=HARNESS_HEALTHCHECK_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        process.kill()
        print("Harness healthcheck timed out before completing", file=sys.stderr)
        return

    if process.returncode not in (0, None):
        print(
            f"Harness healthcheck exited with code {process.returncode}",
            file=sys.stderr,
        )

    if output:
        print("Harness healthcheck output:", file=sys.stderr)
        print(output, file=sys.stderr, end="" if output.endswith("\n") else "\n")
    else:
        print("Harness healthcheck completed without emitting output", file=sys.stderr)


def parse_extra_args(extra_args: Iterable[str]) -> List[Tuple[str, str]]:
    parsed: List[Tuple[str, str]] = []
    for item in extra_args:
        if "=" not in item:
            raise ValueError(f"Invalid --extra-arg entry: {item!r}")
        key, value = item.split("=", 1)
        parsed.append((key, value))
    return parsed


def ensure_test_package_argument(
    args: argparse.Namespace,
    extra_args: Iterable[Tuple[str, str]],
    instrumentation_component: Optional[str],
) -> List[Tuple[str, str]]:
    augmented: List[Tuple[str, str]] = list(extra_args)

    for key, value in augmented:
        if key == "testPackageName" and value.strip():
            return augmented

    explicit = getattr(args, "test_package", None)
    if not explicit:
        explicit = os.environ.get("NOVAPDF_SCREENSHOT_TEST_PACKAGE")

    candidate: Optional[str] = explicit.strip() if explicit else None
    if not candidate:
        candidate = derive_fallback_package(args, augmented, instrumentation_component)

    if not candidate:
        return augmented

    normalized = normalize_package_name(candidate, args=args)
    if normalized and normalized != candidate:
        print(
            f"Resolved screenshot harness test package {candidate} to {normalized}",
            file=sys.stderr,
        )
        candidate = normalized

    if candidate:
        augmented.append(("testPackageName", candidate))
        print(
            "Supplying screenshot harness test package via instrumentation extras: "
            f"{candidate}",
            file=sys.stderr,
        )

    return augmented


def derive_fallback_package(
    args: argparse.Namespace,
    extra_args: Iterable[Tuple[str, str]],
    instrumentation_component: Optional[str] = None,
) -> Optional[str]:
    extras: Dict[str, str] = {key: value for key, value in extra_args}

    explicit = extras.get("testPackageName")
    if explicit:
        explicit = explicit.strip()
        if explicit:
            return explicit

    target_instrumentation = extras.get("targetInstrumentation")
    if target_instrumentation:
        target_package = target_instrumentation.split("/", 1)[0].strip()
        if target_package:
            return target_package

    placeholder = extras.get("novapdfTestAppId")
    if placeholder:
        placeholder = placeholder.strip()
        if placeholder:
            return placeholder

    component_source = instrumentation_component or args.instrumentation
    package_candidate = component_source.split("/", 1)[0].strip()
    if package_candidate:
        return package_candidate

    return None


def _parse_process_listing(output: str) -> List[Tuple[str, str]]:
    processes: List[Tuple[str, str]] = []
    for line in output.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        upper = stripped.upper()
        if upper.startswith("PID") or upper.startswith("USER"):
            continue
        parts = stripped.split(None, 2)
        if len(parts) < 3:
            continue
        pid, _, name = parts
        if pid and name:
            processes.append((pid.strip(), name.strip()))
    return processes


def cleanup_lingering_instrumentation_processes(
    args: argparse.Namespace, ctx: HarnessContext
) -> None:
    packages = sorted(ctx.candidate_packages)
    if not packages:
        return

    try:
        output = adb_command_output(
            args, "shell", "ps", "-A", "-o", "PID,PPID,NAME"
        )
    except subprocess.CalledProcessError as error:
        print(
            f"Unable to inspect device processes for lingering instrumentation: {error}",
            file=sys.stderr,
        )
        return

    processes = _parse_process_listing(output)
    lingering: List[Tuple[str, str, str]] = []
    for pid, name in processes:
        for package in packages:
            if name == package or name.startswith(f"{package}:"):
                lingering.append((package, pid, name))
                break

    if not lingering:
        return

    unique_entries = {(package, name): pid for package, pid, name in lingering}
    for (package, name), pid in unique_entries.items():
        print(
            f"Detected lingering instrumentation process {name} (pid {pid}) for {package}",
            file=sys.stderr,
        )

    packages_to_force_stop = sorted({package for package, _, _ in lingering})
    for package in packages_to_force_stop:
        message = (
            f"Detected lingering instrumentation processes for {package}; force stopping package"
        )
        print(message, file=sys.stderr)
        print(f"::warning::{message}")
        try:
            adb_command(
                args,
                "shell",
                "am",
                "force-stop",
                package,
                text=True,
                check=False,
            )
        except subprocess.CalledProcessError as error:
            print(f"Unable to force-stop {package}: {error}", file=sys.stderr)

    time.sleep(1)

    try:
        post_stop_output = adb_command_output(
            args, "shell", "ps", "-A", "-o", "PID,PPID,NAME"
        )
    except subprocess.CalledProcessError as error:
        print(
            f"Unable to verify lingering instrumentation cleanup: {error}",
            file=sys.stderr,
        )
        return

    remaining = []
    for pid, name in _parse_process_listing(post_stop_output):
        for package in packages:
            if name == package or name.startswith(f"{package}:"):
                remaining.append((package, pid, name))
                break

    if not remaining:
        return

    for package, pid, name in remaining:
        message = (
            f"Lingering instrumentation process {name} (pid {pid}) survived force-stop; sending SIGKILL"
        )
        print(message, file=sys.stderr)
        print(f"::error::{message}")
        try:
            adb_command(
                args,
                "shell",
                "kill",
                "-9",
                pid,
                text=True,
                check=False,
            )
        except subprocess.CalledProcessError as error:
            print(
                f"Unable to terminate instrumentation process {name} (pid {pid}): {error}",
                file=sys.stderr,
            )

    time.sleep(0.5)

    try:
        final_output = adb_command_output(
            args, "shell", "ps", "-A", "-o", "PID,PPID,NAME"
        )
    except subprocess.CalledProcessError:
        return

    stubborn = []
    for pid, name in _parse_process_listing(final_output):
        for package in packages:
            if name == package or name.startswith(f"{package}:"):
                stubborn.append((package, pid, name))
                break

    for package, pid, name in stubborn:
        message = (
            "Instrumentation process {} (pid {}) could not be terminated automatically; "
            "manual cleanup required for {}".format(name, pid, package)
        )
        print(message, file=sys.stderr)
        print(f"::error::{message}")


def run_instrumentation_once(args: argparse.Namespace) -> Tuple[int, HarnessContext]:
    try:
        parsed_extra_args = parse_extra_args(args.extra_arg)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1, HarnessContext(args=args)

    if args.document_factory:
        parsed_extra_args.append(("harnessDocumentFactory", args.document_factory))
    if args.storage_client_factory:
        parsed_extra_args.append(("harnessStorageClientFactory", args.storage_client_factory))

    augmented_extra_args = ensure_test_package_argument(
        args, parsed_extra_args, None
    )

    component = resolve_instrumentation_component(args)
    if component is None:
        return 1, HarnessContext(
            derive_fallback_package(args, augmented_extra_args),
            args=args,
        )

    augmented_extra_args = ensure_test_package_argument(
        args, augmented_extra_args, component
    )

    extras_map: Dict[str, str] = {key: value for key, value in augmented_extra_args}

    try:
        ensure_instrumentation_target_installed(args, component)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1, HarnessContext(
            derive_fallback_package(args, augmented_extra_args, component),
            args=args,
        )

    ctx = HarnessContext(
        derive_fallback_package(args, augmented_extra_args, component),
        args=args,
    )
    ctx.register_instrumentation_component(component)
    test_package_extra = extras_map.get("testPackageName")
    if test_package_extra:
        ctx.add_candidate_package(test_package_extra)
    target_instrumentation_extra = extras_map.get("targetInstrumentation")
    if target_instrumentation_extra:
        ctx.register_instrumentation_component(target_instrumentation_extra)
    process: Optional[subprocess.Popen] = None
    instrumentation_launched = False
    try:
        process = launch_instrumentation(args, augmented_extra_args, component)
        instrumentation_launched = True
    except Exception as error:  # pragma: no cover - defensive
        print(f"Failed to start instrumentation: {error}", file=sys.stderr)
        return 1, ctx

    if process is None or process.stdout is None:
        print("Instrumentation process failed to start", file=sys.stderr)
        return 1, ctx

    return_code: Optional[int] = None
    try:
        try:
            for line in stream_instrumentation_output(
                process, ctx, start_timeout=HARNESS_START_TIMEOUT_SECONDS
            ):
                ctx.maybe_collect_package(line)
                ctx.maybe_collect_ready_flag(line)
                ctx.maybe_collect_done_flags(line)

                if (
                    not ctx.capture_completed
                    and ctx.package
                    and ctx.ready_flags
                    and ctx.done_flags
                ):
                    payload = read_ready_payload(args, ctx)
                    if not payload:
                        continue
                    screenshot_path = capture_screenshot(args, ctx, payload)
                    print(f"Captured screenshot -> {screenshot_path}")
                    signal_completion(args, ctx)
                    ctx.capture_completed = True
        except HarnessStartTimeout as error:
            process.kill()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            print(str(error), file=sys.stderr)
            emit_startup_diagnostics(args, component, ctx)
            reason = (
                "system-server-crash" if ctx.system_crash_detected else "startup-timeout"
            )
            collect_native_crash_artifacts(args, ctx, component, reason)
            run_harness_healthcheck(args, augmented_extra_args, component)
            ctx.maybe_emit_missing_instrumentation_guidance()
            ctx.maybe_emit_system_crash_guidance()
            ctx.maybe_emit_phase_guidance()
            return 1, ctx

        return_code = process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        print("Instrumentation timed out", file=sys.stderr)
        reason = (
            "system-server-crash"
            if ctx.system_crash_detected
            else "instrumentation-timeout"
        )
        collect_native_crash_artifacts(args, ctx, component, reason)
        ctx.maybe_emit_system_crash_guidance()
        ctx.maybe_emit_phase_guidance()
        return 1, ctx
    finally:
        if process and process.stdout:
            process.stdout.close()
        if instrumentation_launched:
            cleanup_lingering_instrumentation_processes(args, ctx)

    if return_code is None:
        print("Instrumentation terminated unexpectedly", file=sys.stderr)
        collect_native_crash_artifacts(
            args, ctx, component, "unexpected-termination"
        )
        ctx.maybe_emit_missing_instrumentation_guidance()
        ctx.maybe_emit_system_crash_guidance()
        ctx.maybe_emit_phase_guidance()
        return 1, ctx

    if return_code != 0:
        print(f"Instrumentation exited with code {return_code}", file=sys.stderr)
        reason: Optional[str] = None
        if ctx.system_crash_detected:
            reason = "system-server-crash"
        elif ctx.process_crash_detected:
            reason = "process-crash"
        if reason:
            collect_native_crash_artifacts(args, ctx, component, reason)
        ctx.maybe_emit_missing_instrumentation_guidance()
        ctx.maybe_emit_system_crash_guidance()
        ctx.maybe_emit_phase_guidance()
        return return_code, ctx

    if not ctx.capture_completed:
        print("Did not capture any screenshots", file=sys.stderr)
        ctx.maybe_emit_missing_instrumentation_guidance()
        ctx.maybe_emit_system_crash_guidance()
        ctx.maybe_emit_phase_guidance()
        return 1, ctx

    return 0, ctx


def wait_for_activity_manager(args: argparse.Namespace, timeout: int = 300) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            adb_command(args, "wait-for-device", text=False)
        except subprocess.CalledProcessError:
            time.sleep(5)
            continue

        try:
            status = adb_command_output(
                args, "shell", "service", "check", "activity"
            ).strip()
        except subprocess.CalledProcessError:
            status = ""
        if status.endswith(": found"):
            return True
        time.sleep(5)
    return False


def main() -> int:
    load_harness_environment()
    args = parse_args()

    max_system_crash_retries = 1
    system_crash_attempts = 0
    auto_install_attempted = getattr(args, "skip_auto_install", False)

    last_exit_code = 1
    while True:
        exit_code, ctx = run_instrumentation_once(args)
        last_exit_code = exit_code
        if exit_code == 0:
            return 0

        missing_instrumentation = ctx.missing_instrumentation_detected
        if (
            missing_instrumentation
            and not getattr(args, "skip_auto_install", False)
            and not auto_install_attempted
        ):
            auto_install_attempted = True
            if auto_install_debug_apks(args):
                continue

        if (
            ctx.system_crash_detected
            and system_crash_attempts < max_system_crash_retries
        ):
            system_crash_attempts += 1
            print(
                "Detected system server crash during instrumentation; waiting for recovery before retrying",
                file=sys.stderr,
            )
            if wait_for_activity_manager(args):
                continue
            print(
                "Unable to verify Activity Manager service after system crash; aborting",
                file=sys.stderr,
            )
            break

        if missing_instrumentation:
            auto_install_attempted = True

        break

    return last_exit_code


if __name__ == "__main__":
    sys.exit(main())
