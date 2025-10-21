import os
import pathlib
import sys
import tempfile
import unittest
import zipfile

from unittest import mock

from tools import check_logcat_for_crashes


class CrashSignatureTests(unittest.TestCase):
    PACKAGE = "com.example.app"

    def setUp(self):
        self.signatures = check_logcat_for_crashes._build_crash_signatures(self.PACKAGE)

    def _find_issues(self, log_text: str):
        return check_logcat_for_crashes._find_issues(log_text, self.signatures)

    def test_androidruntime_fatal_exception_other_process_ignored(self):
        log = """E AndroidRuntime: FATAL EXCEPTION: main\nProcess: com.other.app, PID: 1234\n"""
        issues = self._find_issues(log)
        messages = [message for message, _ in issues]
        self.assertNotIn(
            "AndroidRuntime reported a fatal exception while instrumentation tests were running",
            messages,
        )

    def test_androidruntime_fatal_exception_for_package_detected(self):
        log = """E AndroidRuntime: FATAL EXCEPTION: main\nProcess: com.example.app, PID: 1234\n"""
        issues = self._find_issues(log)
        messages = [message for message, _ in issues]
        self.assertIn(
            "AndroidRuntime reported a fatal exception while instrumentation tests were running",
            messages,
        )
        self.assertTrue(
            any("Process: com.example.app" in snippet for _, snippet in issues),
            msg=f"Expected stack trace snippet in {issues}",
        )

    def test_input_dispatch_timeout_for_package_detected(self):
        log = """
12-10 12:34:56.789  1234  5678 E InputDispatcher: Input dispatching timed out (reason=AppWindowToken{123})
        application is not responding: Window{u0 com.example.app/com.example.app.MainActivity}
12-10 12:34:56.800  1234  5678 I ActivityTaskManager: Displayed com.example.app/.MainActivity
"""
        issues = self._find_issues(log)
        messages = [message for message, _ in issues]
        self.assertIn(
            "Detected input dispatch timeout indicative of an ANR during instrumentation tests",
            messages,
        )
        self.assertTrue(
            any("Input dispatching timed out" in snippet for _, snippet in issues),
            msg=f"Expected ANR snippet in {issues}",
        )

    def test_input_dispatch_timeout_for_other_package_ignored(self):
        log = """
12-10 12:34:56.789  1234  5678 E InputDispatcher: Input dispatching timed out (reason=AppWindowToken{123})
        application is not responding: Window{u0 com.android.systemui/com.android.systemui.SliceProvider}
12-10 12:34:56.800  1234  5678 I ActivityTaskManager: Displayed com.example.app/.MainActivity
"""
        issues = self._find_issues(log)
        messages = [message for message, _ in issues]
        self.assertNotIn(
            "Detected input dispatch timeout indicative of an ANR during instrumentation tests",
            messages,
        )

    def test_sigsegv_detected_in_bugreport_archive(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            archive_path = pathlib.Path(tmpdir) / "bugreport.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(
                    "FS/data/anr/traces.txt",
                    "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0\nProcess name: com.example.app\n",
                )

            issues = check_logcat_for_crashes._scan_logs_for_issues(
                [archive_path],
                self.signatures,
            )

        messages = [issue.message for issue in issues]
        target_message = (
            "Detected native crash (fatal signal) for com.example.app during instrumentation tests"
        )
        self.assertIn(target_message, messages, msg=f"Expected fatal signal detection in {issues}")
        matching_issue = next(issue for issue in issues if issue.message == target_message)
        self.assertIn("Fatal signal 11", matching_issue.snippet)
        self.assertIn("Process name: com.example.app", matching_issue.snippet)
        self.assertTrue(matching_issue.source.endswith("FS/data/anr/traces.txt"))


class MainBehaviorTests(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmpdir.cleanup)
        self.log_path = pathlib.Path(self.tmpdir.name) / "logcat.txt"
        self.log_path.write_text(
            "10-21 17:20:45.927   348   348 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 348 (main), pid 348 (main)\n"
            "Process name: com.novapdf.reader\n",
            encoding="utf-8",
        )

    def test_main_skips_when_virtualization_unavailable_env(self):
        argv = ["check_logcat_for_crashes.py", str(self.log_path)]
        with mock.patch.object(sys, "argv", argv), mock.patch.dict(
            os.environ,
            {"ACTIONS_RUNNER_DISABLE_NESTED_VIRTUALIZATION": "true"},
            clear=True,
        ):
            exit_code = check_logcat_for_crashes.main()

        self.assertEqual(exit_code, 0)

    def test_main_reports_crash_when_virtualization_forced_available(self):
        argv = [
            "check_logcat_for_crashes.py",
            str(self.log_path),
            "--virtualization-available",
        ]
        with mock.patch.object(sys, "argv", argv), mock.patch.dict(
            os.environ,
            {"ACTIONS_RUNNER_DISABLE_NESTED_VIRTUALIZATION": "true"},
            clear=True,
        ):
            exit_code = check_logcat_for_crashes.main()

        self.assertEqual(exit_code, 1)


if __name__ == "__main__":
    unittest.main()
