import pathlib
import tempfile
import unittest
import zipfile

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
        self.assertNotIn(
            "AndroidRuntime reported a fatal exception while instrumentation tests were running",
            issues,
        )

    def test_androidruntime_fatal_exception_for_package_detected(self):
        log = """E AndroidRuntime: FATAL EXCEPTION: main\nProcess: com.example.app, PID: 1234\n"""
        issues = self._find_issues(log)
        self.assertIn(
            "AndroidRuntime reported a fatal exception while instrumentation tests were running",
            issues,
        )

    def test_input_dispatch_timeout_for_package_detected(self):
        log = """
12-10 12:34:56.789  1234  5678 E InputDispatcher: Input dispatching timed out (reason=AppWindowToken{123})
        application is not responding: Window{u0 com.example.app/com.example.app.MainActivity}
12-10 12:34:56.800  1234  5678 I ActivityTaskManager: Displayed com.example.app/.MainActivity
"""
        issues = self._find_issues(log)
        self.assertIn(
            "Detected input dispatch timeout indicative of an ANR during instrumentation tests",
            issues,
        )

    def test_input_dispatch_timeout_for_other_package_ignored(self):
        log = """
12-10 12:34:56.789  1234  5678 E InputDispatcher: Input dispatching timed out (reason=AppWindowToken{123})
        application is not responding: Window{u0 com.android.systemui/com.android.systemui.SliceProvider}
12-10 12:34:56.800  1234  5678 I ActivityTaskManager: Displayed com.example.app/.MainActivity
"""
        issues = self._find_issues(log)
        self.assertNotIn(
            "Detected input dispatch timeout indicative of an ANR during instrumentation tests",
            issues,
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

        self.assertIn(
            "Detected native crash (fatal signal) for com.example.app during instrumentation tests",
            [issue.split(" (source:")[0] for issue in issues],
            msg=f"Expected fatal signal detection in {issues}",
        )


if __name__ == "__main__":
    unittest.main()
