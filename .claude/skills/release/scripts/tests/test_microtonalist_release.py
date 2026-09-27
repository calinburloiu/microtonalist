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

"""Tests of microtonalist_release.py, run with:

    python3 -m unittest discover -s .claude/skills/release/scripts/tests -p "test_*.py"

Command tests run the script against a throwaway repository with a bare `origin` and a fake `gh` (configured through
`git config release.gh`), which answers from canned responses and logs its calls.
"""

import contextlib
import datetime
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import microtonalist_release as release  # noqa: E402

ISSUES = "https://github.com/calinburloiu/microtonalist/issues/"
TODAY = datetime.date.today().isoformat()

BUILD_SBT = textwrap.dedent("""\
    ThisBuild / scalaVersion := "3.3.1"
    ThisBuild / version := "{version}"
    ThisBuild / organization := "org.calinburloiu.music"

    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    """)

NOTES_V150 = textwrap.dedent(f"""\
    # Release notes

    The notes of every release, newest first.

    ## v1.5.0 (2026-09-22)

    This release brings **hot plugging**.

    ### User-facing changes

    - **Hot plugging** ([#131]({ISSUES}131)).

    ### Known issues

    - A track fed by another track doesn't react to devices ([#316]({ISSUES}316)).
    """)

DRAFT_V160 = textwrap.dedent("""\
    ## v1.6.0 (2026-01-01)

    This release keeps the **tuning after a reset**.

    ### User-facing changes

    - **Tuning kept on reset** (#303, [#322](https://github.com/calinburloiu/microtonalist/issues/322)). See `#74`.

    ### Known issues

    - Rebuilt tracks play in 12-EDO (#305).

    """)

FAKE_GH = textwrap.dedent("""\
    #!/usr/bin/env python3
    import json, os, sys
    here = os.path.dirname(os.path.abspath(__file__))
    config = json.load(open(os.path.join(here, "gh-config.json")))
    args = sys.argv[1:]
    record = {"argv": args}
    if args[:2] == ["release", "create"] and "--notes-file" in args:
        record["notes"] = open(args[args.index("--notes-file") + 1]).read()
    with open(os.path.join(here, "gh-calls.jsonl"), "a") as log:
        log.write(json.dumps(record) + "\\n")
    if args[:2] == ["run", "list"]:
        print(json.dumps(config["runs"]))
    elif args[:1] == ["api"]:
        number = args[1].rstrip("/").split("/")[-1]
        if number not in config["issues"]:
            sys.stderr.write("gh: Not Found (HTTP 404)\\n")
            sys.exit(1)
        print(json.dumps(config["issues"][number]))
    elif args[:2] == ["release", "create"]:
        if config.get("release_fails"):
            sys.stderr.write("HTTP 500\\n")
            sys.exit(1)
        print("https://github.com/calinburloiu/microtonalist/releases/tag/" + args[2])
    else:
        sys.stderr.write("unsupported: " + " ".join(args) + "\\n")
        sys.exit(1)
    """)


def issue(number, title, state="closed", body="", labels=(), milestone=None, pull_request=False):
    item = {"number": number, "title": title, "state": state, "body": body,
            "labels": [{"name": name} for name in labels],
            "milestone": {"title": milestone} if milestone else None,
            "html_url": f"https://github.com/calinburloiu/microtonalist/{'pull' if pull_request else 'issues'}/{number}"}
    if pull_request:
        item["pull_request"] = {"merged_at": "2026-09-25T15:40:00Z"}
    return item


def success_runs(sha="HEAD"):
    return [{"workflowName": "Scala CI", "status": "completed", "conclusion": "success", "headSha": sha}]


class RepoFixture(unittest.TestCase):
    """A repository released at v1.5.0 and moved on to 1.6.0-SNAPSHOT with two merged PRs, pushed to its origin."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        self.env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_AUTHOR_NAME="Test", GIT_AUTHOR_EMAIL="test@example.com",
                        GIT_COMMITTER_NAME="Test", GIT_COMMITTER_EMAIL="test@example.com")
        self.remote = os.path.join(self.tmp, "remote.git")
        self.repo = os.path.join(self.tmp, "repo")
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)
        self.gh = os.path.join(self.bin, "gh")
        with open(self.gh, "w") as f:
            f.write(FAKE_GH)
        os.chmod(self.gh, 0o755)
        self.gh_config = {"runs": success_runs(), "issues": {
            "323": issue(323, "Retain the current tuning on reset",
                         body="## Summary\n\nSends `CC #74`, as scalameta/metals#8776 did; remaining in #326 and #326."
                              "\n\nResolves #303\nResolves #322",
                         labels=["bugfix"], pull_request=True),
            "325": issue(325, "Drop the Metals MCP workaround", body="Doc change.\n\nResolves #324", labels=["doc"],
                         pull_request=True),
            "303": issue(303, "Tuning lost when a device reopens", body="The device plays 12-EDO.",
                         labels=["bugfix"], milestone="Track Management"),
            "305": issue(305, "Handle attaching inputs/outputs", state="open", milestone="Track Management"),
            "316": issue(316, "A track fed by another is not released", state="open", milestone="Track Management"),
            "326": issue(326, "Check the tunings when they are loaded", state="open", milestone="Track Management"),
        }}
        self.write_gh_config()

        self.git("init", "-q", "--bare", "-b", "main", self.remote, cwd=self.tmp)
        self.git("init", "-q", "-b", "main", self.repo, cwd=self.tmp)
        self.write("build.sbt", BUILD_SBT.format(version="1.5.0"))
        self.write("docs/release-notes.md", NOTES_V150)
        self.commit("Release v1.5.0")
        self.git("tag", "v1.5.0")
        self.set_version("1.6.0-SNAPSHOT")
        self.commit("Start v1.6.0-SNAPSHOT")
        self.write("Tuner.scala", "// retain\n")
        self.commit("[#305/#322][#303] Retain the current tuning on reset (#323)")
        self.write("AGENTS.md", "docs\n")
        self.commit("[#324] Drop the Metals MCP workaround (#325)")
        self.git("config", "release.gh", self.gh)
        self.git("remote", "add", "origin", self.remote)
        self.git("push", "-q", "origin", "main", "--tags")
        self.git("branch", "-q", "-u", "origin/main")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *args, cwd=None):
        result = subprocess.run(["git"] + list(args), cwd=cwd or self.repo, env=self.env, capture_output=True,
                                text=True)
        if result.returncode != 0:
            raise AssertionError(f"git {' '.join(args)} failed: {result.stderr}")
        return result.stdout.strip()

    def remote_git(self, *args):
        return self.git("--git-dir", self.remote, *args, cwd=self.tmp)

    def write(self, path, content):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(content)

    def read(self, path):
        with open(os.path.join(self.repo, path)) as f:
            return f.read()

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def set_version(self, version):
        self.write("build.sbt", BUILD_SBT.format(version=version))

    def start_snapshot(self, version):
        """Moves main, and its origin, to the development version `version`."""
        self.set_version(version)
        self.commit(f"Start v{version}")
        self.git("push", "-q", "origin", "main")

    def write_gh_config(self):
        with open(os.path.join(self.bin, "gh-config.json"), "w") as f:
            json.dump(self.gh_config, f)

    def gh_calls(self):
        path = os.path.join(self.bin, "gh-calls.jsonl")
        if not os.path.exists(path):
            return []
        with open(path) as f:
            return [json.loads(line) for line in f if line.strip()]

    def add_draft(self, draft=DRAFT_V160):
        notes = self.read("docs/release-notes.md")
        index = notes.index("## v1.5.0")
        self.write("docs/release-notes.md", notes[:index] + draft + notes[index:])

    def run_cli(self, *args, cwd=None):
        """Runs the script's main() in the repository, or in `cwd`; returns (exit code, stdout, stderr)."""
        out, err = io.StringIO(), io.StringIO()
        saved_cwd = os.getcwd()
        os.chdir(cwd or self.repo)
        saved_env = dict(os.environ)
        os.environ.update(self.env)
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = release.main(list(args))
        finally:
            os.chdir(saved_cwd)
            os.environ.clear()
            os.environ.update(saved_env)
        return code, out.getvalue(), err.getvalue()


class VersionTest(unittest.TestCase):

    def test_release_version_accepts_a_leading_v(self):
        # When / Then
        self.assertEqual(release.release_version("v1.6.0"), "1.6.0")
        self.assertEqual(release.release_version("1.6.0"), "1.6.0")

    def test_release_version_rejects_anything_but_major_minor_patch(self):
        # When / Then
        for bad in ("1.6", "1.6.0-SNAPSHOT", "1.6.0.1", "latest", ""):
            with self.subTest(bad=bad), self.assertRaises(release.ReleaseError):
                release.release_version(bad)

    def test_next_version_defaults_to_the_next_minor_snapshot(self):
        # When / Then
        self.assertEqual(release.next_version("1.6.0", None), "1.7.0-SNAPSHOT")
        self.assertEqual(release.next_version("1.5.1", None), "1.6.0-SNAPSHOT")
        self.assertEqual(release.next_version("2.9.3", None), "2.10.0-SNAPSHOT")

    def test_next_version_adds_the_snapshot_suffix_when_missing(self):
        # When / Then
        self.assertEqual(release.next_version("1.6.0", "2.0.0"), "2.0.0-SNAPSHOT")
        self.assertEqual(release.next_version("1.6.0", "v2.0.0-SNAPSHOT"), "2.0.0-SNAPSHOT")

    def test_next_version_keeps_a_higher_snapshot_by_default(self):
        # When / Then
        self.assertEqual(release.next_version("1.5.1", None, "2.0.0-SNAPSHOT"), "2.0.0-SNAPSHOT")
        self.assertEqual(release.next_version("1.5.1", None, "1.6.0-SNAPSHOT"), "1.6.0-SNAPSHOT")
        self.assertEqual(release.next_version("1.6.0", None, "1.6.0-SNAPSHOT"), "1.7.0-SNAPSHOT")
        self.assertEqual(release.next_version("1.5.1", "1.5.2", "2.0.0-SNAPSHOT"), "1.5.2-SNAPSHOT")

    def test_next_version_must_come_after_the_release(self):
        # When / Then
        for bad in ("1.6.0", "1.5.9-SNAPSHOT", "1.6"):
            with self.subTest(bad=bad), self.assertRaises(release.ReleaseError):
                release.next_version("1.6.0", bad)

    def test_latest_release_tag_compares_versions_numerically(self):
        # When / Then
        self.assertEqual(release.latest_release_tag(["v1.9.0", "v1.10.0", "v1.2.1", "v2.0.0-rc1", "other"]), "v1.10.0")
        self.assertIsNone(release.latest_release_tag(["other"]))


class BuildVersionTest(unittest.TestCase):

    def test_build_version_reads_the_this_build_version(self):
        # When / Then
        self.assertEqual(release.build_version(BUILD_SBT.format(version="1.6.0-SNAPSHOT")), "1.6.0-SNAPSHOT")

    def test_with_build_version_changes_only_the_version_line(self):
        # Given
        before = BUILD_SBT.format(version="1.6.0-SNAPSHOT")

        # When
        after = release.with_build_version(before, "1.6.0")

        # Then
        self.assertEqual(after, BUILD_SBT.format(version="1.6.0"))

    def test_with_build_version_requires_exactly_one_version_line(self):
        # When / Then
        for text in ("name := \"x\"\n", BUILD_SBT.format(version="1") + 'ThisBuild / version := "2"\n'):
            with self.subTest(text=text), self.assertRaises(release.ReleaseError):
                release.with_build_version(text, "1.6.0")


class NotesTest(unittest.TestCase):

    def test_linkify_turns_bare_issue_references_into_links(self):
        # When
        text = release.linkify("Fixed (#303, #322). See [#92] and #12.")

        # Then
        self.assertEqual(text, f"Fixed ([#303]({ISSUES}303), [#322]({ISSUES}322)). See [[#92]({ISSUES}92)] and "
                               f"[#12]({ISSUES}12).")

    def test_linkify_links_both_issues_of_a_sub_issue_reference(self):
        # When
        text = release.linkify("Fixed (#305/#322).")

        # Then
        self.assertEqual(text, f"Fixed ([#305]({ISSUES}305)/[#322]({ISSUES}322)).")

    def test_linkify_leaves_links_code_and_anchors_alone(self):
        # Given
        text = textwrap.dedent(f"""\
            Linked [#303]({ISSUES}303), CC `#74`, a [release](https://x.org/releases#release-v1.5.0), a#1, #12abc.
            A [PR #141](https://x.org/pull/141), https://x.org/p?id=#12, <https://x.org/#3>.
            ```
            # not a heading, #99
            ```
            """)

        # When / Then
        self.assertEqual(release.linkify(text), text)

    def test_top_section_returns_the_newest_release_section(self):
        # Given
        notes = NOTES_V150.replace("## v1.5.0", DRAFT_V160 + "## v1.5.0")

        # When
        version, date, section = release.top_section(notes)

        # Then
        self.assertEqual((version, date), ("1.6.0", "2026-01-01"))
        self.assertTrue(section.startswith("## v1.6.0 (2026-01-01)\n"))
        self.assertNotIn("v1.5.0", section)

    def test_top_section_accepts_a_heading_with_a_placeholder_date(self):
        # Given
        notes = NOTES_V150.replace("## v1.5.0", "## v1.6.0 (YYYY-MM-DD)\n\nIntro.\n\n## v1.5.0")

        # When
        version, _, section = release.top_section(notes)

        # Then
        self.assertEqual(version, "1.6.0")
        self.assertEqual(section, "## v1.6.0 (YYYY-MM-DD)\n\nIntro.\n\n")

    def test_github_release_body_drops_the_heading_and_raises_subheadings(self):
        # Given
        section = "## v1.6.0 (2026-01-01)\n\nIntro.\n\n### Changes\n\n#### Detail\n\n```\n### code\n```\n"

        # When
        body = release.github_release_body(section)

        # Then
        self.assertEqual(body, "Intro.\n\n## Changes\n\n### Detail\n\n```\n### code\n```\n")


class PlanTest(RepoFixture):

    def test_plan_prints_the_release_plan(self):
        # When
        code, out, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)
        self.assertIn("v1.5.0", out)
        self.assertIn("Release v1.6.0", out)
        self.assertIn("Start v1.7.0-SNAPSHOT", out)
        self.assertIn("3 commits", out)

    def test_plan_uses_the_next_version_given(self):
        # When
        code, out, _ = self.run_cli("plan", "1.6.0", "--next", "2.0.0")

        # Then
        self.assertEqual(code, 0)
        self.assertIn("Start v2.0.0-SNAPSHOT", out)

    def test_plan_changes_nothing(self):
        # When
        self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual(self.git("status", "--porcelain"), "")
        self.assertEqual(self.git("tag", "-l", "v1.6.0"), "")

    def test_plan_allows_the_drafted_release_notes(self):
        # Given
        self.add_draft()

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)

    def test_plan_runs_from_a_subdirectory(self):
        # Given
        subdirectory = os.path.join(self.repo, "docs")

        # When
        code, out, err = self.run_cli("plan", "1.6.0", cwd=subdirectory)

        # Then
        self.assertEqual(code, 0, err)
        self.assertIn("Release v1.6.0", out)

    def test_plan_fails_off_main(self):
        # Given
        self.git("switch", "-q", "-c", "feature/x")

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("main", err)

    def test_plan_fails_with_other_changes(self):
        # Given
        self.write("Tuner.scala", "// changed\n")

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("Tuner.scala", err)

    def test_plan_fails_when_behind_origin(self):
        # Given
        other = os.path.join(self.tmp, "other")
        self.git("clone", "-q", self.remote, other, cwd=self.tmp)
        with open(os.path.join(other, "new.txt"), "w") as f:
            f.write("x\n")
        self.git("add", "new.txt", cwd=other)
        self.git("commit", "-q", "-m", "New", cwd=other)
        self.git("push", "-q", "origin", "main", cwd=other)

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("origin/main", err)

    def test_plan_fails_when_ahead_of_origin(self):
        # Given
        self.write("Tuner.scala", "// not pushed\n")
        self.commit("Not pushed")

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("origin/main", err)

    def test_plan_fails_when_the_build_is_not_at_a_snapshot(self):
        # Given
        self.start_snapshot("1.6.0")

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("-SNAPSHOT", err)

    def test_plan_fails_when_the_tag_exists(self):
        # Given
        self.git("tag", "v1.6.0")

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("v1.6.0", err)

    def test_plan_fails_for_a_version_not_after_the_latest_release(self):
        # When
        code, _, err = self.run_cli("plan", "1.5.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("v1.5.0", err)

    def test_plan_fails_when_ci_failed(self):
        # Given
        self.gh_config["runs"] = [{"workflowName": "Scala CI", "status": "completed", "conclusion": "failure",
                                   "headSha": self.base}]
        self.write_gh_config()

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("CI", err)
        self.assertIn("failure", err)

    def test_plan_fails_when_ci_is_still_running(self):
        # Given
        self.gh_config["runs"] = [{"workflowName": "Scala CI", "status": "in_progress", "conclusion": "",
                                   "headSha": self.base}]
        self.write_gh_config()

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("in_progress", err)

    def test_plan_fails_without_a_ci_run(self):
        # Given
        self.gh_config["runs"] = []
        self.write_gh_config()

        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("CI", err)

    def test_plan_queries_ci_for_the_head_commit(self):
        # When
        self.run_cli("plan", "1.6.0")

        # Then
        runs = [c["argv"] for c in self.gh_calls() if c["argv"][:2] == ["run", "list"]]
        self.assertEqual(len(runs), 1)
        self.assertIn(self.base, runs[0])
        self.assertEqual(runs[0][runs[0].index("-R") + 1], "calinburloiu/microtonalist")

    def test_plan_does_not_warn_for_the_snapshot_version(self):
        # When
        code, _, err = self.run_cli("plan", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)
        self.assertNotIn("warning", err)

    def test_plan_does_not_warn_for_a_patch_of_the_previous_release(self):
        # When
        code, _, err = self.run_cli("plan", "1.5.1")

        # Then
        self.assertEqual(code, 0, err)
        self.assertNotIn("warning", err)

    def test_plan_warns_when_the_version_skips_releases(self):
        # When
        code, _, err = self.run_cli("plan", "1.5.3")

        # Then
        self.assertEqual(code, 0, err)
        self.assertIn("warning", err)
        self.assertIn("1.5.1", err)

    def test_plan_keeps_a_higher_snapshot_as_the_next_version_of_a_patch(self):
        # Given
        self.start_snapshot("2.0.0-SNAPSHOT")

        # When
        code, out, err = self.run_cli("plan", "1.5.1")

        # Then
        self.assertEqual(code, 0, err)
        self.assertIn("Start v2.0.0-SNAPSHOT", out)
        self.assertNotIn("warning", err)

    def test_plan_warns_when_the_next_version_given_is_below_the_snapshot(self):
        # Given
        self.start_snapshot("2.0.0-SNAPSHOT")

        # When
        code, _, err = self.run_cli("plan", "1.5.1", "--next", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)
        self.assertIn("warning", err)
        self.assertIn("2.0.0-SNAPSHOT", err)

    def test_plan_warns_when_the_version_skips_the_snapshot(self):
        # When
        code, _, err = self.run_cli("plan", "1.7.0")

        # Then
        self.assertEqual(code, 0, err)
        self.assertIn("warning", err)
        self.assertIn("1.6.0-SNAPSHOT", err)


class ContextTest(RepoFixture):

    def test_context_lists_the_commits_since_the_latest_release(self):
        # When
        code, out, err = self.run_cli("context")

        # Then
        self.assertEqual(code, 0, err)
        self.assertIn("v1.5.0", out)
        self.assertIn("Retain the current tuning on reset (#323)", out)
        self.assertIn("Drop the Metals MCP workaround (#325)", out)
        self.assertNotIn("Release v1.5.0", out)

    def test_context_includes_the_referenced_pull_requests_and_issues(self):
        # When
        _, out, _ = self.run_cli("context")

        # Then
        self.assertIn("#323", out)
        self.assertIn("Resolves #322", out)
        self.assertIn("Tuning lost when a device reopens", out)
        self.assertIn("The device plays 12-EDO.", out)

    def test_context_reports_the_state_of_the_previous_known_issues(self):
        # When
        _, out, _ = self.run_cli("context")

        # Then
        self.assertIn("#316", out)
        self.assertIn("A track fed by another is not released", out)
        self.assertIn("open", out)

    def test_context_says_why_a_known_issue_was_closed(self):
        # Given
        self.gh_config["issues"]["316"].update(state="closed", state_reason="not_planned")
        self.write_gh_config()

        # When
        _, out, _ = self.run_cli("context")

        # Then
        known = out[out.index("# Known issues"):out.index("# Other issues")]
        self.assertIn("#316", known)
        self.assertIn("closed as not planned", known)

    def test_context_asks_github_about_this_repository(self):
        # When
        self.run_cli("context")

        # Then
        paths = [c["argv"][1] for c in self.gh_calls() if c["argv"][:1] == ["api"]]
        self.assertTrue(paths)
        self.assertTrue(all(path.startswith("repos/calinburloiu/microtonalist/issues/") for path in paths), paths)

    def test_context_lists_the_pull_requests_before_the_issues(self):
        # When
        _, out, _ = self.run_cli("context")

        # Then
        self.assertLess(out.index("PR #323"), out.index("Issue #303"))
        self.assertLess(out.index("PR #325"), out.index("Issue #303"))

    def test_context_nests_the_headings_of_descriptions_under_their_item(self):
        # When
        _, out, _ = self.run_cli("context")

        # Then
        self.assertIn("\n#### Summary\n", out)
        self.assertNotIn("\n## Summary\n", out)

    def test_context_lists_each_other_mention_once_and_ignores_code_and_other_repositories(self):
        # When
        _, out, _ = self.run_cli("context")

        # Then
        others = out[out.index("# Other issues"):]
        self.assertEqual(others.count("#326"), 1)
        self.assertNotIn("#74", others)
        self.assertNotIn("#8776", others)

    def test_context_fails_without_a_release_tag(self):
        # Given
        self.git("tag", "-d", "v1.5.0")

        # When
        code, _, err = self.run_cli("context")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("no release tag", err)

    def test_context_survives_references_it_cannot_resolve(self):
        # Given
        del self.gh_config["issues"]["303"]
        self.write_gh_config()

        # When
        code, out, _ = self.run_cli("context")

        # Then
        self.assertEqual(code, 0)
        self.assertIn("#303", out)


class CommitTest(RepoFixture):

    def assert_commit_dates_the_heading(self, heading):
        # Given
        self.add_draft(DRAFT_V160.replace("## v1.6.0 (2026-01-01)", heading))

        # When
        code, _, err = self.run_cli("commit", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)
        notes = self.git("show", "v1.6.0:docs/release-notes.md")
        self.assertIn(f"## v1.6.0 ({TODAY})\n", notes)
        self.assertNotIn(heading + "\n", notes)

    def fail_commits(self, subject_prefix):
        """Makes git refuse the commits whose subject starts with `subject_prefix`."""
        hook = os.path.join(self.repo, ".git", "hooks", "commit-msg")
        with open(hook, "w") as f:
            f.write(f"#!/bin/sh\ngrep -q '^{subject_prefix}' \"$1\" && exit 1\nexit 0\n")
        os.chmod(hook, 0o755)

    def assert_undone(self, code, err):
        self.assertEqual(code, 1)
        self.assertIn("Nothing was committed", err)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual(self.git("tag", "-l", "v1.6.0"), "")
        self.assertEqual(self.read("build.sbt"), BUILD_SBT.format(version="1.6.0-SNAPSHOT"))
        self.assertIn(DRAFT_V160, self.read("docs/release-notes.md"))
        self.assertEqual(self.git("status", "--porcelain"), "M docs/release-notes.md")

    def test_commit_commits_and_tags_the_release_locally_only(self):
        # Given
        self.add_draft()

        # When
        code, out, err = self.run_cli("commit", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)
        log = self.git("log", "--format=%s", f"{self.base}..HEAD").splitlines()
        self.assertEqual(log, ["Start v1.7.0-SNAPSHOT", "Release v1.6.0"])
        self.assertEqual(self.git("rev-parse", "refs/tags/v1.6.0^{commit}"), self.git("rev-parse", "HEAD^"))
        self.assertIn('ThisBuild / version := "1.6.0"', self.git("show", "v1.6.0:build.sbt"))
        self.assertIn('ThisBuild / version := "1.7.0-SNAPSHOT"', self.git("show", "HEAD:build.sbt"))
        self.assertEqual(self.git("show", "--name-only", "--format=", "v1.6.0").split(),
                         ["build.sbt", "docs/release-notes.md"])
        self.assertEqual(self.git("show", "--name-only", "--format=", "HEAD").split(), ["build.sbt"])
        self.assertEqual(self.git("status", "--porcelain"), "")
        self.assertEqual(self.remote_git("rev-parse", "main"), self.base)
        self.assertEqual(self.remote_git("tag", "-l", "v1.6.0"), "")
        self.assertFalse([c for c in self.gh_calls() if c["argv"][:2] == ["release", "create"]])
        self.assertIn("publish 1.6.0", out)

    def test_commit_dates_and_links_the_committed_notes(self):
        # Given
        self.add_draft()

        # When
        self.run_cli("commit", "1.6.0")

        # Then
        notes = self.git("show", "v1.6.0:docs/release-notes.md")
        self.assertIn(f"## v1.6.0 ({TODAY})\n", notes)
        self.assertIn(f"([#303]({ISSUES}303), [#322]({ISSUES}322))", notes)
        self.assertIn(f"([#305]({ISSUES}305))", notes)
        self.assertIn("See `#74`.", notes)
        self.assertTrue(notes.endswith(NOTES_V150[NOTES_V150.index("## v1.5.0"):].rstrip("\n")))

    def test_commit_dates_a_heading_without_a_date(self):
        self.assert_commit_dates_the_heading("## v1.6.0")

    def test_commit_dates_a_heading_with_a_placeholder_date(self):
        self.assert_commit_dates_the_heading("## v1.6.0 (YYYY-MM-DD)")

    def test_commit_keeps_non_ascii_notes_under_a_c_locale(self):
        # Given
        self.add_draft(DRAFT_V160.replace("Rebuilt tracks", "Cireșar tracks – rebuilt"))
        script = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "microtonalist_release.py")
        env = dict(self.env, LC_ALL="C", LANG="C", PYTHONUTF8="0", PYTHONIOENCODING="")

        # When
        result = subprocess.run([sys.executable, script, "commit", "1.6.0"], cwd=self.repo, env=env,
                                capture_output=True, text=True, encoding="utf-8")

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Cireșar tracks – rebuilt", self.git("show", "v1.6.0:docs/release-notes.md"))

    def test_commit_uses_the_next_version_given(self):
        # Given
        self.add_draft()

        # When
        code, _, err = self.run_cli("commit", "1.6.0", "--next", "2.0.0")

        # Then
        self.assertEqual(code, 0, err)
        self.assertEqual(self.git("log", "-1", "--format=%s"), "Start v2.0.0-SNAPSHOT")

    def test_commit_prints_how_to_undo_it_keeping_the_notes(self):
        # Given
        self.add_draft()
        _, out, _ = self.run_cli("commit", "1.6.0")
        undo = [line for line in out.splitlines() if line.startswith("To undo")]
        self.assertEqual(len(undo), 1, out)

        # When
        subprocess.run(undo[0].split(": ", 1)[1], shell=True, check=True, cwd=self.repo, env=self.env,
                       capture_output=True)

        # Then
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual(self.git("tag", "-l", "v1.6.0"), "")
        self.assertEqual(self.git("status", "--porcelain"), "M docs/release-notes.md")
        self.assertIn("## v1.6.0", self.read("docs/release-notes.md"))
        code, _, err = self.run_cli("plan", "1.6.0")
        self.assertEqual(code, 0, err)

    def test_commit_undoes_everything_when_the_release_commit_fails(self):
        # Given
        self.add_draft()
        self.fail_commits("Release")

        # When
        code, _, err = self.run_cli("commit", "1.6.0")

        # Then
        self.assert_undone(code, err)

    def test_commit_undoes_everything_when_the_next_version_commit_fails(self):
        # Given
        self.add_draft()
        self.fail_commits("Start")

        # When
        code, _, err = self.run_cli("commit", "1.6.0")

        # Then
        self.assert_undone(code, err)

    def test_commit_requires_the_release_notes_section(self):
        # When
        code, _, err = self.run_cli("commit", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("## v1.6.0", err)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_commit_changes_nothing_when_a_check_fails(self):
        # Given
        self.add_draft()
        self.gh_config["runs"] = [{"workflowName": "Scala CI", "status": "completed", "conclusion": "failure",
                                   "headSha": self.base}]
        self.write_gh_config()

        # When
        code, _, _ = self.run_cli("commit", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual(self.git("tag", "-l", "v1.6.0"), "")


class PublishTest(RepoFixture):

    def commit_release(self, *args):
        self.add_draft()
        code, _, err = self.run_cli("commit", "1.6.0", *args)
        self.assertEqual(code, 0, err)

    def assert_nothing_published(self):
        self.assertEqual(self.remote_git("tag", "-l", "v1.6.0"), "")
        self.assertFalse([c for c in self.gh_calls() if c["argv"][:2] == ["release", "create"]])

    def test_publish_pushes_the_release_commits_and_creates_the_github_release(self):
        # Given
        self.commit_release()
        head = self.git("rev-parse", "HEAD")

        # When
        code, out, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)
        self.assertEqual(self.remote_git("rev-parse", "main"), head)
        self.assertEqual(self.remote_git("rev-parse", "refs/tags/v1.6.0^{commit}"), self.remote_git("rev-parse",
                                                                                                    "main^"))
        self.assertEqual(self.git("rev-parse", "HEAD"), head)
        self.assertIn("releases/tag/v1.6.0", out)

    def test_publish_creates_a_latest_github_release_from_the_notes(self):
        # Given
        self.commit_release()

        # When
        self.run_cli("publish", "1.6.0")

        # Then
        creates = [c for c in self.gh_calls() if c["argv"][:2] == ["release", "create"]]
        self.assertEqual(len(creates), 1)
        argv = creates[0]["argv"]
        self.assertEqual(argv[2], "v1.6.0")
        self.assertIn("--latest", argv)
        self.assertIn("--verify-tag", argv)
        self.assertEqual(argv[argv.index("--title") + 1], "v1.6.0")
        notes = creates[0]["notes"]
        self.assertTrue(notes.startswith("This release keeps the **tuning after a reset**."))
        self.assertIn("## User-facing changes", notes)
        self.assertNotIn("### ", notes)
        self.assertNotIn("v1.5.0", notes)
        self.assertIn(f"[#303]({ISSUES}303)", notes)

    def test_publish_pushes_the_next_version_given_to_commit(self):
        # Given
        self.commit_release("--next", "2.0.0")

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)
        self.assertEqual(self.remote_git("log", "-1", "--format=%s", "main"), "Start v2.0.0-SNAPSHOT")

    def test_publish_requires_the_release_commits(self):
        # Given
        self.add_draft()

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("commit 1.6.0", err)
        self.assertEqual(self.remote_git("rev-parse", "main"), self.base)
        self.assert_nothing_published()

    def test_publish_refuses_commits_on_top_of_the_release(self):
        # Given
        self.commit_release()
        self.write("Tuner.scala", "// after the release\n")
        self.commit("Unrelated")

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("Start v", err)
        self.assertEqual(self.remote_git("rev-parse", "main"), self.base)
        self.assert_nothing_published()

    def test_publish_refuses_when_origin_moved_since_the_release_commits(self):
        # Given
        self.commit_release()
        other = os.path.join(self.tmp, "other")
        self.git("clone", "-q", self.remote, other, cwd=self.tmp)
        with open(os.path.join(other, "new.txt"), "w") as f:
            f.write("x\n")
        self.git("add", "new.txt", cwd=other)
        self.git("commit", "-q", "-m", "New", cwd=other)
        self.git("push", "-q", "origin", "main", cwd=other)
        moved = self.remote_git("rev-parse", "main")

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("origin/main", err)
        self.assertEqual(self.remote_git("rev-parse", "main"), moved)
        self.assert_nothing_published()

    def test_publish_refuses_uncommitted_changes(self):
        # Given
        self.commit_release()
        self.write("docs/release-notes.md", self.read("docs/release-notes.md") + "\nEdited after the commit.\n")

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("docs/release-notes.md", err)
        self.assertEqual(self.remote_git("rev-parse", "main"), self.base)
        self.assert_nothing_published()

    def test_publish_explains_how_to_release_uncommitted_changes(self):
        # Given
        self.commit_release()
        self.write("docs/release-notes.md", self.read("docs/release-notes.md") + "\nEdited after the commit.\n")
        _, _, err = self.run_cli("publish", "1.6.0")
        undo = re.search(r"with `([^`]*)`", err)
        self.assertIsNotNone(undo, err)

        # When
        subprocess.run(undo.group(1), shell=True, check=True, cwd=self.repo, env=self.env, capture_output=True)

        # Then
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual(self.git("tag", "-l", "v1.6.0"), "")
        self.assertIn("Edited after the commit.", self.read("docs/release-notes.md"))
        code, _, err = self.run_cli("commit", "1.6.0")
        self.assertEqual(code, 0, err)

    def test_publish_asks_to_set_aside_uncommitted_changes_to_other_files(self):
        # Given
        self.commit_release()
        self.write("Tuner.scala", "// not for this release\n")

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("Tuner.scala", err)
        self.assertIn("stash", err.lower())
        self.assertNotIn("git reset", err)
        self.assert_nothing_published()

    def test_publish_refuses_a_tag_left_behind_by_rewritten_commits(self):
        # Given
        self.commit_release()
        self.git("reset", "-q", "--hard", self.base)
        self.add_draft(DRAFT_V160.replace("keeps", "retains"))
        self.set_version("1.6.0")
        self.commit("Release v1.6.0")
        self.set_version("1.7.0-SNAPSHOT")
        self.commit("Start v1.7.0-SNAPSHOT")

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("v1.6.0", err)
        self.assertEqual(self.remote_git("rev-parse", "main"), self.base)
        self.assert_nothing_published()

    def test_publish_changes_nothing_when_the_push_fails(self):
        # Given
        self.commit_release()
        hook = os.path.join(self.remote, "hooks", "pre-receive")
        with open(hook, "w") as f:
            f.write("#!/bin/sh\necho 'protected branch' >&2\nexit 1\n")
        os.chmod(hook, 0o755)

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("nothing was published", err)
        self.assertIn("publish 1.6.0", err)
        self.assertEqual(self.remote_git("rev-parse", "main"), self.base)
        self.assert_nothing_published()

    def test_publish_explains_how_to_finish_when_the_github_release_fails(self):
        # Given
        self.commit_release()
        self.gh_config["release_fails"] = True
        self.write_gh_config()

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertEqual(self.remote_git("rev-parse", "refs/tags/v1.6.0^{commit}"), self.remote_git("rev-parse",
                                                                                                    "main^"))
        self.assertIn("github-release 1.6.0", err)

    def test_publish_points_to_github_release_when_the_tag_is_already_pushed(self):
        # Given
        self.commit_release()
        self.gh_config["release_fails"] = True
        self.write_gh_config()
        self.run_cli("publish", "1.6.0")

        # When
        code, _, err = self.run_cli("publish", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("github-release 1.6.0", err)


class GithubReleaseTest(RepoFixture):

    def test_github_release_publishes_the_notes_of_a_pushed_tag(self):
        # Given
        self.add_draft()
        self.run_cli("commit", "1.6.0")
        self.gh_config["release_fails"] = True
        self.write_gh_config()
        self.run_cli("publish", "1.6.0")
        self.gh_config["release_fails"] = False
        self.write_gh_config()

        # When
        code, out, err = self.run_cli("github-release", "1.6.0")

        # Then
        self.assertEqual(code, 0, err)
        creates = [c for c in self.gh_calls() if c["argv"][:2] == ["release", "create"]]
        self.assertEqual(len(creates), 2)
        self.assertIn("## User-facing changes", creates[-1]["notes"])
        self.assertIn("releases/tag/v1.6.0", out)

    def test_github_release_requires_the_tag_on_origin(self):
        # When
        code, _, err = self.run_cli("github-release", "1.6.0")

        # Then
        self.assertEqual(code, 1)
        self.assertIn("v1.6.0", err)


if __name__ == "__main__":
    unittest.main()
