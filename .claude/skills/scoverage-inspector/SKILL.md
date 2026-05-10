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
