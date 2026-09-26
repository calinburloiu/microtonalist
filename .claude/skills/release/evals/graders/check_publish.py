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

"""Grades the publish step: the release and snapshot commits and the tag, as the house conventions make them; their push
to the remote; and the GitHub Release. The local history is graded apart from the push, so that a push the permission
system denied does not hide whether the commits were right."""

import re

from common import (EXPECTED, NOTES_PATH, Checks, base_head, changed_lines, git, gh_releases, normalize,
                    promote_headings, read, remote_git, section_body, sections, version_in)

version = EXPECTED["version"]
next_version = EXPECTED["next"]
tag = f"v{version}"
release_subject, start_subject = f"Release {tag}", f"Start v{next_version}"
base = base_head()
checks = Checks()

# Local history ------------------------------------------------------------------------------------------------------

log = git("log", "--format=%H %s", f"{base}..HEAD") or ""
commits = [line.split(" ", 1) for line in log.splitlines()]
checks.add("two-commits", [s for _, s in commits] == [start_subject, release_subject],
           f"commits since base: {[s for _, s in commits]}")
release_sha = next((sha for sha, s in commits if s == release_subject), None)
start_sha = next((sha for sha, s in commits if s == start_subject), None)
checks.add("release-commit-on-base", release_sha and git("rev-parse", f"{release_sha}^") == base,
           "the release commit's parent must be the base commit")
messages = [git("log", "-1", "--format=%B", sha) for sha in (release_sha, start_sha) if sha]
checks.add("commit-messages-exact", len(messages) == 2 and [m.strip() for m in messages] == [release_subject,
                                                                                            start_subject],
           f"messages must be exactly the subjects, without trailers: {messages}")


def files_of(sha):
    out = git("show", "--name-only", "--format=", sha) if sha else None
    return sorted(filter(None, (out or "").splitlines()))


base_build = read("state/base-build.sbt")
release_build = git("show", f"{release_sha}:build.sbt") if release_sha else None
start_build = git("show", f"{start_sha}:build.sbt") if start_sha else None
checks.add("release-commit-files", files_of(release_sha) == ["build.sbt", NOTES_PATH],
           f"release commit files: {files_of(release_sha)}")
release_diff = changed_lines(base_build, release_build)
checks.add("release-version-bump", version_in(release_build) == version and release_diff is not None
           and len(release_diff) == 1, f"version {version_in(release_build)}, changed lines {release_diff}")
start_diff = changed_lines(release_build, start_build)
checks.add("snapshot-commit", files_of(start_sha) == ["build.sbt"] and version_in(start_build) == next_version
           and start_diff is not None and len(start_diff) == 1,
           f"files {files_of(start_sha)}, version {version_in(start_build)}, changed lines {start_diff}")


def without_heading_dates(text):
    return re.sub(r"^(## v\d+\.\d+\.\d+) \(\d{4}-\d{2}-\d{2}\)", r"\1", text or "", flags=re.M)


approved = read("state/approved-release-notes.md", "")
committed = git("show", f"{release_sha}:{NOTES_PATH}") if release_sha else None
checks.add("notes-committed-as-approved",
           committed is not None and normalize(without_heading_dates(committed)) == normalize(
               without_heading_dates(approved)), "the release commit must contain the approved notes")
tag_sha = git("rev-parse", f"refs/tags/{tag}^{{commit}}")
checks.add("tag-on-release-commit", release_sha and tag_sha == release_sha,
           f"{tag} -> {tag_sha}, release commit {release_sha}")
checks.add("tag-lightweight", git("cat-file", "-t", f"refs/tags/{tag}") == "commit",
           "release tags are lightweight, like the existing ones")
status = git("status", "--porcelain")
checks.add("working-tree-clean", status == "", f"status: {status!r}")

# Push ---------------------------------------------------------------------------------------------------------------

checks.add("main-pushed", release_sha and remote_git("rev-parse", "main") == git("rev-parse", "HEAD"),
           "the remote main must be the local main")
checks.add("tag-pushed", release_sha and remote_git("rev-parse", f"refs/tags/{tag}^{{commit}}") == release_sha,
           f"the remote {tag} must tag the release commit")

# GitHub Release -----------------------------------------------------------------------------------------------------

releases = gh_releases()
checks.add("one-github-release", len(releases) == 1 and releases[0]["tag"] == tag,
           f"releases created: {[r['tag'] for r in releases]}")
release = releases[0] if releases else {}
checks.add("release-title", release.get("title") == tag, f"title {release.get('title')!r}")
checks.add("release-marked-latest", release.get("latest") in (True, "true") and not release.get("draft")
           and not release.get("prerelease"),
           f"latest={release.get('latest')} draft={release.get('draft')} prerelease={release.get('prerelease')}")
checks.add("release-tag-pushed-first", release.get("tag_existed_on_remote") is True,
           "the tag must be on the remote before the release is created")
approved_sections = sections(approved)
expected_body = promote_headings(section_body(approved_sections[0][2])) if approved_sections else ""
checks.add("release-notes-body", normalize(release.get("notes")) == normalize(expected_body),
           "the GitHub Release notes must be the approved section, headings raised one level")

checks.emit()
