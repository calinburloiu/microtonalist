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
# license-header-read-skip.sh — Claude Code PreToolUse hook for the Read tool.
#
# Every source file in this repo opens with a ~15-line Apache 2.0 license
# header that carries no information an agent needs but costs tokens on every
# read. This hook detects that header and rewrites the Read input (via the
# PreToolUse "updatedInput" field) to set an "offset" that starts the read just
# past the header, so the agent sees the code without the boilerplate. Real
# line numbers are preserved: Read with an offset omits the skipped lines, it
# does not renumber the rest.
#
# See docs/development/license-headers.md and the "Hooks" section of
# docs/development/claude-code-setup.md for the rationale and the escape hatch.
#
# Exit codes (PreToolUse contract):
#   - exit 0 with JSON on stdout  → apply the decision (here: allow + offset)
#   - exit 0 with no stdout       → no opinion; the read runs unchanged
# This hook never blocks: on any non-match, missing dependency, or unexpected
# input it falls through to a bare `exit 0`, so it can never break a Read call.
#

# Read the hook payload from stdin.
input="$(cat)"

# Need jq to parse/emit JSON; if it is missing, do nothing (read unchanged).
command -v jq >/dev/null 2>&1 || exit 0

tool_input="$(printf '%s' "$input" | jq -c '.tool_input // empty')"
[[ -n "$tool_input" ]] || exit 0

file_path="$(printf '%s' "$tool_input" | jq -r '.file_path // empty')"
offset="$(printf '%s' "$tool_input" | jq -r '.offset // empty')"
limit="$(printf '%s' "$tool_input" | jq -r '.limit // empty')"

# Pass through untouched when the caller asked for a specific range. This also
# preserves the escape hatch: Read with `offset: 1` shows the full header.
[[ -z "$offset" && -z "$limit" ]] || exit 0
# Need a readable regular file to inspect.
[[ -n "$file_path" && -f "$file_path" && -r "$file_path" ]] || exit 0

# Inspect only the top of the file (the header lives there).
head_lines="$(head -n 25 "$file_path" 2>/dev/null)" || exit 0
[[ -n "$head_lines" ]] || exit 0

first_line="$(printf '%s\n' "$head_lines" | sed -n '1p')"

# Never drop a shebang: a single offset cannot keep line 1 while skipping the
# header on lines 2+, so shebang files are read with their header visible.
case "$first_line" in '#!'*) exit 0 ;; esac

# Only act when the Apache marker is actually present in the head.
printf '%s\n' "$head_lines" | grep -q 'Licensed under the Apache License' || exit 0

# Determine where the code starts, by the comment style inferred from line 1.
content_start=""
case "$first_line" in
  '/*'*)
    # Block comment (.scala, .java, .js, .css, .sbt): end of the `*/` line, +1.
    n="$(printf '%s\n' "$head_lines" | grep -n -m1 '\*/' | cut -d: -f1)"
    [[ -n "$n" ]] && content_start=$((n + 1))
    ;;
  '<!--'*)
    # XML/HTML comment (.xml, .fxml, .html): end of the `-->` line, +1.
    n="$(printf '%s\n' "$head_lines" | grep -n -m1 '\-\->' | cut -d: -f1)"
    [[ -n "$n" ]] && content_start=$((n + 1))
    ;;
  '#'*)
    # Line comment (.py, .properties, and shebang-less .sh/.bash): end of the
    # contiguous top run of `#` lines, +1 (i.e. the first non-`#` line).
    n="$(printf '%s\n' "$head_lines" | awk '!/^#/{print NR; exit}')"
    [[ -n "$n" ]] && content_start="$n"
    ;;
esac

# Bail out if we could not locate the end of the header.
[[ -n "$content_start" && "$content_start" -gt 1 ]] || exit 0

# Skip the single separator blank line between the header and the code.
target_line="$(printf '%s\n' "$head_lines" | sed -n "${content_start}p")"
if [[ "$target_line" =~ ^[[:space:]]*$ ]]; then
  content_start=$((content_start + 1))
fi

# Guard: never set an offset past EOF.
total="$(grep -c '' "$file_path" 2>/dev/null)" || exit 0
[[ -n "$total" && "$content_start" -le "$total" ]] || exit 0

# Emit the decision, preserving all original input fields and adding the offset.
jq -nc --argjson in "$tool_input" --argjson n "$content_start" \
  '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"allow",updatedInput:($in + {offset:$n})}}'

exit 0
