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
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

SAFE_PUNCTUATION = {".", "-", "_"}
MULTIPLE_UNDERSCORES = re.compile(r"_+")
MULTIPLE_PERIODS = re.compile(r"\.+")
PACKAGE_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._]+$")


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
    return parser.parse_args()


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

    return component


def launch_instrumentation(
    args: argparse.Namespace,
    extra_args: Iterable[Tuple[str, str]],
    component: str,
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
        args.test,
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

        if runner and package:
            expected = f"{package}/{runner}"
            for component in components:
                if component == expected:
                    return component

        if package:
            for component in components:
                if component.startswith(f"{package}/"):
                    if runner and component != f"{package}/{runner}":
                        print(
                            "Requested instrumentation",
                            f"{requested or '<unspecified>'} not installed;",
                            f"using {component}",
                            file=sys.stderr,
                        )
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
        return _normalize_instrumentation_component(args, component)

    if getattr(args, "skip_auto_install", False):
        return None

    auto_install_debug_apks(args)
    component = _resolve_instrumentation_component_once(
        args,
        requested,
        package,
        runner,
        queries,
        suppress_guidance=False,
    )
    if component:
        return _normalize_instrumentation_component(args, component)

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
        self.missing_instrumentation_detected: bool = False
        self._sanitized_package_warning_emitted: bool = False
        self._system_crash_guidance_emitted: bool = False
        self._missing_instrumentation_guidance_emitted: bool = False

        if fallback_package:
            self._maybe_set_package(fallback_package, suppress_warning=True)

    def observe_line(self, line: str) -> None:
        stripped = line.strip()
        if "System has crashed" in stripped:
            self.system_crash_detected = True
        if stripped.startswith("INSTRUMENTATION_ABORTED") and "System has crashed" in stripped:
            self.system_crash_detected = True
        if "Unable to find instrumentation info for" in stripped:
            self.missing_instrumentation_detected = True

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
    process: subprocess.Popen, ctx: HarnessContext
) -> Iterable[str]:
    assert process.stdout is not None
    for line in process.stdout:
        sys.stdout.write(line)
        ctx.observe_line(line)
        yield line


def parse_extra_args(extra_args: Iterable[str]) -> List[Tuple[str, str]]:
    parsed: List[Tuple[str, str]] = []
    for item in extra_args:
        if "=" not in item:
            raise ValueError(f"Invalid --extra-arg entry: {item!r}")
        key, value = item.split("=", 1)
        parsed.append((key, value))
    return parsed


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


def run_instrumentation_once(args: argparse.Namespace) -> Tuple[int, HarnessContext]:
    try:
        parsed_extra_args = parse_extra_args(args.extra_arg)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1, HarnessContext(args=args)

    component = resolve_instrumentation_component(args)
    if component is None:
        return 1, HarnessContext(
            derive_fallback_package(args, parsed_extra_args),
            args=args,
        )

    try:
        ensure_instrumentation_target_installed(args, component)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1, HarnessContext(
            derive_fallback_package(args, parsed_extra_args, component),
            args=args,
        )

    ctx = HarnessContext(
        derive_fallback_package(args, parsed_extra_args, component),
        args=args,
    )
    process: Optional[subprocess.Popen] = None
    try:
        process = launch_instrumentation(args, parsed_extra_args, component)
    except Exception as error:  # pragma: no cover - defensive
        print(f"Failed to start instrumentation: {error}", file=sys.stderr)
        return 1, ctx

    if process is None or process.stdout is None:
        print("Instrumentation process failed to start", file=sys.stderr)
        return 1, ctx

    return_code: Optional[int] = None
    try:
        for line in stream_instrumentation_output(process, ctx):
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

        return_code = process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        print("Instrumentation timed out", file=sys.stderr)
        ctx.maybe_emit_system_crash_guidance()
        return 1, ctx
    finally:
        if process and process.stdout:
            process.stdout.close()

    if return_code is None:
        print("Instrumentation terminated unexpectedly", file=sys.stderr)
        ctx.maybe_emit_missing_instrumentation_guidance()
        ctx.maybe_emit_system_crash_guidance()
        return 1, ctx

    if return_code != 0:
        print(f"Instrumentation exited with code {return_code}", file=sys.stderr)
        ctx.maybe_emit_missing_instrumentation_guidance()
        ctx.maybe_emit_system_crash_guidance()
        return return_code, ctx

    if not ctx.capture_completed:
        print("Did not capture any screenshots", file=sys.stderr)
        ctx.maybe_emit_missing_instrumentation_guidance()
        ctx.maybe_emit_system_crash_guidance()
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
    args = parse_args()

    max_system_crash_retries = 1

    last_exit_code = 1
    for attempt in range(max_system_crash_retries + 1):
        exit_code, ctx = run_instrumentation_once(args)
        last_exit_code = exit_code
        if exit_code == 0:
            return 0

        if ctx.system_crash_detected and attempt < max_system_crash_retries:
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

    return last_exit_code


if __name__ == "__main__":
    sys.exit(main())
