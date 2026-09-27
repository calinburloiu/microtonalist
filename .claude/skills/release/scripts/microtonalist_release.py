#!/usr/bin/env python3
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

"""Releases a new microtonalist version.

Commands, run anywhere in the repository:

  plan VERSION [--next NEXT]     Check that VERSION can be released now and print the release plan. Changes nothing.
  context                        Print what the release notes are drafted from: the commits since the latest release,
                                 the PRs and issues they reference, and the state of the previous known issues.
  commit VERSION [--next NEXT]   Make the release locally, after the checks of `plan`, from the notes drafted at the top
                                 of docs/release-notes.md: commit "Release vVERSION" (build.sbt and the notes), tag
                                 vVERSION, and commit "Start vNEXT" (build.sbt). Pushes nothing, and prints how to undo.
  publish VERSION                Publish the release made by `commit`: push main and the tag to origin, and create the
                                 GitHub Release, marked Latest, from the committed notes.
  github-release VERSION         Create the GitHub Release of an already pushed tag, from its notes (to finish a
                                 `publish` whose last step failed).

VERSION is MAJOR.MINOR.PATCH (a leading `v` is accepted). NEXT defaults to the next minor version, or to the
-SNAPSHOT version build.sbt is at, if higher; its `-SNAPSHOT` suffix is added when missing.

The GitHub CLI is `gh`, or the command in `git config release.gh` (a stand-in, in tests).
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile

BUILD_FILE = "build.sbt"
NOTES_FILE = "docs/release-notes.md"
BRANCH = "main"
REMOTE = "origin"
REPO = "calinburloiu/microtonalist"
ISSUE_URL = f"https://github.com/{REPO}/issues/"
SCRIPT_FILE = os.path.abspath(__file__)

VERSION_LINE = re.compile(r'^ThisBuild / version := "([^"]*)"$', re.M)
RELEASE_VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
SNAPSHOT_VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(-SNAPSHOT)?$")
RELEASE_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
SECTION_HEADING = re.compile(r"^## v(\d+\.\d+\.\d+)(?: \(([^)\n]*)\))?[ \t]*$", re.M)
FENCE = re.compile(r"^\s*(```|~~~)")
CODE_SPAN = re.compile(r"(`[^`\n]*`)")
LINK_OR_REF = re.compile(r"\[[^\]\n]*\]\([^)\n]*\)|<?https?://[^\s)>]*>?|(?<![\w&#])#(\d+)\b")
REFERENCE = re.compile(r"(?<![\w&])#(\d+)\b")


class ReleaseError(Exception):
    """A release that cannot or must not proceed, with a message for the user."""


# Versions ------------------------------------------------------------------------------------------------------------


def version_tuple(version):
    return tuple(int(part) for part in version.split("-")[0].split("."))


def release_version(text):
    """Validates a release version such as `1.6.0` or `v1.6.0`, returning it without the `v`."""
    m = RELEASE_VERSION.match(text or "")
    if not m:
        raise ReleaseError(f"invalid release version {text!r}: expected MAJOR.MINOR.PATCH, e.g. 1.6.0")
    return ".".join(m.groups())


def next_version(version, requested, snapshot=None):
    """The development version after releasing `version`, as a -SNAPSHOT: `requested`, or else the next minor or the
    `snapshot` being developed, whichever is higher (a patch keeps the development version it was released from)."""
    if requested is None:
        major, minor, _ = version_tuple(version)
        result = f"{major}.{minor + 1}.0-SNAPSHOT"
        return snapshot if snapshot and version_tuple(snapshot) > version_tuple(result) else result
    m = SNAPSHOT_VERSION.match(requested)
    if not m:
        raise ReleaseError(f"invalid next version {requested!r}: expected MAJOR.MINOR.PATCH[-SNAPSHOT]")
    result = ".".join(m.groups()[:3]) + "-SNAPSHOT"
    if version_tuple(result) <= version_tuple(version):
        raise ReleaseError(f"the next version {result} must come after the release {version}")
    return result


def expected_release(version, snapshot):
    """Whether releasing `version` from the development version `snapshot` is expected: `snapshot` is the version
    being released, or `version` is a patch of an earlier release, found while developing the next one."""
    if version_tuple(version) == version_tuple(snapshot):
        return True
    return version_tuple(version)[2] > 0 and version_tuple(version) < version_tuple(snapshot)


def successors(version):
    """The versions that can directly follow the release `version`: its next patch, minor and major."""
    major, minor, patch = version_tuple(version)
    return [f"{major}.{minor}.{patch + 1}", f"{major}.{minor + 1}.0", f"{major + 1}.0.0"]


def latest_release_tag(tags):
    """The highest `vMAJOR.MINOR.PATCH` tag, or None."""
    releases = [t for t in tags if RELEASE_TAG.match(t)]
    return max(releases, key=lambda t: version_tuple(t[1:])) if releases else None


# build.sbt -----------------------------------------------------------------------------------------------------------


def build_version(text):
    matches = VERSION_LINE.findall(text)
    if len(matches) != 1:
        raise ReleaseError(f"{BUILD_FILE} must have exactly one `ThisBuild / version := \"...\"` line; "
                           f"found {len(matches)}")
    return matches[0]


def with_build_version(text, version):
    build_version(text)
    return VERSION_LINE.sub(f'ThisBuild / version := "{version}"', text)


# Release notes -------------------------------------------------------------------------------------------------------


def linkify(text):
    """Turns bare issue references such as `#123` into Markdown links, outside code and existing links."""
    out, in_fence = [], False
    for line in text.split("\n"):
        if FENCE.match(line):
            in_fence = not in_fence
        elif not in_fence:
            parts = CODE_SPAN.split(line)
            for i in range(0, len(parts), 2):
                parts[i] = LINK_OR_REF.sub(
                    lambda m: f"[#{m.group(1)}]({ISSUE_URL}{m.group(1)})" if m.group(1) else m.group(0), parts[i])
            line = "".join(parts)
        out.append(line)
    return "\n".join(out)


def top_section(notes):
    """The newest release section of the notes, as (version, date or None, section text). The date is whatever the
    heading has in parentheses after the version, possibly a placeholder such as `YYYY-MM-DD`."""
    matches = list(SECTION_HEADING.finditer(notes))
    if not matches:
        raise ReleaseError(f"{NOTES_FILE} has no `## vMAJOR.MINOR.PATCH` section")
    first = matches[0]
    end = matches[1].start() if len(matches) > 1 else len(notes)
    return first.group(1), first.group(2), notes[first.start():end]


def github_release_body(section):
    """The GitHub Release notes of a section: without its heading line, every other heading raised one level."""
    lines = section.split("\n")[1:]
    out, in_fence = [], False
    for line in lines:
        if FENCE.match(line):
            in_fence = not in_fence
        elif not in_fence and re.match(r"^#{2,6} ", line):
            line = line[1:]
        out.append(line)
    return "\n".join(out).strip("\n") + "\n"


def with_release_section(notes, version, date):
    """Dates and links the top section of the notes, which must be the one of `version`."""
    found, _, section = top_section(notes)
    heading, _, rest = section.partition("\n")
    if found != version:
        raise ReleaseError(f"the top section of {NOTES_FILE} must be `## v{version}`, with the release notes of "
                           f"v{version}; found `{heading}`")
    if not rest.strip():
        raise ReleaseError(f"the `## v{version}` section of {NOTES_FILE} is empty")
    start = SECTION_HEADING.search(notes).start()
    return notes[:start] + f"## v{version} ({date})\n" + linkify(rest) + notes[start + len(section):]


# Git and GitHub ------------------------------------------------------------------------------------------------------


def run(command, check=True, input_text=None):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", input=input_text)
    if check and result.returncode != 0:
        raise ReleaseError(f"`{' '.join(command)}` failed:\n{(result.stderr or result.stdout).strip()}")
    return result


def git(*args, check=True):
    return run(["git"] + list(args), check=check).stdout.strip()


def gh_command():
    configured = run(["git", "config", "--get", "release.gh"], check=False).stdout.strip()
    return configured or "gh"


def gh(*args, check=True):
    return run([gh_command()] + list(args), check=check)


def script():
    """This script's path, relative to the repository root, for the commands printed to the user."""
    return os.path.relpath(SCRIPT_FILE)


def read_file(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write_file(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def uncommitted_changes():
    """The paths of tracked files with uncommitted changes."""
    status = run(["git", "status", "--porcelain"]).stdout.splitlines()
    return [line[3:] for line in status if line and not line.startswith("??")]


# Checks --------------------------------------------------------------------------------------------------------------


class Plan:
    """What a release of `version` would do, and the problems that prevent it."""

    def __init__(self, version, requested_next):
        self.version = release_version(version)
        self.requested_next = requested_next
        self.next = next_version(self.version, requested_next)
        self.tag = f"v{self.version}"
        self.errors, self.warnings = [], []
        self.head = self.current_version = self.previous_tag = None
        self.commit_count = 0
        self.ci = []

    def check(self):
        self.check_git()
        self.check_build()
        self.check_tags()
        self.check_ci()
        return self

    def check_git(self):
        branch = git("rev-parse", "--abbrev-ref", "HEAD")
        if branch != BRANCH:
            self.errors.append(f"releases are made from {BRANCH}, but the current branch is {branch}")
        others = [path for path in uncommitted_changes() if path != NOTES_FILE]
        if others:
            self.errors.append(f"uncommitted changes other than {NOTES_FILE}: {', '.join(others)}")
        fetched = run(["git", "fetch", "--quiet", "--tags", REMOTE], check=False)
        if fetched.returncode != 0:
            self.errors.append(f"cannot fetch {REMOTE}: {fetched.stderr.strip()}")
        self.head = git("rev-parse", "HEAD")
        upstream = git("rev-parse", f"{REMOTE}/{BRANCH}", check=False)
        if upstream != self.head:
            self.errors.append(f"{BRANCH} ({self.head[:7]}) is not in sync with {REMOTE}/{BRANCH} "
                               f"({(upstream or 'missing')[:7]}): pull or push first")

    def check_build(self):
        try:
            self.current_version = build_version(read_file(BUILD_FILE))
        except ReleaseError as e:
            self.errors.append(str(e))
            return
        if not self.current_version.endswith("-SNAPSHOT"):
            self.errors.append(f"{BUILD_FILE} is at {self.current_version}, not at a -SNAPSHOT version")
        else:
            if not expected_release(self.version, self.current_version):
                self.warnings.append(f"{BUILD_FILE} is at {self.current_version}, but the release is {self.version}")
            self.next = next_version(self.version, self.requested_next, self.current_version)
            if version_tuple(self.next) < version_tuple(self.current_version):
                self.warnings.append(f"the next version {self.next} is below {self.current_version}, the version "
                                     f"{BUILD_FILE} is at now")

    def check_tags(self):
        tags = git("tag", "--list", "v*").split()
        if self.tag in tags or git("ls-remote", "--tags", REMOTE, f"refs/tags/{self.tag}", check=False):
            self.errors.append(f"the tag {self.tag} already exists")
        self.previous_tag = latest_release_tag([t for t in tags if t != self.tag])
        if self.previous_tag:
            if version_tuple(self.version) <= version_tuple(self.previous_tag[1:]):
                self.errors.append(f"{self.version} does not come after the latest release, {self.previous_tag}")
            elif self.version not in successors(self.previous_tag[1:]):
                expected = successors(self.previous_tag[1:])
                self.warnings.append(f"{self.version} skips versions after the latest release, {self.previous_tag}: "
                                     f"expected {expected[0]}, {expected[1]} or {expected[2]}")
            self.commit_count = int(git("rev-list", "--count", f"{self.previous_tag}..HEAD"))

    def check_ci(self):
        result = gh("run", "list", "-R", REPO, "--commit", self.head, "--json",
                    "workflowName,status,conclusion,headSha", check=False)
        if result.returncode != 0:
            self.errors.append(f"cannot read the CI status of {self.head[:7]}: {result.stderr.strip()}")
            return
        self.ci = json.loads(result.stdout or "[]")
        if not self.ci:
            self.errors.append(f"no CI run found for {self.head[:7]}: wait for CI to start on {BRANCH}")
        for ci_run in self.ci:
            state = ci_run["conclusion"] if ci_run["status"] == "completed" else ci_run["status"]
            if state != "success":
                self.errors.append(f"CI workflow {ci_run['workflowName']} is {state} on {self.head[:7]}")

    def report(self):
        previous = (f"{self.previous_tag} ({self.commit_count} commits since)" if self.previous_tag
                    else "none")
        ci = ", ".join(f"{r['workflowName']}: {r['conclusion'] or r['status']}" for r in self.ci) or "unknown"
        return "\n".join([
            f"Release plan for {self.tag}",
            f"  previous release: {previous}",
            f"  release:  {BUILD_FILE} {self.current_version} -> {self.version}, commit \"Release {self.tag}\" "
            f"(with {NOTES_FILE}), tag {self.tag}",
            f"  next:     {BUILD_FILE} -> {self.next}, commit \"Start v{self.next}\"",
            f"  then:     push {BRANCH} and {self.tag} to {REMOTE}; GitHub Release {self.tag}, marked Latest",
            f"  from:     {self.head[:7] if self.head else '?'} on {BRANCH}; CI {ci}",
        ])


def checked_plan(version, requested_next):
    plan = Plan(version, requested_next).check()
    print(plan.report())
    for warning in plan.warnings:
        print(f"warning: {warning}", file=sys.stderr)
    if plan.errors:
        raise ReleaseError("the release cannot proceed:\n" + "\n".join(f"  - {e}" for e in plan.errors))
    return plan


# Commands ------------------------------------------------------------------------------------------------------------


def command_plan(args):
    checked_plan(args.version, args.next)
    print("All checks passed.")


def fetch_item(number):
    result = gh("api", f"repos/{REPO}/issues/{number}", check=False)
    return json.loads(result.stdout) if result.returncode == 0 else None


def references(text):
    """The issue/PR numbers referenced in `text`, in order and once each, ignoring code and other repositories."""
    found, in_fence = [], False
    for line in (text or "").split("\n"):
        if FENCE.match(line):
            in_fence = not in_fence
        elif not in_fence:
            found += [int(n) for n in REFERENCE.findall(CODE_SPAN.sub("", line))]
    return list(dict.fromkeys(found))


def describe(item):
    kind = "PR" if item.get("pull_request") else "Issue"
    state = "merged" if (item.get("pull_request") or {}).get("merged_at") else item["state"]
    if state == "closed" and item.get("state_reason"):
        state += " as " + item["state_reason"].replace("_", " ")
    labels = ", ".join(label["name"] for label in item.get("labels", [])) or "-"
    milestone = (item.get("milestone") or {}).get("title") or "-"
    return f"{kind} #{item['number']}: {item['title']} ({state}; labels: {labels}; milestone: {milestone})"


def nested(body):
    """A description with its headings moved two levels down, below the `##` heading of its item."""
    out, in_fence = [], False
    for line in body.split("\n"):
        if FENCE.match(line):
            in_fence = not in_fence
        elif not in_fence and re.match(r"^#{1,4} ", line):
            line = "##" + line
        out.append(line)
    return "\n".join(out)


def iter_sections(notes):
    matches = list(SECTION_HEADING.finditer(notes))
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(notes)
        yield m.group(1), m.group(2), notes[m.start():end]


def command_context(_args):
    previous = latest_release_tag(git("tag", "--list", "v*").split())
    if not previous:
        raise ReleaseError("no release tag vMAJOR.MINOR.PATCH found")
    log = git("log", "--format=%h %s", f"{previous}..HEAD")
    print(f"# Commits since {previous}\n\n{log or '(none)'}")

    cache = {}

    def item(number):
        if number not in cache:
            cache[number] = fetch_item(number)
        return cache[number]

    def line(number):
        return f"- {describe(item(number))}" if item(number) else f"- #{number}: not found on GitHub"

    direct = references(log)
    for number in list(direct):
        if item(number) and item(number).get("pull_request"):
            direct += [int(n) for n in re.findall(r"(?im)^\s*(?:resolves|closes|fixes)\s+#(\d+)",
                                                  item(number).get("body") or "") if int(n) not in direct]
    direct.sort(key=lambda n: not (item(n) or {}).get("pull_request"))

    print("\n# Pull requests and issues of these commits")
    mentioned = []
    for number in direct:
        if not item(number):
            print(f"\n## #{number}: not found on GitHub")
            continue
        body = (item(number).get("body") or "").strip()
        print(f"\n## {describe(item(number))}\n\n{nested(body) if body else '(no description)'}")
        mentioned += references(body)

    notes = read_file(NOTES_FILE) if os.path.exists(NOTES_FILE) else ""
    section = next((s for v, _, s in iter_sections(notes) if f"v{v}" == previous), "")
    known = references(section.split("### Known issues", 1)[1]) if "### Known issues" in section else []
    print(f"\n# Known issues of {previous}, and their state now\n")
    print("\n".join(line(n) for n in known) or "(none)")

    others = [n for n in dict.fromkeys(mentioned) if n not in direct and n not in known]
    print("\n# Other issues and PRs mentioned above\n")
    print("\n".join(line(n) for n in others) or "(none)")


def create_github_release(tag):
    notes = git("show", f"{tag}:{NOTES_FILE}")
    version, _, section = top_section(notes)
    if f"v{version}" != tag:
        raise ReleaseError(f"the top section of {NOTES_FILE} at {tag} is v{version}, not {tag}")
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
        f.write(github_release_body(section))
    try:
        result = gh("release", "create", tag, "-R", REPO, "--title", tag, "--notes-file", f.name, "--latest",
                    "--verify-tag")
    finally:
        os.unlink(f.name)
    print(f"GitHub Release: {result.stdout.strip()}")


def undo_command(tag, base):
    return f"git tag -d {tag} && git reset --quiet --mixed {base} && git checkout -- {BUILD_FILE}"


def command_commit(args):
    plan = checked_plan(args.version, args.next)
    draft = read_file(NOTES_FILE)
    notes = with_release_section(draft, plan.version, datetime.date.today().isoformat())
    build = read_file(BUILD_FILE)

    try:
        write_file(NOTES_FILE, notes)
        write_file(BUILD_FILE, with_build_version(build, plan.version))
        git("add", BUILD_FILE, NOTES_FILE)
        git("commit", "--quiet", "-m", f"Release {plan.tag}")
        git("tag", plan.tag)
        write_file(BUILD_FILE, with_build_version(build, plan.next))
        git("add", BUILD_FILE)
        git("commit", "--quiet", "-m", f"Start v{plan.next}")
    except (ReleaseError, OSError) as e:
        # The checks guarantee that the tag did not exist and that HEAD was plan.head, so both are safe to restore.
        run(["git", "tag", "-d", plan.tag], check=False)
        run(["git", "reset", "--quiet", "--mixed", plan.head], check=False)
        write_file(BUILD_FILE, build)
        write_file(NOTES_FILE, draft)
        raise ReleaseError(f"{e}\nNothing was committed: the release commits and tag are undone, and {BUILD_FILE} "
                           f"and {NOTES_FILE} are as they were.")

    print("\nCommitted locally; nothing is pushed yet:")
    for sha in git("rev-list", f"{plan.head}..HEAD").split():
        files = ", ".join(git("show", "--name-only", "--format=", sha).split())
        print(f"  {git('log', '-1', '--format=%h %s%d', sha)}: {files}")
    print(f"\nTo publish, once the user approves: python3 {script()} publish {plan.version}")
    print(f"To undo, keeping the notes: {undo_command(plan.tag, plan.head)}")


def release_commits_error(tag, version):
    """Why HEAD is not the pair of release commits of `tag` that `commit` makes, or None if it is."""
    if git("rev-parse", "--abbrev-ref", "HEAD") != BRANCH:
        return f"releases are published from {BRANCH}"
    tagged = git("rev-parse", "--verify", "--quiet", f"refs/tags/{tag}^{{commit}}", check=False)
    if not tagged:
        return f"there is no tag {tag}: run `commit {version}` first"
    if git("log", "-1", "--format=%s", tagged) != f"Release {tag}":
        return f"the tag {tag} is not on a \"Release {tag}\" commit"
    if (git("rev-parse", "HEAD^", check=False) != tagged
            or not re.fullmatch(r"Start v\S+-SNAPSHOT", git("log", "-1", "--format=%s"))):
        return f"HEAD must be the \"Start v...-SNAPSHOT\" commit right after the tag {tag}"
    changed = uncommitted_changes()
    if changed == [NOTES_FILE]:
        undo = undo_command(tag, git("rev-parse", f"{tagged}^"))
        return (f"uncommitted changes to {NOTES_FILE}, which would not be published. To release them, undo the "
                f"release commits, keeping the notes, with `{undo}`, and commit again")
    if changed:
        return (f"uncommitted changes, which are not part of the release: {', '.join(changed)}. Stash or revert "
                f"them (they belong in a pull request of their own), and publish again")
    return None


def command_publish(args):
    version = release_version(args.version)
    tag = f"v{version}"
    if git("ls-remote", "--tags", REMOTE, f"refs/tags/{tag}", check=False):
        raise ReleaseError(f"the tag {tag} is already on {REMOTE}. If its GitHub Release is missing, run: "
                           f"python3 {script()} github-release {version}")
    error = release_commits_error(tag, version)
    if error:
        raise ReleaseError(error)
    fetched = run(["git", "fetch", "--quiet", REMOTE, BRANCH], check=False)
    if fetched.returncode != 0:
        raise ReleaseError(f"cannot fetch {REMOTE}: {fetched.stderr.strip()}")
    base = git("rev-parse", f"{tag}^")
    if git("rev-parse", f"{REMOTE}/{BRANCH}") != base:
        raise ReleaseError(f"{REMOTE}/{BRANCH} moved since the release commits were made on {base[:7]}, so nothing "
                           f"was published. Undo them, keeping the notes, with `{undo_command(tag, base)}`, pull, "
                           f"and release again")

    pushed = run(["git", "push", "--quiet", "--atomic", REMOTE, BRANCH, tag], check=False)
    if pushed.returncode != 0:
        raise ReleaseError(f"pushing to {REMOTE} failed, so nothing was published:\n{pushed.stderr.strip()}\n"
                           f"The release commits and tag are intact: fix the cause and run "
                           f"`python3 {script()} publish {version}` again, or undo them, keeping the notes, with "
                           f"`{undo_command(tag, base)}`")
    print(f"Pushed {BRANCH} and {tag} to {REMOTE}.")
    try:
        create_github_release(tag)
    except ReleaseError as e:
        raise ReleaseError(f"{e}\nThe commits and the tag are pushed. To create the GitHub Release, run: "
                           f"python3 {script()} github-release {version}")


def command_github_release(args):
    tag = f"v{release_version(args.version)}"
    if not git("ls-remote", "--tags", REMOTE, f"refs/tags/{tag}", check=False):
        raise ReleaseError(f"the tag {tag} is not on {REMOTE}: run `publish` instead")
    create_github_release(tag)


def main(argv=None):
    parser = argparse.ArgumentParser(prog="microtonalist_release.py", description=__doc__.split("\n\n")[0],
                                     epilog=__doc__.split("\n\n", 1)[1], formatter_class=argparse.RawTextHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name, handler in (("plan", command_plan), ("commit", command_commit)):
        sub = commands.add_parser(name)
        sub.add_argument("version")
        sub.add_argument("--next", help="the next development version (default: the next minor, or the -SNAPSHOT "
                                        "version build.sbt is at, if higher)")
        sub.set_defaults(handler=handler)
    commands.add_parser("context").set_defaults(handler=command_context)
    for name, handler in (("publish", command_publish), ("github-release", command_github_release)):
        sub = commands.add_parser(name)
        sub.add_argument("version")
        sub.set_defaults(handler=handler)
    args = parser.parse_args(argv)
    try:
        root = run(["git", "rev-parse", "--show-toplevel"], check=False)
        if root.returncode != 0:
            raise ReleaseError("run this from the microtonalist repository")
        os.chdir(root.stdout.strip())
        args.handler(args)
    except ReleaseError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
