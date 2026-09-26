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

# skillgrade `command` agent that runs no model. The trials are run beforehand by Claude Code subagents (see
# bin/prepare-trials); this loads the next finished trial of the current task (named in task.env) from $TRIALS_DIR
# into the workspace, for the graders. The instruction arrives on stdin and is ignored: the subagent already had it.
set -euo pipefail
cat > /dev/null
: "${TRIALS_DIR:?TRIALS_DIR must point to the trials directory of an eval run}"
source task.env

for trial in "$TRIALS_DIR/$TASK"/*; do
  if mkdir "$trial/.imported" 2>/dev/null; then
    cp -R "$trial/repo" "$trial/remote.git" "$trial/sandbox" "$trial/state" .
    for file in agent-output.txt commands.txt judge.json; do
      if [ -f "$trial/$file" ]; then cp "$trial/$file" .; fi
    done
    echo "imported $trial"
    cat agent-output.txt 2>/dev/null || true
    exit 0
  fi
done
echo "import-trial: no trial of $TASK left to import in $TRIALS_DIR" >&2
exit 1
