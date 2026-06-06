# Plan: Replace the scoverage-inspector subagent with an MCP server

> **Status:** Proposal / RFC. This document is the deliverable of the planning PR on branch
> `refactoring/scoverage-inspector-mcp`. Implementation follows in subsequent PR(s) once the plan is
> approved.

## 1. Background

Coverage inspection today is a three-layer stack:

1. **`scoverage-inspector` skill** (`.claude/skills/scoverage-inspector/SKILL.md`) — carries the coverage
   *policy* for the main agent and *delegates* the mechanical work to a custom subagent.
2. **`scoverage-inspector` subagent** (`.claude/agents/scoverage-inspector.md`, model `haiku`,
   tools `Bash` + `mcp__metals__inspect`) — orchestrates the mechanical workflow: resolve class → module,
   freshness-check, run coverage, parse the XML, distill a small answer.
3. **Helper scripts** (`.claude/skills/scoverage-inspector/scripts/`) — four Python stdlib scripts that
   stream `scoverage.xml` with `iterparse` and emit a few hundred bytes each, plus two Bash wrappers that
   run `sbt coverageModules` / `sbt coverageAll` with the `-Dmicrotonalist.build.targetSuffix=-scoverage`
   isolation flag and tee output to `logs/skills/scoverage-inspector/sbt-run.log`.

The subagent exists to (a) keep verbose mechanical tool calls and the large `sbt` log out of the
expensive main-agent context, and (b) run that mechanical reasoning cheaply on Haiku.

### Why this is now redundant

Once the helper-script logic is moved *in-process into an MCP server*, every decision the subagent makes
becomes deterministic code rather than LLM reasoning:

- **Class → module resolution** is derivable from the FQN alone: the package path *is* the directory path,
  so a filesystem glob (`**/src/main/scala/<pkg-as-path>/<Class>.scala`, module = the segment before
  `src`) resolves it. This removes the `mcp__metals__inspect` dependency entirely.
- **Freshness-check → rebuild** becomes an automatic precondition of every query tool, not a step an agent
  sequences by hand.
- **XML parsing** already returns small structured results; in-process it returns typed data directly
  instead of stdout the agent must re-read.
- **Verbose `sbt` output** is captured to a log file by the tool; only a compact status + log path is
  returned, so it never enters any agent's context.

With no judgment node left and no verbose output to quarantine, the subagent's two reasons to exist both
disappear. The mechanical work costs ~nothing in tokens (it's Python returning hundreds of bytes), so
there is no Haiku saving to capture, and dropping the nested agent loop *reduces* wall-clock latency.

## 2. Goals and non-goals

### Goals

- Move all coverage-inspection mechanics into an in-process MCP server backed by a pure, unit-tested
  Python module.
- **Unit-test every line of Python in the MCP** — both `scoverage_core.py` and `server.py`. No Python
  ships without tests (see §8).
- Make freshness-check-and-maybe-rebuild an internal precondition of every query tool.
- Remove the `scoverage-inspector` subagent and the `mcp__metals__inspect` dependency.
- Slim the skill down to the coverage *policy* plus "call the tool".
- **Remove TASTy / companion-class failure detection and the one-shot retry everywhere** — the underlying
  sbt-scoverage + Scala 3 concurrency issue is resolved, so this logic is dead weight (see §6).

### Non-goals

- No change to the coverage *policy* itself (thresholds, the never-lower-the-floor rule, the 80%-for-new-
  files rule). That stays the main agent's job, expressed in the skill.
- No change to how `sbt` produces reports or where they live (`coverage-reports/<module>/...`).
- No broad rewrite of the surrounding dev docs that mention the TASTy issue (see §6 for the exact scope).

## 3. Target architecture

```
.claude/mcp/scoverage_inspector/
  scoverage_core.py     # pure logic: resolver, freshness, parsers, sbt runner — no MCP, no I/O coupling
  server.py             # thin stdio MCP server wrapping scoverage_core
  tests/
    test_scoverage_core.py    # unit tests for the pure logic
    test_server.py            # unit tests for the MCP wrapper (tool dispatch, arg parsing, error mapping)
    resources/scoverage-sample.xml   # small fixture report
.mcp.json               # registers the server (stdio transport)
```

- **`scoverage_core.py`** — pure functions returning dataclasses, independently runnable and testable:
  - `resolve(fqn) -> ClassLocation(module, source_path)` via filesystem glob (replaces metals).
  - `freshness(module, aggregate=False) -> Freshness(FRESH|STALE|MISSING)` (ports `coverage_freshness.py`).
  - `run_coverage(modules, aggregate=False) -> RunResult(status, log_path)` — wraps `sbt`, captures
    output to `logs/skills/scoverage-inspector/sbt-run.log` (file only, **not** returned inline), bakes in
    the `-Dmicrotonalist.build.targetSuffix=-scoverage` flag. **No retry, no TASTy classification.**
    `status` is `OK` or `ERROR` only.
  - `class_summary(...)`, `class_uncovered_lines(...)`, `module_summary(...)` — port the three reader
    scripts, keeping `iterparse` streaming, returning dataclasses.
- **`server.py`** — a `stdio` MCP server (FastMCP, or hand-rolled JSON-RPC if we want zero pip deps — see
  §8 open question) exposing the tools in §4.
- **`.mcp.json`** — registers the server so Claude Code launches it as a child process over stdio.

## 4. Tool API

### `coverage_report` (primary)

```
coverage_report(
  classes: list[str],            # fully-qualified class names
  include_uncovered: bool = False,
  aggregate: bool = False,
  allow_rebuild: bool = True,
) -> {
  classes: [ { fqn, module, file, stmt_pct, branch_pct,
               methods?: [...], uncovered_ranges?: ["file:Lstart-Lend", ...] } ],
  rebuilt: [<module>, ...],       # which modules were (re)built this call
  log: "logs/skills/scoverage-inspector/sbt-run.log",
  status: "ok" | "error",
}
```

Behavior:

1. Resolve each FQN → `(module, file)` by glob; dedupe the module set.
2. Freshness-check the module set (or the aggregate report when `aggregate=True`).
3. For stale/missing modules: if `allow_rebuild`, run **one batched** `sbt coverageModules <m...>`
   (or `sbt coverageAll` for aggregate); otherwise return `status=error` noting which modules are stale.
4. Parse the report(s); return one compact record per class, including uncovered ranges only when
   `include_uncovered=True`.

### `module_coverage` (secondary)

```
module_coverage(
  modules: list[str],
  include_classes: bool = True,
  aggregate: bool = False,
  allow_rebuild: bool = True,
) -> { modules: [ { module, stmt_pct, branch_pct, classes?: [...] } ], rebuilt, log, status }
```

Same freshness/rebuild precondition; answers module-level "is this module above its floor?" questions.

### Design notes

- **Implicit rebuild is made observable and controllable.** A "read coverage" call may transparently
  trigger a multi-minute `sbt` build — that is correct (stale data is worse than slow data), but the tool
  must not hide it: it returns `rebuilt: [...]` and the `log` path, and `allow_rebuild=False` lets the
  caller demand fail-if-stale instead. That boolean is the only lever the subagent used to need judgment
  for.
- **Batching** across all requested classes in a single call is why the primary tool takes a *list*: five
  classes spanning three stale modules trigger one `sbt` run, not three.
- **`status` is `ok | error` only.** On a real `sbt` failure the tool returns `error` + the log path; the
  main agent reads the log and decides. There is no automatic retry and no special-casing of any failure
  signature (see §6).

## 5. What the skill becomes

`SKILL.md` keeps **only** the coverage policy (thresholds, never-lower-the-floor, 80%-for-new-files) and a
short "how to call it" pointing at `coverage_report` / `module_coverage`. The following are **removed**:

- The entire "delegate to the custom subagent" section and the `Agent(...)` invocation block.
- The "Dependency: requires the custom subagent" section.
- The duplicated workflow pointer to the agent file.
- The TASTy paragraph instructing "stop and wait for user input" (see §6).

## 6. TASTy failure detection removal (issue resolved)

The sbt-scoverage + Scala 3 concurrency issue that produced intermittent TASTy / companion-class errors is
**solved**, so all related handling is removed rather than ported:

- **New MCP:** `run_coverage` does **no** retry and does **no** TASTy classification; `status` is `ok` or
  `error` only.
- **Skill:** delete the paragraph that tells the main agent to stop and wait on a TASTy-flake report.
- **Retired with the scripts/agent:** the agent prompt's "Retry rule" (retry once on TASTy-type errors)
  and the Bash wrappers' log-rotation-for-retry rationale go away with those files (§7).

**Scope boundary:** this removal covers the skill and the new MCP, as requested. The broader dev docs that
mention the historical issue — `docs/development/scoverage-issue.md`, `docs/development/coverage.md`,
`docs/agents/dev-stack.md`, `docs/development/claude-code-setup.md`, `docs/development/build.md`,
`docs/development/README.md` — are **out of scope** for this refactor. Cleaning or deleting
`scoverage-issue.md` and its references can be a small follow-up; flagged here so it is not forgotten.

## 7. File-by-file change list

**Add**

- `.claude/mcp/scoverage_inspector/scoverage_core.py`
- `.claude/mcp/scoverage_inspector/server.py`
- `.claude/mcp/scoverage_inspector/tests/test_scoverage_core.py`
- `.claude/mcp/scoverage_inspector/tests/resources/scoverage-sample.xml`
- `.mcp.json` (register the stdio server)

**Modify**

- `.claude/skills/scoverage-inspector/SKILL.md` — strip delegation + dependency + TASTy paragraph; keep
  policy; point at the MCP tools.

**Remove**

- `.claude/agents/scoverage-inspector.md` (the subagent).
- `.claude/skills/scoverage-inspector/scripts/` — `class_summary.py`, `class_uncovered_lines.py`,
  `module_summary.py`, `coverage_freshness.py`, `run_coverage_modules.sh`, `run_coverage_all.sh`
  (logic absorbed into `scoverage_core.py`). *Decision point:* optionally retain thin CLI shims for
  humans/CI — see §8.

**Unchanged**

- `logs/skills/scoverage-inspector/` log location and rotation behavior.
- The coverage policy semantics.

## 8. Implementation order (TDD)

**All Python in the MCP is unit-tested — `scoverage_core.py` and `server.py` both.** Every public function
and tool handler gets tests, including error/edge paths (missing report, unresolvable FQN, zero/multiple
glob matches, stale-with-`allow_rebuild=False`, `sbt` returning a non-zero `error` status). Use a single
test framework run from CI/locally (recommendation: stdlib `unittest`, or `pytest` if we accept the dev
dependency — see §10). The bar: the MCP Python has no untested branches.

1. Write `test_scoverage_core.py` against a small fixture XML: resolver, freshness, class summary, class
   uncovered lines, module summary — happy paths and the error/edge paths above. Red.
2. Implement `scoverage_core.py` until green. Refactor.
3. Write `test_server.py`: tool registration/dispatch, argument parsing/validation, core→tool result
   shaping, and core-error → `status: "error"` mapping. The `sbt` runner is mocked so server tests never
   shell out. Red, then implement `server.py` until green.
4. Add `.mcp.json`; verify the tool is callable from a session (manual stdio handshake +
   `coverage_report` round-trip).
5. Slim `SKILL.md`; delete the agent and the scripts; remove TASTy handling.
6. Update any references (e.g. `.claude/settings.json` hooks, dev docs that point at the scripts) so
   nothing dangles.

## 9. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| MCP server is a new always-on failure surface (a crash takes out all coverage tools). | Keep all logic in `scoverage_core.py` as a plain importable, unit-tested module; `server.py` is a thin wrapper. Core stays runnable/testable without the server. |
| Implicit rebuild surprises with multi-minute latency. | `rebuilt`/`log` in every return + `allow_rebuild=False` escape hatch (§4). |
| Glob-based resolution is less precise than metals (e.g. two identical FQNs). | The FQN includes the full package path, so collisions require the *same* FQN in two modules, which should not occur; resolver returns an explicit error if it matches zero or >1 file. |
| MCP runtime/deps (e.g. the `mcp` pip package) may not be present where Claude Code runs. | See §8 open question — prefer a zero-extra-dependency approach or a pinned `uvx` invocation. |

## 10. Open questions for the reviewer

1. **Server runtime:** FastMCP (`mcp` pip package, launched via `uvx`/`pipx`) for less boilerplate, or a
   hand-rolled stdlib JSON-RPC stdio loop for zero extra dependencies? Recommendation: hand-rolled stdlib
   if the protocol surface is small, to keep the toolchain dependency-free.
2. **CLI shims:** keep thin standalone CLI entry points over `scoverage_core.py` for humans/CI, or fold
   everything into the server only? Recommendation: keep tiny shims — cheap, and useful for manual/CI use.
3. **`scoverage-issue.md`:** delete it and scrub its references now (separate small PR), or leave it as
   historical record? (Out of scope here per §6.)
4. **Test framework:** stdlib `unittest` (zero dev deps, runs anywhere `python3` exists) or `pytest`
   (nicer assertions/fixtures, adds a dev dependency)? Either way the requirement is identical — all MCP
   Python is unit-tested (§8). Recommendation: `unittest`, to keep the toolchain dependency-free and align
   with the zero-dep server option in (1).
