import json
import tempfile
import unittest
from pathlib import Path

from tools.scripts import retire_performance_results as retention


class RetirePerformanceResultsTests(unittest.TestCase):
    def _create_run(self, root: Path, name: str, recorded_at: str) -> Path:
        run_dir = root / name
        run_dir.mkdir()
        (run_dir / "metadata.json").write_text(
            json.dumps(
                {
                    "recorded_at": recorded_at,
                    "artifacts": {
                        "baseline_profile": "baseline-prof.txt",
                        "benchmarks": "benchmarks.json",
                    },
                }
            ),
            encoding="utf-8",
        )
        (run_dir / "benchmarks.json").write_text("{}", encoding="utf-8")
        (run_dir / "baseline-prof.txt").write_text("PROFILE", encoding="utf-8")
        return run_dir

    def test_retire_runs_removes_entries_beyond_retention(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old_run = self._create_run(root, "2024-05-01T10-00-00Z", "2024-05-01T10:00:00Z")
            recent_run = self._create_run(root, "2024-11-15T09-30-00Z", "2024-11-15T09:30:00Z")

            report = retention.retire_runs(
                root,
                retention_days=90,
                now=retention._parse_timestamp("2024-12-15T00:00:00Z"),
            )

            self.assertFalse(old_run.exists())
            self.assertTrue(recent_run.exists())
            self.assertEqual([info.path for info in report.removed], [old_run])
            self.assertEqual([info.path for info in report.kept], [recent_run])
            self.assertEqual(report.skipped, [])

    def test_dry_run_preserves_directories(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old_run = self._create_run(root, "2024-06-01T08-00-00Z", "2024-06-01T08:00:00Z")

            report = retention.retire_runs(
                root,
                retention_days=90,
                now=retention._parse_timestamp("2024-10-15T00:00:00Z"),
                dry_run=True,
            )

            self.assertTrue(old_run.exists())
            self.assertEqual([info.path for info in report.removed], [old_run])

    def test_skips_invalid_metadata_but_uses_directory_timestamp(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fallback = root / "2024-09-20T14-21-00Z"
            fallback.mkdir()
            invalid = root / "broken-run"
            invalid.mkdir()
            (invalid / "metadata.json").write_text("not-json", encoding="utf-8")

            report = retention.retire_runs(
                root,
                retention_days=90,
                now=retention._parse_timestamp("2024-12-01T00:00:00Z"),
            )

            # Fallback directory should remain because its timestamp is within retention.
            self.assertTrue(fallback.exists())
            self.assertEqual([info.path for info in report.kept], [fallback])
            self.assertTrue(any(path == invalid for path, _ in report.skipped))


if __name__ == "__main__":
    unittest.main()
