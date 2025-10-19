import json
import tarfile
import tempfile
import unittest
from pathlib import Path

from tools import aggregate_ci_logs


def _create_sample_logs(root: Path) -> list[Path]:
    root_a = root / "device_a"
    root_a.mkdir()
    (root_a / "logcat.txt").write_text("logcat output", encoding="utf-8")
    (root_a / "nested").mkdir()
    (root_a / "nested" / "harness.log").write_text("harness output", encoding="utf-8")

    root_b = root / "device_b"
    root_b.mkdir()
    (root_b / "logcat.txt").write_text("second log", encoding="utf-8")

    return [root_a, root_b]


class AggregateCiLogsTests(unittest.TestCase):
    def test_collect_log_files_handles_duplicate_names(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            roots = _create_sample_logs(Path(tmp))
            files = aggregate_ci_logs.collect_log_files(roots)
            relative_paths = sorted(file.relative_path.as_posix() for file in files)
            self.assertIn("device_a/logcat.txt", relative_paths)
            self.assertTrue(any(path.startswith("device_b") for path in relative_paths))
            self.assertEqual(len(files), 3)

    def test_directory_export_writes_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            roots = _create_sample_logs(tmp_path)
            files = aggregate_ci_logs.collect_log_files(roots)
            metadata = aggregate_ci_logs.ExportMetadata(
                device="pixel6", test="connectedAndroidTest", config="release", run_id="12345"
            )
            output_dir = tmp_path / "export"
            destination = aggregate_ci_logs.export_logs(
                metadata,
                files,
                output_dir,
                archive_format="dir",
                manifest_name="manifest.json",
            )

            expected_root = output_dir / metadata.path
            self.assertEqual(destination, expected_root)
            manifest_path = expected_root / "manifest.json"
            self.assertTrue(manifest_path.exists())
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["device"], "pixel6")
            self.assertEqual(manifest["total_files"], 3)

    def test_tar_export_contains_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            roots = _create_sample_logs(tmp_path)
            files = aggregate_ci_logs.collect_log_files(roots)
            metadata = aggregate_ci_logs.ExportMetadata(
                device="emulator", test="screenshot", config="debug", run_id="run-1"
            )
            archive_path = tmp_path / "logs.tar.gz"
            result = aggregate_ci_logs.export_logs(
                metadata,
                files,
                archive_path,
                archive_format="tar",
                manifest_name="manifest.json",
            )

            self.assertEqual(result, archive_path)
            with tarfile.open(archive_path, "r:gz") as archive:
                members = archive.getnames()
                manifest_name = (metadata.path / "manifest.json").as_posix()
                self.assertIn(manifest_name, members)
                extracted_manifest = json.loads(archive.extractfile(manifest_name).read().decode("utf-8"))
                self.assertEqual(extracted_manifest["run_id"], "run-1")

    def test_main_allows_empty_exports(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "empty"
            code = aggregate_ci_logs.main(
                [
                    "--device",
                    "emu",
                    "--test",
                    "none",
                    "--config",
                    "baseline",
                    "--run-id",
                    "0",
                    "--output",
                    str(output),
                    "--allow-missing",
                ]
            )
            self.assertEqual(code, 0)
            manifest = output / "emu" / "none" / "baseline" / "0" / "manifest.json"
            self.assertTrue(manifest.exists())

    def test_main_fails_without_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "fail"
            code = aggregate_ci_logs.main(
                [
                    "--device",
                    "emu",
                    "--test",
                    "none",
                    "--config",
                    "baseline",
                    "--run-id",
                    "0",
                    "--output",
                    str(output),
                ]
            )
            self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
