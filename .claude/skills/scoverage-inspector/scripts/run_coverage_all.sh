#!/usr/bin/env bash
#
# run_coverage_all.sh — run `sbt coverageAll` with the scoverage target-suffix
# isolation flag to produce an aggregate report covering all modules.
#
# Use this only when the user wants coverage including caller tests from other
# modules. For focused per-class questions, use run_coverage_modules.sh instead.
#
# Writes output to the next free logs/skills/scoverage-inspector/sbt-run-N.log
# and prints the chosen log path before invoking sbt so the caller can surface
# it even if sbt crashes mid-run.
#
# Always use `sbt` (fresh JVM), never `sbtn`. The scoverage run must land in
# target-scoverage/ to avoid colliding with the BSP server's target-bsp/.

set -uo pipefail

repo_root="$(git rev-parse --show-toplevel)"
log_dir="$repo_root/logs/skills/scoverage-inspector"
mkdir -p "$log_dir"

n=1
while [[ -e "$log_dir/sbt-run-$n.log" ]]; do
  ((n++))
done
log_file="$log_dir/sbt-run-$n.log"

echo "log: $log_file"
sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageAll 2>&1 | tee "$log_file"
exit "${PIPESTATUS[0]}"
