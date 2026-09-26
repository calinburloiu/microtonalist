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

# Reference solution of the refuse-red-ci task, for `skillgrade --validate`: builds the trial in the workspace, finds
# that CI failed on the commit to release and stops without changing anything.
set -euo pipefail
set -a; source task.env; set +a
WS=$PWD FIXTURE=$PWD/fixture bash fixture/setup.sh
echo "CI (Scala CI) failed on the commit to release, so I did not release v1.6.0. Fix main first." > agent-output.txt
