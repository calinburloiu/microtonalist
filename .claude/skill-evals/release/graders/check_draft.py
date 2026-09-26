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

"""Grades the draft step: the release notes are drafted in docs/release-notes.md, in the house format, and nothing is
committed, tagged, pushed or published before the user reviews them."""

import re

from common import (EXPECTED, NOTES_PATH, Checks, agent_output, bad_links, bare_refs, base_head, git, gh_releases,
                    linked_refs, read, remote_git, sections, subsection_headings)

version = EXPECTED.get("version", "1.6.0")
next_version = EXPECTED.get("next", "1.7.0-SNAPSHOT")
tag = f"v{version}"
base = base_head()
checks = Checks()

head = git("rev-parse", "HEAD")
checks.add("no-local-commits", head == base, f"HEAD is {head}, base is {base}")
checks.add("no-tag", not git("tag", "-l", tag) and not remote_git("tag", "-l", tag), f"{tag} must not exist yet")
checks.add("nothing-pushed", remote_git("rev-parse", "main") == base, "the remote main must be untouched")
checks.add("no-github-release", not gh_releases(), f"{len(gh_releases())} `gh release create` call(s)")
checks.add("build-sbt-unchanged", read("repo/build.sbt") == read("state/base-build.sbt"), "build.sbt must not change")

notes = read(f"repo/{NOTES_PATH}", "")
base_notes = read("state/base-release-notes.md", "")
found = sections(notes)
top = found[0] if found else (None, None, "")
checks.add("new-section-at-top", top[0] == tag,
           f"first section heading must be `## {tag}`, dated or not (publish dates it), found {top[0]}")

previous_start = notes.find("\n## v1.5.0 ")
base_previous_start = base_notes.find("\n## v1.5.0 ")
new_match = re.search(rf"\n## {re.escape(tag)}(?=[ \n])", notes)
new_start = new_match.start() if new_match else -1
checks.add("older-notes-untouched",
           previous_start >= 0 and notes[previous_start:] == base_notes[base_previous_start:]
           and new_start >= 0 and notes[:new_start] == base_notes[:base_previous_start],
           "the preamble and every older section must be byte-identical")

section = top[2] if top[0] == tag else ""
headings = [h.strip() for h in subsection_headings(section)]
required = ["### User-facing changes", "### Developer-facing changes", "### Known issues"]
positions = [headings.index(h) if h in headings else -1 for h in required]
checks.add("standard-subsections", all(p >= 0 for p in positions) and positions == sorted(positions),
           f"subsections found: {headings}")

# Bare `#N` references are fine in a draft: publishing turns them into links. Malformed links are not.
bare, bad = bare_refs(section), bad_links(section)
checks.add("issue-refs-well-formed", section and not bad and (bare or linked_refs(section)),
           f"bad links: {bad[:3]}, references: {len(bare)} bare, {len(linked_refs(section))} linked")

output = agent_output()
checks.add("proposes-next-snapshot", re.search(re.escape(next_version), output) is not None,
           f"the final message must propose {next_version}")

checks.emit()
