"""Regression checks for bounded, platform-aware benchmark builds."""
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import benchmark_issue81 as benchmark


class BenchmarkBuildTest(unittest.TestCase):
    def test_wrapper_selection(self):
        target = Path("fixture")
        self.assertEqual([str(target / "gradlew.bat")], benchmark.gradle_command(target, "nt"))
        self.assertEqual(["sh", str(target / "gradlew")], benchmark.gradle_command(target, "posix"))

    def test_archive_timeout_is_propagated(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(benchmark, "WORK", Path(folder)), patch.object(
            benchmark.subprocess, "check_output", side_effect=subprocess.TimeoutExpired("git", 120)
        ) as archive:
            with self.assertRaises(subprocess.TimeoutExpired):
                benchmark.build("baseline")
            self.assertEqual(120, archive.call_args.kwargs["timeout"])

    def test_build_timeout_is_propagated_and_log_is_closed(self):
        content = io.BytesIO()
        with zipfile.ZipFile(content, "w") as archive:
            archive.writestr("app/build.gradle.kts", 'applicationIdSuffix = ".debug"')
        with tempfile.TemporaryDirectory() as folder, patch.object(benchmark, "WORK", Path(folder)), patch.object(
            benchmark.subprocess, "check_output", return_value=content.getvalue()
        ), patch.object(benchmark.subprocess, "run", side_effect=subprocess.TimeoutExpired("gradle", 900)) as build:
            with self.assertRaises(subprocess.TimeoutExpired):
                benchmark.build("baseline")
            self.assertEqual(900, build.call_args.kwargs["timeout"])
            self.assertTrue(build.call_args.kwargs["stdout"].closed)


if __name__ == "__main__":
    unittest.main()
