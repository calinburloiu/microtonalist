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

This skill carries the coverage **policy** (your job, as the main agent) and tells you how to
resolve classes to sbt modules and call the **scoverage-inspector MCP server**, which does all the
mechanical work (freshness check, rebuild if stale, XML parsing) in-process.

## Coverage policy

Code coverage is measured via [scoverage](https://github.com/scoverage/sbt-scoverage). Each SBT
project has per-module statement and branch thresholds configured in `build.sbt` via the
`coverageSettings` helper. To inspect the current thresholds without reading `build.sbt`, run
`sbtn coverageThresholds` — it prints a table of stmt% and branch% floors for every covered module.

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

For the manual `sbt coverageAll` / `coverageModules` workflow and CI's `coverageCheck`, see
`docs/development/coverage.md`.

## Step 1 — Resolve each class to its sbt module ID

The MCP receives **sbt module IDs**, not class names — it cannot resolve symbols itself. For each
fully-qualified class the user named, find the module that *defines* it.

This is a "which file declares this symbol?" lookup, and the precise tool for it is a textual search
for the **declaration** — not Metals.

1. Search for the declaration under `src/main/scala` — match the keyword, **not** the filename,
   which may differ from the class name:

   ```bash
   rg -l -g '**/src/main/scala/**' \
     'class MainConfigManager\b|object MainConfigManager\b|trait MainConfigManager\b|enum MainConfigManager\b'
   ```

   The `class|object|trait|enum X\b` form can't match a same-named variable, so this is precise;
   `\b` keeps `Scale` from matching `ScaleFormat`.
2. By build convention the **sbt module ID is the first path segment** of the match (the base
   directory name; `build.sbt` enforces this with `.withId(<dir>)`). IDs may be kebab-case
   (`sc-midi`, `common-test-utils`, `config`). Example:
   `org.calinburloiu.music.microtonalist.config.MainConfigManager` is declared in
   `config/src/main/scala/org/calinburloiu/music/microtonalist/config/ConfigManagement.scala` — note
   the file is **not** named `MainConfigManager.scala` — so the module is `config`.

Deduplicate the resulting set of `(module, fqn)` pairs.

**If `rg` returns more than one file** (two same-named declarations in different packages/modules),
disambiguate by the class's package: keep the file whose `src/main/scala/<…>` path matches the FQN's
package. If you need Metals to confirm a symbol resolves, `mcp__metals__get-usages` (with a
project-wide `fileInFocus`; see the root `CLAUDE.md` "Symbol tool file focus" section) lists
reference paths rooted at their module directories — the definition is the `src/main` entry in the
class's own package.

## Step 2 — Call the MCP tools

The two MCP tools (`coverage_report` and `module_coverage`) carry their own parameter docs — read
their schemas for argument details rather than relying on this section. If they aren't already
callable, fetch their schemas with ToolSearch before calling. What follows is the usage judgment the
schemas can't give you.

**Which tool.** Use `coverage_report` (primary) for "is this class covered, and where are the
gaps?"; pass **all** classes in one call so a single rebuild covers every stale module. Use
`module_coverage` (secondary) for "is this module above its floor?".

**Reading the result.** Both tools freshness-check every requested module and transparently rebuild
stale or missing reports via a single batched `sbt` run — potentially multi-minute. Each result
names the modules that were `rebuilt` and a `log` path, so the build is always visible. `status` is
`"ok"` or `"error"`; on `"error"`, read the `log` and decide what to do.

**Aggregate vs per-module.** Default coverage reflects only a module's **own** tests. Set
`aggregate=True` only when the user explicitly wants caller tests in other modules counted (e.g.
tests in `composition` that exercise `intonation`'s `Scale`).

## Step 3 — Cite gaps as `file:line`

Report uncovered locations in the `file:Lline` form the tools return (e.g.
`org/calinburloiu/music/intonation/Scale.scala:L88`) so they render as clickable locations. Never
paste raw XML.

## Command-line fallback

If the MCP server is unavailable, the same logic is runnable directly via the CLI shim:

```bash
python3 .claude/mcp/scoverage_inspector/cli.py class-summary <module> <fqn> [--aggregate] [--overall-only]
python3 .claude/mcp/scoverage_inspector/cli.py class-uncovered <module> <fqn> [--aggregate]
python3 .claude/mcp/scoverage_inspector/cli.py module-summary <module> [--aggregate] [--overall-only]
python3 .claude/mcp/scoverage_inspector/cli.py freshness <module> [--aggregate]
python3 .claude/mcp/scoverage_inspector/cli.py run-coverage <module> [<module> ...] | --aggregate
```
