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

    def test_sigsegv_detected_in_bugreport_archive(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            archive_path = pathlib.Path(tmpdir) / "bugreport.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(
                    "FS/data/anr/traces.txt",
                    "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0\n",
                )

            issues = check_logcat_for_crashes._scan_logs_for_issues(
                [archive_path],
                self.signatures,
            )

        self.assertTrue(
            any("SIGSEGV" in issue for issue in issues),
            msg=f"Expected SIGSEGV detection in {issues}",
        )


if __name__ == "__main__":
    unittest.main()
