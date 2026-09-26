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

"""Helpers shared by the release-skill graders.

A grader runs in a skillgrade workspace holding one finished trial (see `fixture/import-trial.sh`): `repo/` (the
agent's working copy), `remote.git/` (the stand-in for GitHub), `sandbox/gh-calls.jsonl` (the stand-in `gh` log),
`state/` (the fixture's expected state), `agent-output.txt` (the agent's final message) and `judge.json` (the LLM
judge's verdict, when judged). It prints skillgrade's JSON result as the only output on stdout.
"""

import json
import os
import re
import subprocess

WS = os.getcwd()
REPO = os.path.join(WS, "repo")
REMOTE = os.path.join(WS, "remote.git")
STATE = os.path.join(WS, "state")
INPUT = json.loads(os.environ.get("SKILLGRADE_INPUT") or "{}")
EXPECTED = INPUT.get("expected") or {}
ISSUE_URL = "https://github.com/calinburloiu/microtonalist/issues/"
NOTES_PATH = "docs/release-notes.md"

HEADING = re.compile(r"^## (v\d+\.\d+\.\d+)(?: \((\d{4}-\d{2}-\d{2})\))?[ \t]*$", re.M)
LINKED_REF = re.compile(r"\[#(\d+)\]\(([^)\s]*)\)")


class Checks:
    """Accumulates named checks and prints them as a skillgrade result whose score is the fraction passed."""

    def __init__(self):
        self.items = []

    def add(self, name, passed, message=""):
        self.items.append({"name": name, "passed": bool(passed), "message": message})
        return bool(passed)

    def emit(self):
        passed = sum(1 for c in self.items if c["passed"])
        total = len(self.items)
        print(json.dumps({"score": round(passed / total, 4) if total else 0.0,
                          "details": f"{passed}/{total} checks passed", "checks": self.items}))


def git(*args, git_dir=None):
    """Runs git on the trial's repository (or on `git_dir`); returns stdout, or None when git fails."""
    command = ["git", "--no-pager"] + (["--git-dir", git_dir] if git_dir else ["-C", REPO]) + list(args)
    result = subprocess.run(command, capture_output=True, text=True)
    return result.stdout.rstrip("\n") if result.returncode == 0 else None


def remote_git(*args):
    return git(*args, git_dir=REMOTE)


def read(path, default=None):
    full = path if os.path.isabs(path) else os.path.join(WS, path)
    if not os.path.exists(full):
        return default
    with open(full) as f:
        return f.read()


def gh_releases():
    """The `gh release create` calls the stand-in recorded."""
    log = read("sandbox/gh-calls.jsonl", "")
    events = [json.loads(line) for line in log.splitlines() if line.strip()]
    return [e for e in events if e.get("event") == "release-create"]


def agent_output():
    return read("agent-output.txt", "")


def base_head():
    return (read("state/base-head", "") or "").strip()


def sections(text):
    """Splits release notes into `(version, date, section_text)` tuples, in file order."""
    matches = list(HEADING.finditer(text or ""))
    result = []
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        result.append((m.group(1), m.group(2), text[m.start():end]))
    return result


def bad_links(text):
    """Links `[#N](url)` whose URL is not the issue URL of N."""
    return [f"[#{n}]({url})" for n, url in LINKED_REF.findall(text or "") if url != f"{ISSUE_URL}{n}"]


def subsection_headings(section_text):
    return re.findall(r"^### .*$", section_text or "", re.M)


def promote_headings(text):
    """Raises every Markdown heading below the top level by one level (`### X` becomes `## X`)."""
    return re.sub(r"^#(#+ )", r"\1", text, flags=re.M)


def section_body(section_text):
    """The section without its `## vX.Y.Z (date)` heading line, trimmed."""
    return section_text.split("\n", 1)[1].strip() if "\n" in section_text else ""


def normalize(text):
    return "\n".join(line.rstrip() for line in (text or "").strip().splitlines())


def version_in(build_sbt):
    m = re.search(r'^ThisBuild / version := "([^"]*)"$', build_sbt or "", re.M)
    return m.group(1) if m else None


def changed_lines(before, after):
    """Lines that differ between two texts of the same length, as (before, after) pairs; None if lengths differ."""
    a, b = (before or "").splitlines(), (after or "").splitlines()
    if len(a) != len(b):
        return None
    return [(x, y) for x, y in zip(a, b) if x != y]
