#!/usr/bin/env bash
#
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
#

#
# stop-sbt-metals.sh — stop a running `start-sbt-metals.sh`.
#
# Reads the PID from `logs/start-sbt-metals.pid`, sends SIGTERM, waits up to
# 10 seconds for the process to exit, then escalates to SIGKILL if needed.
# Removes the PID file on success. Idempotent: a missing PID file or a stale
# PID (no live process) is a no-op success.
#
# Pair with `start-sbt-metals.sh --background`.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

pid_file="$repo_root/logs/start-sbt-metals.pid"

log() { echo "[stop-sbt-metals] $*"; }

if [[ ! -f "$pid_file" ]]; then
  log "No PID file at $pid_file; nothing to stop."
  exit 0
fi

pid="$(cat "$pid_file" 2>/dev/null || true)"

if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
  log "PID file present but process ${pid:-<empty>} is not alive; cleaning up stale file."
  rm -f "$pid_file"
  exit 0
fi

log "Sending SIGTERM to PID $pid..."
kill "$pid" 2>/dev/null || true

for _ in $(seq 1 10); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done

if kill -0 "$pid" 2>/dev/null; then
  log "PID $pid still alive after 10s; sending SIGKILL."
  kill -9 "$pid" 2>/dev/null || true
fi

rm -f "$pid_file"
log "Done."
