import pathlib

import pytest

from tools.scripts.verify_log_markers import main as verify_main, verify_logs


def _write(path: pathlib.Path, contents: str) -> pathlib.Path:
    path.write_text(contents, encoding="utf-8")
    return path


def test_verify_logs_pass(tmp_path: pathlib.Path) -> None:
    log = _write(tmp_path / "logcat.txt", "TestRunner: run finished: 3 tests\nINSTRUMENTATION_STATUS_CODE: -1\n")

    failures = verify_logs([log], required=["INSTRUMENTATION_STATUS_CODE: -1"], require_any=[["TestRunner: run finished"]])

    assert not failures


def test_verify_logs_fail_when_marker_missing(tmp_path: pathlib.Path) -> None:
    log = _write(tmp_path / "logcat.txt", "TestRunner: run finished: 3 tests\n")

    failures = verify_logs([log], required=["INSTRUMENTATION_STATUS_CODE: -1"], require_any=[])

    assert failures
    assert failures[0].path == log
    assert "INSTRUMENTATION_STATUS_CODE: -1" in failures[0].missing


def test_cli_returns_non_zero_for_missing_marker(tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch) -> None:
    log = _write(tmp_path / "logcat.txt", "TestRunner: run finished: 3 tests\n")

    argv = ["--path", str(log), "--require", "INSTRUMENTATION_STATUS_CODE: -1"]
    exit_code = verify_main(argv)

    assert exit_code == 1


def test_cli_supports_alternative_markers(tmp_path: pathlib.Path) -> None:
    log = _write(tmp_path / "logcat.txt", "Instrumentation run finished at 12:00\n")

    argv = [
        "--path",
        str(log),
        "--require-any",
        "TestRunner: run finished||Instrumentation run finished",
    ]
    exit_code = verify_main(argv)

    assert exit_code == 0
