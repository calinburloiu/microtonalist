# Copyright 2026 Calin-Andrei Burloiu
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.

"""Tests of the scripts in bin/ that wrap another command (the launchers and the coverage scripts), run with:

    python3 -m unittest discover -s bin/tests -p "test_*.py"

Each test copies the scripts into a throwaway repository and puts fake `java` and `sbt` commands first on the `PATH`.
The fakes record the directory they ran in and their arguments.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1]

SCRIPT_TIMEOUT_S = 30.0

FAKE_COMMAND = """#!/bin/bash
{ pwd; printf '%s\\n' "$@"; } > "$FAKE_STATE/$(basename "$0").args"
"""

TARGET_SUFFIX_FLAG = "-Dmicrotonalist.build.targetSuffix=-scoverage"


class WrapperScriptTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="mtlist-wrappers-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

        self.repo = self.tmp / "repo"
        (self.repo / "bin").mkdir(parents=True)
        for script in BIN.glob("mtlist*"):
            shutil.copy2(script, self.repo / "bin" / script.name)
        (self.repo / "project").mkdir()
        (self.repo / "project" / "scala-version").write_text("3.1.4\n")

        self.state = self.tmp / "state"
        self.state.mkdir()
        fake_bin = self.tmp / "fakebin"
        fake_bin.mkdir()
        for name in ["java", "sbt"]:
            path = fake_bin / name
            path.write_text(FAKE_COMMAND)
            path.chmod(0o755)

        self.env = {**os.environ, "PATH": f"{fake_bin}:/usr/bin:/bin:/usr/sbin:/sbin", "FAKE_STATE": str(self.state)}
        # The scripts run from a subdirectory, to check that they don't depend on the working directory.
        self.cwd = self.repo / "project"

    def run_script(self, name: str, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([str(self.repo / "bin" / name), *args], cwd=self.cwd, env=self.env,
                              stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=SCRIPT_TIMEOUT_S)

    def recorded_call(self, command: str) -> tuple[Path, list[str]]:
        """Returns the working directory and the arguments of the fake command's call."""
        path = self.state / f"{command}.args"
        self.assertTrue(path.exists(), f"{command} was not run")
        cwd, *args = path.read_text().splitlines()
        return Path(cwd), args

    def assert_same_path(self, actual: str | Path, expected: Path) -> None:
        self.assertEqual(os.path.realpath(actual), os.path.realpath(expected))

    # --- launchers ---

    def test_mtlist_runs_the_app_jar_built_with_the_scala_version_of_the_build(self):
        # When
        result = self.run_script("mtlist", "song.mtlist")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        _, args = self.recorded_call("java")
        self.assertEqual(args[0], "-jar")
        self.assert_same_path(args[1], self.repo / "app" / "target" / "scala-3.1.4" / "microtonalist-app.jar")
        self.assertEqual(args[2:], ["song.mtlist"])

    def test_mtlist_tool_runs_the_cli_jar_built_with_the_scala_version_of_the_build(self):
        # When
        result = self.run_script("mtlist-tool", "midi", "devices")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        _, args = self.recorded_call("java")
        self.assertEqual(args[0], "-jar")
        self.assert_same_path(args[1], self.repo / "cli" / "target" / "scala-3.1.4" / "microtonalist-cli.jar")
        self.assertEqual(args[2:], ["midi", "devices"])

    # --- coverage ---

    def test_mtlist_coverage_modules_covers_the_given_modules_in_the_scoverage_target(self):
        # When
        result = self.run_script("mtlist-coverage-modules", "tuner", "intonation")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        cwd, args = self.recorded_call("sbt")
        self.assert_same_path(cwd, self.repo)
        self.assertEqual(args, [TARGET_SUFFIX_FLAG, "coverageModules tuner intonation"])

    def test_mtlist_coverage_modules_requires_a_module(self):
        # When
        result = self.run_script("mtlist-coverage-modules")

        # Then
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Usage:", result.stderr)
        self.assertFalse((self.state / "sbt.args").exists())

    def test_mtlist_coverage_all_covers_all_modules_in_the_scoverage_target(self):
        # When
        result = self.run_script("mtlist-coverage-all")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        cwd, args = self.recorded_call("sbt")
        self.assert_same_path(cwd, self.repo)
        self.assertEqual(args, [TARGET_SUFFIX_FLAG, "coverageAll"])

    def test_mtlist_coverage_check_runs_the_ci_coverage_check_in_the_scoverage_target(self):
        # When
        result = self.run_script("mtlist-coverage-check")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        cwd, args = self.recorded_call("sbt")
        self.assert_same_path(cwd, self.repo)
        self.assertEqual(args, [TARGET_SUFFIX_FLAG, "coverageCheck"])

    def test_coverage_scripts_without_modules_reject_arguments(self):
        for name in ["mtlist-coverage-all", "mtlist-coverage-check"]:
            with self.subTest(name):
                # When
                result = self.run_script(name, "tuner")

                # Then
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Usage:", result.stderr)
                self.assertFalse((self.state / "sbt.args").exists())


if __name__ == "__main__":
    unittest.main()
