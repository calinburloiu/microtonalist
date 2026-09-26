#!/bin/bash
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

# Builds one trial of the fixture in $WS:
#
# - `remote.git`, a bare repository standing in for GitHub;
# - `repo`, a clone of it whose history mirrors microtonalist's around v1.5.0 (release commits, tags and the three PRs
#   merged since v1.5.0), with the skill under test ($SKILL_DIR, when set) committed as in the real repository;
# - `sandbox/bin/gh`, the stand-in GitHub CLI, answering from $FIXTURE/gh-data and logging to `sandbox/gh-calls.jsonl`
#   (`git config release.gh` points the release script at it);
# - `state/`, the expected state for the graders.
#
# Environment: APPLY_APPROVED_NOTES=1 adds the approved v1.6.0 release notes to docs/release-notes.md, uncommitted, as
# the draft step of the skill leaves them; GH_STUB_CI sets the CI conclusion the stand-in reports (default success).
set -euo pipefail
: "${WS:?}" "${FIXTURE:?}"

# Deterministic commits, whatever the user's git configuration.
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_AUTHOR_NAME="Calin-Andrei Burloiu" GIT_AUTHOR_EMAIL="calin.burloiu@gmail.com"
export GIT_COMMITTER_NAME="Calin-Andrei Burloiu" GIT_COMMITTER_EMAIL="calin.burloiu@gmail.com"

STATE="$WS/state"
rm -rf "$WS/repo" "$WS/remote.git" "$WS/sandbox" "$STATE"
mkdir -p "$STATE" "$WS/sandbox/bin"
cp "$FIXTURE/bin/gh" "$WS/sandbox/bin/gh"
chmod +x "$WS/sandbox/bin/gh"
printf '{"data": "%s", "log": "%s", "ci": "%s"}\n' \
  "$FIXTURE/gh-data" "$WS/sandbox/gh-calls.jsonl" "${GH_STUB_CI:-success}" > "$WS/sandbox/config.json"

commit() {
  GIT_AUTHOR_DATE="$1" GIT_COMMITTER_DATE="$1" git commit -q -m "$2"
}

set_version() {
  perl -pi -e 's{^ThisBuild / version := ".*"$}{ThisBuild / version := "'"$1"'"}' build.sbt
}

touch_file() {
  mkdir -p "$(dirname "$1")"
  printf '%s\n' "$2" >> "$1"
  git add "$1"
}

git init -q --bare -b main "$WS/remote.git"
git init -q -b main "$WS/repo"
cd "$WS/repo"

# v1.4.0: the notes up to v1.4.0 and the skill under test.
cp "$FIXTURE/repo/build.sbt" "$FIXTURE/repo/README.md" .
mkdir -p docs
awk '/^## v1\.5\.0 /{skip=1} /^## v1\.4\.0 /{skip=0} !skip' "$FIXTURE/repo/docs/release-notes.md" > docs/release-notes.md
if [ -n "${SKILL_DIR:-}" ]; then
  mkdir -p .claude/skills
  cp -R "$SKILL_DIR" .claude/skills/release
fi
set_version 1.4.0
git add -A
commit "2026-08-18T23:30:00+03:00" "Release v1.4.0"
git tag v1.4.0

set_version 1.5.0-SNAPSHOT
git add build.sbt
commit "2026-08-18T23:35:00+03:00" "Start v1.5.0-SNAPSHOT"

touch_file tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala "// hot plugging"
commit "2026-09-15T20:10:00+03:00" "[#131][#288] Allow hot plugging MIDI devices (#304)"

set_version 1.5.0
cp "$FIXTURE/repo/docs/release-notes.md" docs/release-notes.md
git add build.sbt docs/release-notes.md
commit "2026-09-22T22:18:12+03:00" "Release v1.5.0"
git tag v1.5.0

set_version 1.6.0-SNAPSHOT
git add build.sbt
commit "2026-09-22T22:20:00+03:00" "Start v1.6.0-SNAPSHOT"

touch_file AGENTS.md "Symbol tool targets: glob searches need no fileInFocus."
touch_file docs/agents/metals-mcp-inspect-workaround.md "inspect still needs a fileInFocus."
commit "2026-09-24T11:02:00+03:00" "[#324] Drop the Metals MCP fileInFocus workaround for glob searches (#325)"

touch_file tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Tuner.scala "// retain the current tuning"
commit "2026-09-25T18:40:00+03:00" \
  "[#305/#322][#303][#297] Retain the current tuning in the Tuner trait and restore it on reset (#323)"

touch_file tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Tuner.scala "// canTune and clamping"
commit "2026-09-26T13:15:00+03:00" \
  "[#305/#322][#303] Tell whether a Tuner can tune a tuning exactly, and clamp the ones it cannot (#327)"

git config release.gh "$WS/sandbox/bin/gh"
git remote add origin "$WS/remote.git"
git push -q origin main --tags
git branch -q -u origin/main
git fetch -q origin

git rev-parse HEAD > "$STATE/base-head"
cp docs/release-notes.md "$STATE/base-release-notes.md"
cp build.sbt "$STATE/base-build.sbt"

if [ "${APPLY_APPROVED_NOTES:-0}" = "1" ]; then
  approved="$STATE/approved-section.md"
  sed "s/@DATE@/$(date +%F)/" "$FIXTURE/approved-v1.6.0.md" > "$approved"
  awk -v approved="$approved" '
    /^## v1\.5\.0 / && !done { while ((getline line < approved) > 0) print line; print ""; done = 1 }
    { print }
  ' "$STATE/base-release-notes.md" > docs/release-notes.md
  cp docs/release-notes.md "$STATE/approved-release-notes.md"
fi
