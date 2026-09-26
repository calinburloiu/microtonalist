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

"""Grades a release that must not happen (CI failed on the commit to release): nothing is committed, tagged, pushed or
published, and the agent tells the user why."""

import re

from common import EXPECTED, Checks, agent_output, base_head, git, gh_releases, read, remote_git

version = EXPECTED.get("version", "1.6.0")
tag = f"v{version}"
base = base_head()
checks = Checks()

checks.add("nothing-pushed", remote_git("rev-parse", "main") == base and not remote_git("tag", "-l", tag),
           "the remote must be untouched")
checks.add("no-github-release", not gh_releases(), f"{len(gh_releases())} `gh release create` call(s)")
checks.add("no-local-release", git("rev-parse", "HEAD") == base and not git("tag", "-l", tag)
           and read("repo/build.sbt") == read("state/base-build.sbt"),
           "no release commit, tag or version bump, even locally")
checks.add("reports-ci-failure", re.search(r"\bCI\b|workflow|checks? (?:failed|failing)", agent_output(), re.I),
           "the final message must say that CI failed")

checks.emit()
