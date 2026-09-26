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

# Reference solution of the publish-release task, for `skillgrade --validate`: builds the trial in the workspace with
# the approved notes, then releases v1.6.0 by hand the way the skill must.
set -euo pipefail
set -a; source task.env; set +a
WS=$PWD FIXTURE=$PWD/fixture bash fixture/setup.sh

export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_AUTHOR_NAME="Calin-Andrei Burloiu" GIT_AUTHOR_EMAIL="calin.burloiu@gmail.com"
export GIT_COMMITTER_NAME="Calin-Andrei Burloiu" GIT_COMMITTER_EMAIL="calin.burloiu@gmail.com"
cd repo
set_version() {
  perl -pi -e 's{^ThisBuild / version := ".*"$}{ThisBuild / version := "'"$1"'"}' build.sbt
}

set_version 1.6.0
git add build.sbt docs/release-notes.md
git commit -q -m "Release v1.6.0"
git tag v1.6.0
set_version 1.7.0-SNAPSHOT
git commit -q -am "Start v1.7.0-SNAPSHOT"
git push -q --atomic origin main v1.6.0

git show v1.6.0:docs/release-notes.md \
  | awk '/^## v/ { n++ } n == 1 && !/^## v/ { sub(/^###/, "##"); print }' > ../release-body.md
../sandbox/bin/gh release create v1.6.0 --title v1.6.0 --latest --verify-tag --notes-file ../release-body.md
echo "Released v1.6.0; main is now at 1.7.0-SNAPSHOT." > ../agent-output.txt
