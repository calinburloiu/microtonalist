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
# the approved notes, then releases v1.6.0 with the skill's script (skillgrade copies the skill into the workspace).
set -euo pipefail
set -a; source task.env; set +a
WS=$PWD FIXTURE=$PWD/fixture REAL_REPO=$PWD/real-repo bash fixture/setup.sh

export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_AUTHOR_NAME="Calin-Andrei Burloiu" GIT_AUTHOR_EMAIL="calin.burloiu@gmail.com"
export GIT_COMMITTER_NAME="Calin-Andrei Burloiu" GIT_COMMITTER_EMAIL="calin.burloiu@gmail.com"
cd repo
python3 ../.claude/skills/release/scripts/microtonalist_release.py publish 1.6.0 > ../agent-output.txt
