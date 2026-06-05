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
# sbt-test-filter.sh — Claude Code PreToolUse hook for the Bash tool.
#
# When the agent runs an sbt/sbtn test command, this hook transparently routes
# its output through bin/agents-test-filter by rewriting the command (via the
# PreToolUse "updatedInput" field) before it executes, so the agent only sees
# the meaningful lines (failures, aborts, and the counts that matter) and the
# pipeline's exit code reflects pass/fail.
#
# See docs/development/claude-code-setup.md ("Hooks") for the rationale and the
# bin/agents-test-filter behavior.
#
# Exit codes (PreToolUse contract):
#   - exit 0 with JSON on stdout  → apply the decision (here: allow + rewrite)
#   - exit 0 with no stdout       → no opinion; the command runs unchanged
# This hook never blocks: on any non-match, missing dependency, or unexpected
# input it falls through to a bare `exit 0`, so it can never break a Bash call.
#

# Read the hook payload from stdin.
input="$(cat)"

# Need jq to parse/emit JSON; if it is missing, do nothing (run unchanged).
command -v jq >/dev/null 2>&1 || exit 0

cmd="$(printf '%s' "$input" | jq -r '.tool_input.command // empty')"
[[ -n "$cmd" ]] || exit 0

# Decide whether this is a *simple* sbt/sbtn test invocation we should rewrite.
# Conservative on purpose: we only touch a single, plain command so that
# appending a pipe can never alter shell-operator precedence.
is_plain_sbt_test() {
  # Reject multi-line commands.
  case "$cmd" in *$'\n'*) return 1 ;; esac
  # Must start with the sbt or sbtn binary.
  printf '%s' "$cmd" | grep -Eq '^[[:space:]]*sbtn?[[:space:]]' || return 1
  # Must run a test task (covers `test`, `*/test`, and `testOnly`).
  printf '%s' "$cmd" | grep -q 'test' || return 1
  # Skip if it is already routed through the filter.
  printf '%s' "$cmd" | grep -q 'agents-test-filter' && return 1
  # Skip anything with shell metacharacters (pipes, lists, redirections,
  # backgrounding) — including an existing `2>&1` (the `&`) — so we never break
  # precedence or double up redirections.
  printf '%s' "$cmd" | grep -qE '[|;&<>`]' && return 1
  return 0
}

if is_plain_sbt_test; then
  new_cmd="$cmd 2>&1 | bin/agents-test-filter"
  jq -n --arg c "$new_cmd" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "allow",
      updatedInput: { command: $c },
      additionalContext: "sbt test output was routed through bin/agents-test-filter (see docs/development/claude-code-setup.md). Green-run noise is dropped; failures, aborts, and non-zero counts pass through, and the pipeline exit code reflects pass/fail."
    }
  }'
fi

exit 0
