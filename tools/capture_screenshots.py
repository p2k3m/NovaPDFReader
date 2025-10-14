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


def launch_instrumentation(
    args: argparse.Namespace, extra_args: Iterable[Tuple[str, str]]
) -> subprocess.Popen:
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
    instrumentation_cmd.append(args.instrumentation)
    command.extend(instrumentation_cmd)
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    return process


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


PACKAGE_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._]+$")


class HarnessContext:
    def __init__(self, fallback_package: Optional[str] = None) -> None:
        self.package: Optional[str] = fallback_package
        self.ready_flags: List[str] = []
        self.done_flags: List[str] = []
        self.capture_completed: bool = False
        self.system_crash_detected: bool = False
        self._sanitized_package_warning_emitted: bool = False

    def observe_line(self, line: str) -> None:
        stripped = line.strip()
        if "System has crashed" in stripped:
            self.system_crash_detected = True
        if stripped.startswith("INSTRUMENTATION_ABORTED") and "System has crashed" in stripped:
            self.system_crash_detected = True

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
            if not PACKAGE_NAME_PATTERN.match(candidate):
                if not self._sanitized_package_warning_emitted:
                    fallback = self.package or "<unknown>"
                    print(
                        "Unable to determine screenshot harness package from instrumentation output; "
                        f"continuing with {fallback}",
                        file=sys.stderr,
                    )
                    self._sanitized_package_warning_emitted = True
                return
            self.package = candidate


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
    args: argparse.Namespace, extra_args: Iterable[Tuple[str, str]]
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

    instrumentation_component = args.instrumentation.split("/", 1)[0].strip()
    if instrumentation_component:
        return instrumentation_component

    return None


def run_instrumentation_once(args: argparse.Namespace) -> Tuple[int, HarnessContext]:
    try:
        parsed_extra_args = parse_extra_args(args.extra_arg)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1, HarnessContext()

    ctx = HarnessContext(derive_fallback_package(args, parsed_extra_args))
    process: Optional[subprocess.Popen] = None
    try:
        process = launch_instrumentation(args, parsed_extra_args)
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
        return 1, ctx
    finally:
        if process and process.stdout:
            process.stdout.close()

    if return_code is None:
        print("Instrumentation terminated unexpectedly", file=sys.stderr)
        return 1, ctx

    if return_code != 0:
        print(f"Instrumentation exited with code {return_code}", file=sys.stderr)
        return return_code, ctx

    if not ctx.capture_completed:
        print("Did not capture any screenshots", file=sys.stderr)
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
