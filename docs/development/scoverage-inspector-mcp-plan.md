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

Once the helper-script logic is moved *in-process into an MCP server*, every *mechanical* step the subagent
performs becomes deterministic code rather than LLM reasoning:

- **Freshness-check → rebuild** becomes an automatic precondition of every query tool, not a step an agent
  sequences by hand.
- **XML parsing** already returns small structured results; in-process it returns typed data directly
  instead of stdout the agent must re-read.
- **Verbose `sbt` output** is captured to a log file by the tool; only a compact status + log path is
  returned, so it never enters any agent's context.

The one step that is *not* pure mechanics — **class → sbt-module resolution** — stays Metals-based and moves
up into the **skill/main-agent layer** (see §4a). It cannot be a filesystem glob: directory names do not
always equal sbt module IDs in this repo (`scMidi`/`sc-midi`, `appConfig`/`config`,
`commonTestUtils`/`common-test-utils`), so a directory-walk would yield the wrong module. And the MCP — a
plain child process — cannot call `mcp__metals__inspect` itself. So the main agent does the resolution with
one or two cheap Metals calls and passes the resolved sbt module ID(s) into the MCP tools. That is a couple
of direct tool calls in the main agent, **not** a reason to keep a nested subagent.

With no mechanical judgment node left and no verbose output to quarantine, the subagent's two reasons to
exist both disappear. The mechanical work costs ~nothing in tokens (it's Python returning hundreds of
bytes), so there is no Haiku saving to capture, and dropping the nested agent loop *reduces* wall-clock
latency.

## 2. Goals and non-goals

### Goals

- Move all coverage-inspection mechanics into an in-process MCP server backed by a pure, unit-tested
  Python module.
- **Unit-test every line of Python in the MCP** — both `scoverage_core.py` and `server.py`. No Python
  ships without tests (see §8).
- Make freshness-check-and-maybe-rebuild an internal precondition of every query tool.
- Remove the `scoverage-inspector` subagent. **Keep** Metals-based class → module resolution, but move it
  into the skill (the main agent calls `mcp__metals__inspect` and passes resolved sbt module IDs to the MCP).
- Slim the skill down to the coverage *policy*, the Metals resolution step, plus "call the tool".
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
  scoverage_core.py     # pure logic: module map, freshness, parsers, sbt runner — no MCP, no I/O coupling
  server.py             # thin stdio FastMCP server wrapping scoverage_core
  cli.py                # thin argparse shims over scoverage_core for humans/CI (see §7)
  tests/
    test_scoverage_core.py    # unit tests for the pure logic
    test_server.py            # unit tests for the MCP wrapper (tool dispatch, arg parsing, error mapping)
    test_cli.py               # unit tests for the CLI shims
    resources/
      scoverage-sample.xml    # small fixture report
      build-sample.sbt        # fixture for the module-ID ↔ directory parser
.mcp.json               # registers the server (stdio transport, launched via uvx)
```

- **`scoverage_core.py`** — pure functions returning dataclasses, independently runnable and testable:
  - `module_dir_map(build_sbt) -> dict[str, str]` — parse `lazy val <id> = (project in file("<dir>"))`
    declarations from `build.sbt` to map sbt module ID → directory. This is the key piece that handles the
    `scMidi`/`sc-midi`, `appConfig`/`config`, `commonTestUtils`/`common-test-utils` mismatches: the report
    lives at `coverage-reports/<id>/...` (sbt uses `thisProject.value.id`, see `build.sbt`), while sources
    live at `<dir>/src/...`. The MCP receives the **sbt module ID** (already resolved upstream via Metals)
    and uses this map to find the source directories for the freshness check.
  - `freshness(module_id, aggregate=False) -> Freshness(FRESH|STALE|MISSING)` (ports
    `coverage_freshness.py`, now using `module_dir_map` for the source path — fixing the latent bug where
    the old script assumed dir == id).
  - `run_coverage(module_ids, aggregate=False) -> RunResult(status, log_path)` — wraps `sbt`, captures
    output to `logs/skills/scoverage-inspector/sbt-run.log` (file only, **not** returned inline), bakes in
    the `-Dmicrotonalist.build.targetSuffix=-scoverage` flag. **No retry, no TASTy classification.**
    `status` is `OK` or `ERROR` only.
  - `class_summary(...)`, `class_uncovered_lines(...)`, `module_summary(...)` — port the three reader
    scripts, keeping `iterparse` streaming, returning dataclasses. These key off the FQN (`class name` in
    the XML), so they need only the report path (derived from the module ID), not the source directory.
- **`server.py`** — a `stdio` MCP server built with **FastMCP** (`mcp` pip package), exposing the tools in
  §4. Class → module resolution is **not** done here; tools receive already-resolved sbt module IDs.
- **`cli.py`** — thin `argparse` entry points over `scoverage_core` so the functions stay runnable by hand
  and from CI (decided in §10).
- **`.mcp.json`** — registers the server so Claude Code launches it as a child process over stdio, via
  `uvx` (so the `mcp` dependency is fetched/pinned without polluting the repo toolchain).

## 4. Tool API

### 4a. Resolution happens upstream (skill / main agent)

Before calling the MCP, the main agent resolves each fully-qualified class name to its **sbt module ID**
using `mcp__metals__inspect` (the skill carries this step). Metals is authoritative for symbol → build
target and sidesteps the directory ≠ module-ID mismatches that a glob cannot. The agent then passes
`(module, fqn)` pairs to the tools below; the MCP never resolves classes itself.

### `coverage_report` (primary)

```
coverage_report(
  classes: list[{ module: str, fqn: str }],   # module = sbt module ID, resolved upstream via Metals
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

1. Dedupe the module set from the supplied `(module, fqn)` pairs (no resolution here — done upstream §4a).
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

`SKILL.md` keeps the coverage policy (thresholds, never-lower-the-floor, 80%-for-new-files), the **Metals
resolution step** (FQN → sbt module ID via `mcp__metals__inspect`, §4a), and a short "how to call it"
pointing at `coverage_report` / `module_coverage`. The following are **removed**:

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
`docs/development/README.md` — are **left untouched**. In particular, **`docs/development/scoverage-issue.md`
is kept** as a historical record (decided); we are not deleting it or scrubbing its references in this or a
follow-up refactor.

## 7. File-by-file change list

**Add**

- `.claude/mcp/scoverage_inspector/scoverage_core.py`
- `.claude/mcp/scoverage_inspector/server.py`
- `.claude/mcp/scoverage_inspector/cli.py` (thin CLI shims over the core — kept, decided §10)
- `.claude/mcp/scoverage_inspector/tests/test_scoverage_core.py`
- `.claude/mcp/scoverage_inspector/tests/test_server.py`
- `.claude/mcp/scoverage_inspector/tests/test_cli.py`
- `.claude/mcp/scoverage_inspector/tests/resources/scoverage-sample.xml`
- `.claude/mcp/scoverage_inspector/tests/resources/build-sample.sbt`
- `.mcp.json` (register the stdio server, launched via `uvx`)

**Modify**

- `.claude/skills/scoverage-inspector/SKILL.md` — strip delegation + dependency + TASTy paragraph; keep
  policy + the Metals resolution step (§4a); point at the MCP tools.

**Remove**

- `.claude/agents/scoverage-inspector.md` (the subagent).
- `.claude/skills/scoverage-inspector/scripts/` — `class_summary.py`, `class_uncovered_lines.py`,
  `module_summary.py`, `coverage_freshness.py`, `run_coverage_modules.sh`, `run_coverage_all.sh`. Their
  reader/freshness logic is absorbed into `scoverage_core.py`; the standalone command-line entry points
  they provided are preserved by the new `cli.py` shims (§10).

**Unchanged**

- `logs/skills/scoverage-inspector/` log location and rotation behavior.
- The coverage policy semantics.

## 8. Implementation order (TDD)

**All Python in the MCP is unit-tested — `scoverage_core.py`, `server.py`, and `cli.py`.** Every public
function, tool handler, and CLI entry point gets tests, including error/edge paths (missing report, class
not found in the report, unknown module ID, `module_dir_map` parsing of the mismatched modules, stale-with-
`allow_rebuild=False`, `sbt` returning a non-zero `error` status). Tests run on **stdlib `unittest`**
(decided §10), locally and in CI. The bar: the MCP Python has no untested branches.

1. Write `test_scoverage_core.py` against the fixture XML + `build-sample.sbt`: `module_dir_map` (covering
   the `scMidi`/`sc-midi` family), freshness, class summary, class uncovered lines, module summary — happy
   paths and the error/edge paths above. Red.
2. Implement `scoverage_core.py` until green. Refactor.
3. Write `test_server.py`: tool registration/dispatch, argument parsing/validation, core→tool result
   shaping, and core-error → `status: "error"` mapping. The `sbt` runner is mocked so server tests never
   shell out. Red, then implement `server.py` until green.
4. Write `test_cli.py`: each shim parses args and delegates to the core correctly (core mocked). Red, then
   implement `cli.py` until green.
5. Add `.mcp.json` (uvx launch); verify the tool is callable from a session (manual stdio handshake +
   `coverage_report` round-trip).
6. Slim `SKILL.md` (keep policy + Metals resolution step); delete the agent and the scripts; remove TASTy
   handling.
7. Update any references (e.g. `.claude/settings.json` hooks, dev docs that point at the scripts) so
   nothing dangles.

## 9. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| MCP server is a new always-on failure surface (a crash takes out all coverage tools). | Keep all logic in `scoverage_core.py` as a plain importable, unit-tested module; `server.py` is a thin wrapper. Core stays runnable/testable without the server. |
| Implicit rebuild surprises with multi-minute latency. | `rebuilt`/`log` in every return + `allow_rebuild=False` escape hatch (§4). |
| Directory name ≠ sbt module ID (`scMidi`/`sc-midi`, `appConfig`/`config`, `commonTestUtils`/`common-test-utils`). | Resolution is done by Metals upstream (authoritative for symbol → module), not by directory-walk; inside the MCP, `module_dir_map` parses `build.sbt` to translate module ID → source directory for freshness. The latent bug in the old freshness script (which assumed dir == id) is fixed by this map. |
| FastMCP / `mcp` pip package not present where Claude Code runs. | Launch the server via `uvx` in `.mcp.json`, which fetches and pins the dependency on demand without adding it to the repo toolchain. |

## 10. Resolved decisions

1. **Class → module resolution:** **Metals**, in the skill/main-agent layer (§4a). Not a filesystem glob —
   directory names don't always equal sbt module IDs in this repo. The MCP receives resolved sbt module IDs.
2. **Server runtime:** **FastMCP** (`mcp` pip package), launched via **`uvx`** from `.mcp.json`.
3. **CLI shims:** **kept** — thin `cli.py` `argparse` entry points over `scoverage_core.py` for humans/CI,
   preserving the standalone command-line use the removed scripts provided.
4. **Test framework:** **stdlib `unittest`** (zero dev deps, runs anywhere `python3` exists). All MCP Python
   is unit-tested (§8).
5. **`docs/development/scoverage-issue.md`:** **kept** as a historical record; not deleted and its
   references are not scrubbed (§6).
