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
# start-metals-mcp.sh — launch Metals MCP headlessly for this workspace.
#
# Starts, as background processes:
#   1. `sbt` (interactive shell), which also hosts SBT's BSP server so Metals
#      can reuse a single warm SBT process.
#   2. `metals-standalone-client --verbose . -- -Dmetals.mcpClient=claude`,
#      which drives Metals as a headless LSP client and makes Metals write
#      `.mcp.json` at the repo root for Claude Code to pick up.
#
# Output of both processes is captured into `logs/*.log` at the repo root.
# Older logs are discarded on each run.
#
# Once `.mcp.json` has appeared, the script optionally warms up the build by
# sending `compile` to the running SBT shell. Because SBT and Metals share the
# same BSP/SBT state, this also warms what Metals' MCP tools see.
#
# The script blocks until interrupted (Ctrl-C or SIGTERM) or until one of the
# background processes exits. On shutdown it stops both background processes.
#
# See docs/development-setup/metals-mcp-claude-code.md for the full setup.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
cd "$repo_root"

logs_dir="$repo_root/logs"
sbt_log="$logs_dir/sbt.log"
metals_log="$logs_dir/metals-standalone-client.log"
sbt_fifo="$logs_dir/.sbt-stdin.fifo"

log() { echo "[start-metals-mcp] $*"; }
err() { echo "[start-metals-mcp] $*" >&2; }

# Sanity-check required commands.
for cmd in sbt metals-standalone-client; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    err "Required command not found on PATH: $cmd"
    exit 1
  fi
done

mkdir -p "$logs_dir"

# Discard older logs.
rm -f "$sbt_log" "$metals_log"

# Recreate the FIFO used as SBT's stdin so SBT stays alive and we can push
# commands (e.g. `compile`, `exit`) into it.
rm -f "$sbt_fifo"
mkfifo "$sbt_fifo"

sbt_pid=""
metals_pid=""
sbt_fd_open=0

cleanup() {
  trap - EXIT INT TERM
  echo
  log "Stopping background processes..."

  if [[ -n "$metals_pid" ]] && kill -0 "$metals_pid" 2>/dev/null; then
    kill "$metals_pid" 2>/dev/null || true
  fi

  if [[ -n "$sbt_pid" ]] && kill -0 "$sbt_pid" 2>/dev/null; then
    # Ask SBT to exit cleanly via its stdin FIFO, then close the FD so SBT
    # sees EOF if it ignored the `exit` command.
    if [[ "$sbt_fd_open" -eq 1 ]]; then
      echo "exit" >&9 2>/dev/null || true
      exec 9>&- 2>/dev/null || true
      sbt_fd_open=0
    fi
    # Give SBT a few seconds to stop on its own, then force it.
    for _ in 1 2 3 4 5; do
      kill -0 "$sbt_pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$sbt_pid" 2>/dev/null; then
      kill "$sbt_pid" 2>/dev/null || true
    fi
  fi

  # Make sure the FD is released even if SBT was never started.
  if [[ "$sbt_fd_open" -eq 1 ]]; then
    exec 9>&- 2>/dev/null || true
    sbt_fd_open=0
  fi

  rm -f "$sbt_fifo"
  wait 2>/dev/null || true
  log "Done."
}
trap cleanup EXIT INT TERM

log "Repo root: $repo_root"
log "Logs:      $logs_dir"

# Start SBT first so that it becomes the FIFO's reader. Opening a FIFO for
# read-only blocks until a writer appears, and opening for write-only blocks
# until a reader appears — so we must have the reader (SBT) in progress before
# we open the writer end below. Backgrounded SBT's stdin redirection will
# block on open() until the parent opens FD 9 for writing.
log "Starting SBT (log: $sbt_log)..."
sbt <"$sbt_fifo" >"$sbt_log" 2>&1 &
sbt_pid=$!
log "SBT PID: $sbt_pid"

# Now open the write end. This rendezvous unblocks both SBT's read-open and
# our own write-open. FD 9 stays open for the lifetime of the script so SBT
# never sees EOF on its stdin; we also use it to push commands (e.g. the
# warm-up `compile` and the shutdown `exit`).
exec 9>"$sbt_fifo"
sbt_fd_open=1

log "Starting metals-standalone-client (log: $metals_log)..."
metals-standalone-client --verbose . -- -Dmetals.mcpClient=claude \
  >"$metals_log" 2>&1 &
metals_pid=$!
log "metals-standalone-client PID: $metals_pid"

# Wait for Metals to write .mcp.json, then warm up the build.
mcp_file="$repo_root/.mcp.json"
log "Waiting for $mcp_file to be written by Metals..."
for _ in $(seq 1 180); do
  if [[ -f "$mcp_file" ]]; then
    log "$mcp_file is ready."
    break
  fi
  if ! kill -0 "$metals_pid" 2>/dev/null; then
    err "metals-standalone-client exited; see $metals_log"
    exit 1
  fi
  sleep 1
done

if [[ -f "$mcp_file" ]]; then
  # Warm up: ask the running SBT shell to do a full compile. SBT and Metals
  # share the same BSP state, so this also warms what Metals' MCP tools see.
  # (A proper MCP `compile-full` call would require speaking JSON-RPC over SSE,
  # which is impractical from a shell script.)
  log "Warming up: sending 'compile' to SBT..."
  echo "compile" >&9 || true
else
  err "Warning: $mcp_file did not appear within timeout; skipping warm-up."
fi

echo
log "Metals MCP is running."
log "  Tail SBT log:    tail -f $sbt_log"
log "  Tail Metals log: tail -f $metals_log"
log "Launch Claude Code from $repo_root in another terminal and run /mcp to verify."
log "Press Ctrl-C to stop."

# Block until either child exits (or until we are signalled).
while kill -0 "$sbt_pid" 2>/dev/null && kill -0 "$metals_pid" 2>/dev/null; do
  sleep 2
done

log "A background process exited. Shutting down."
