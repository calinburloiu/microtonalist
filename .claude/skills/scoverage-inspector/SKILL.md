---
name: scoverage-inspector
description: |
  Inspect microtonalist scoverage XML reports cheaply: get per-class statement/branch
  percentages and uncovered source lines without loading 3000-line XML files into
  context. USE THIS SKILL whenever the user asks about test coverage of specific
  classes, asks "what's missing in tests for X", asks whether a change keeps a module
  above its `coverageMinimumStmtTotal` floor, or any time you're about to verify
  coverage for one or more Scala classes after editing them. Also use it before
  running `sbt coverageModules` to decide whether the existing report is still fresh,
  so you don't pay for a full coverage build when the previous report is still valid.
---

# scoverage-inspector

## Coverage policy

This policy is for the **main agent** to apply: judging thresholds and adding tests is your job. It
is distinct from the subagent's mechanical XML inspection (whose findings you return verbatim — see
the delegation section below).

Code coverage is measured via [scoverage](https://github.com/scoverage/sbt-scoverage). Each SBT
project has per-module statement and branch thresholds configured in `build.sbt` via the
`coverageSettings` helper.

The target for this project is **80% statement and branch coverage**. This applies for every module,
as well as for the project-wide aggregated coverage. Modules that have not yet reached 80% are
configured with their current coverage minus _a 3% buffer_ and an open issue tracking the work needed
to reach 80%.

**Per-module coverage must never decrease below the configured threshold.** When changing code in a
module:

- The threshold in `build.sbt` is a floor, not a target. It can stay flat or be raised toward 80%,
  but never lowered.
- If your change reduces coverage below the configured threshold, add tests so it stays at or above
  the threshold.
- If your change raises coverage, you may raise the threshold in `build.sbt` accordingly, but keep
  the 3% buffer. Once both statement and branch reach 80%, switch the module to
  `coverageSettings(stmt = 80, branch = 80)` and close the tracking issue.
- **New files must always meet the 80% statement and branch coverage target on their own**,
  regardless of the module's current threshold. The per-module floor exists to track legacy code
  paying down toward 80%; it is not a license for newly authored code to ship under-tested.

Coverage runs occasionally fail with TASTy / companion-class errors due to a known sbt-scoverage +
Scala 3 concurrency issue documented in `docs/development/scoverage-issue.md`. If the subagent
reports such a failure, **stop and wait for user input** rather than retrying or modifying code.

For the manual `sbt coverageAll` / `coverageModules` workflow and CI's `coverageCheck`, see
`docs/development/coverage.md`.

## Dependency

This skill requires the custom subagent defined at
`.claude/agents/scoverage-inspector.md`. The agent file must exist before the
skill can run.

## For the main agent: delegate to the custom subagent

When this skill triggers, **do not execute the workflow yourself**. Spawn the
`scoverage-inspector` custom subagent, which has the full workflow baked into
its system prompt. This keeps all the mechanical tool calls out of your
expensive main-agent turns.

```
Agent(
  subagent_type = "scoverage-inspector",
  prompt        = """
User's question: <USER_QUESTION_VERBATIM>

Working directory (repo root): <ABSOLUTE_CWD>

Run all commands with cwd = <ABSOLUTE_CWD>. Always call the helper scripts
with relative paths from the repo root (e.g.
`python3 .claude/skills/scoverage-inspector/scripts/coverage_freshness.py <module>`).
"""
)
```

The custom subagent loads **only its own system prompt** — the project
`CLAUDE.md` is not injected. This is intentional: `CLAUDE.md` tells agents to
prefer `sbtn` against the running BSP server, which conflicts with this skill's
need to run `sbt` in isolation under `target-scoverage/`.

Return the subagent's response verbatim. Do not add your own commentary.

---

## Workflow

The full workflow — freshness check, coverage build via wrapper scripts,
report parsing, and anti-patterns — lives in the system prompt of
`.claude/agents/scoverage-inspector.md`. That file is the single source of
truth.
