import unittest

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


if __name__ == "__main__":
    unittest.main()
