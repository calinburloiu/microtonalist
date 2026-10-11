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

"""Tests of microtonalist-gh, run with:

    python3 -m unittest discover -s .claude/skills/contributing/scripts/tests -p "test_*.py"

Each test puts fake `gh` and `git` commands first on the `PATH`. They record their arguments, and answer the queries the
script makes as a GitHub repository on a `doc/` branch would. The environment variables `FAKE_PARENT_ISSUE` and
`FAKE_ISSUE_EDIT_FAILS` make `gh` report a parent issue and fail to edit an issue.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "microtonalist-gh"

SCRIPT_TIMEOUT_S = 30.0

# The arguments of each call are recorded on one line, separated by the ASCII unit separator.
FAKE_GH = """#!/bin/bash
(IFS=$'\\x1f'; printf '%s\\n' "$*") >> "$FAKE_STATE/gh.calls"
case "$1 $2" in
  "pr create") echo "https://github.com/calinburloiu/microtonalist/pull/999" ;;
  "api graphql")
    if [[ -n "${FAKE_PARENT_ISSUE:-}" ]]; then
      echo "$FAKE_PARENT_ISSUE"
    fi ;;
  "issue edit")
    if [[ -n "${FAKE_ISSUE_EDIT_FAILS:-}" ]]; then
      echo "GraphQL: Could not resolve to an issue or pull request with the number of $3." >&2
      exit 1
    fi ;;
esac
"""

FAKE_GIT = """#!/bin/bash
case "$*" in
  "rev-parse --abbrev-ref HEAD") echo "doc/some-change" ;;
  "remote") echo "origin" ;;
  "remote get-url origin") echo "git@github.com:calinburloiu/microtonalist.git" ;;
  push*) printf '%s\\n' "$*" >> "$FAKE_STATE/git.calls" ;;
esac
"""

PR_URL = "https://github.com/calinburloiu/microtonalist/pull/999"

ASSIGN_ISSUE_33 = ["issue", "edit", "33", "--add-assignee", "@me"]


class PrTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="microtonalist-gh-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

        self.state = self.tmp / "state"
        self.state.mkdir()
        fake_bin = self.tmp / "fakebin"
        fake_bin.mkdir()
        for name, text in [("gh", FAKE_GH), ("git", FAKE_GIT)]:
            fake = fake_bin / name
            fake.write_text(text)
            fake.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{fake_bin}{os.pathsep}{os.environ['PATH']}", FAKE_STATE=str(self.state))

    def run_pr(self, *args, **env):
        return subprocess.run(["bash", str(SCRIPT), "pr", *args], cwd=self.tmp, env=dict(self.env, **env),
                              capture_output=True, text=True, timeout=SCRIPT_TIMEOUT_S)

    def recorded_calls(self, name):
        calls_file = self.state / f"{name}.calls"
        return calls_file.read_text().splitlines() if calls_file.exists() else []

    def issue_edits(self):
        return [call.split("\x1f") for call in self.recorded_calls("gh") if call.startswith("issue\x1fedit\x1f")]

    def pr_creates(self):
        return [call for call in self.recorded_calls("gh") if call.startswith("pr\x1fcreate\x1f")]

    def test_assigns_the_linked_issue_to_the_current_user(self):
        # When
        result = self.run_pr("33", "Add a feature")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.issue_edits(), [ASSIGN_ISSUE_33])
        self.assertIn("assigning issue #33 to you", result.stderr)
        self.assertEqual(result.stdout.strip(), PR_URL)

    def test_assigns_a_sub_issue_but_not_its_parent(self):
        # When
        result = self.run_pr("32/33", "Add a feature")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.issue_edits(), [ASSIGN_ISSUE_33])

    def test_assigns_a_sub_issue_but_not_its_auto_detected_parent(self):
        # When
        result = self.run_pr("33", "Add a feature", FAKE_PARENT_ISSUE="32")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.issue_edits(), [ASSIGN_ISSUE_33])

    def test_opens_no_pr_when_the_issue_cannot_be_assigned(self):
        # When
        result = self.run_pr("33", "Add a feature", FAKE_ISSUE_EDIT_FAILS="1")

        # Then
        self.assertEqual(result.returncode, 1)
        self.assertIn("could not assign issue #33 to you, so no PR was opened", result.stderr)
        self.assertEqual(self.recorded_calls("git"), [])
        self.assertEqual(self.pr_creates(), [])

    def test_assigns_no_issue_when_none_is_linked(self):
        # When
        result = self.run_pr("Tidy up the docs")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.issue_edits(), [])

    def test_dry_run_shows_the_issue_assignment_without_making_it(self):
        # When
        result = self.run_pr("33", "Add a feature", "--dry-run")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        assign_line = result.stdout.index("gh issue edit 33 --add-assignee @me")
        self.assertLess(assign_line, result.stdout.index("git push"))
        self.assertEqual(self.issue_edits(), [])


if __name__ == "__main__":
    unittest.main()
