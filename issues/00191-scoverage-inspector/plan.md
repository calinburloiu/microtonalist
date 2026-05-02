# Plan: `coverageModules` command + `scoverage-inspector` skill

## Context

Today the agent has two coverage commands available: `coverageAll` (runs every
module, slow) and `coverageModule <module>` (runs exactly one module). A
common real workflow is "I just touched a few classes — show me how well
they're covered, and where the gaps are." That set of classes typically spans
**a few related modules**, so neither current command fits well: `coverageAll`
wastes a lot of work; `coverageModule` has to be invoked once per module,
each invocation paying the full clean + instrument + clean cost.

Two changes follow:

1. Replace the single-module command with a varargs version
   (`coverageModules`) so a focused multi-module run is a single sbt
   invocation.
2. Add a `scoverage-inspector` skill so the agent (a) knows *when* to re-run
   coverage vs. reuse the existing report, and (b) extracts per-class
   numbers and uncovered lines from `scoverage.xml` cheaply, without
   pulling 3000-line XML files into context.

## Part 1 — `project/Coverage.scala`

Rename `coverageModule` to `coverageModules` and accept **one or more**
module IDs. All listed modules' tests run inside a single `coverage` session,
each producing its own report.

### File to modify

- `project/Coverage.scala`

### Change

Replace the `coverageModule` definition with:

```scala
private def coverageModules: Command = Command.args("coverageModules", "<module> [<module> ...]") {
  (state, args) =>
    if (args.isEmpty) {
      state.globalLogging.full.error("Usage: coverageModules <module> [<module> ...]")
      state.fail
    } else {
      val testTasks   = args.map(m => s"$m/test").toList
      val reportTasks = args.map(m => s"$m/coverageReport").toList
      ("clean" :: "coverage" :: testTasks ::: reportTasks ::: "clean" :: Nil) ::: state
    }
}
```

And update the registration line:

```scala
val commands: Seq[Command] = Seq(coverageAll, coverageModules, coverageCheck, coverageClean)
```

ScalaDoc on the object needs the same rename + a sentence noting the varargs
behaviour and that the single trailing `clean` covers all listed modules
because `coverageEnabled` is set globally (existing rationale already in the
doc — just generalize the wording from "the named module" to "the named
modules").

### Doc updates

- `CLAUDE.md` — under "Running coverage", replace
  `sbt "coverageModule <module>"` with
  `sbt "coverageModules <module> [<module> ...]"` and update the example
  (`sbt "coverageModules intonation"` plus a multi-module example such as
  `sbt "coverageModules tuner intonation"`).
- Auto-memory: `~/.claude/projects/-Users-calinburloiu-Development-microtonalist/memory/feedback_coverage_commands.md`
  currently recommends `coverageModule`; update to `coverageModules`.

### Verification

- `sbt "coverageModules intonation"` succeeds, writes
  `coverage-reports/intonation/scoverage-report/scoverage.xml`, no other
  module's report is regenerated.
- `sbt "coverageModules tuner intonation"` writes both reports in a single
  run.
- `sbt coverageModules` (no args) fails with the usage message.
- Confirm `target/` is cleaned at the end and `coverage-reports/` survives.

## Part 2 — Skill: `scoverage-inspector`

### Should this be a skill?

**Yes.** Two reasons it doesn't fit "agent handles it out of the box":

1. **Non-trivial decision policy.** "Run coverage now vs. trust the existing
   report" depends on mtime comparisons between sources/tests and the XML.
   Without a skill the agent will either re-run coverage every time
   (expensive) or silently use stale reports.
2. **`scoverage.xml` is token-expensive to read raw.** The intonation
   report is ~3000 lines / ~200 KB; a multi-module session would blow tens
   of thousands of tokens. There is **no off-the-shelf CLI** that prints a
   terse per-class summary or uncovered-line list from scoverage XML
   (scoverage ships HTML, raw XML, and Cobertura — none token-friendly).
   A small bundled Python script solves this once.

### Do we need `coverageClean` first?

**No.** `coverageModules` already starts with `clean`, which wipes
`target/scoverage-data` (the raw measurement files). The `coverageReport`
task then fully rewrites `coverage-reports/<module>/scoverage-report/`
for every module listed. Modules **not** listed keep their existing XML —
which is exactly what the freshness check below is for. Forcing
`coverageClean` would just guarantee a re-run for unrelated modules. The
skill should not call it.

### Class → module resolution via Metals

Don't grep filesystem paths to find modules. Use the Metals MCP:

1. `mcp__metals__inspect` with the FQN returns the symbol's source file
   path (`/.../<module>/src/main/scala/...`).
2. Walk up to the segment immediately preceding `src/main/scala` — that
   directory name is the sbt module ID.

This costs one MCP call per class and avoids loading source files into
context. Fall back to `find . -name <ClassName>.scala` only if Metals
isn't available.

### Skill layout

Create via `skill-creator` at `~/.claude/skills/scoverage-inspector/` with:

```
scoverage-inspector/
  SKILL.md
  scripts/
    coverage_freshness.py        # is the XML still valid for these sources?
    module_summary.py            # whole-module: overall % + one line/class
    class_summary.py             # one class: overall + per-method %
    class_uncovered_lines.py     # one class: compressed uncovered line ranges
```

Splitting class summary and uncovered lines into two scripts means the
agent only pays for what it asked for — "what's the % for X" doesn't have
to also stream every uncovered statement.

### `SKILL.md` — triggering and behavior

**Triggers.** User asks to "check coverage of <classes>", "are these classes
covered", "what's missing in tests for X", or after the agent finishes a
code change and wants to verify the per-module floor in `build.sbt` still
holds.

**Workflow:**

1. **Resolve classes → modules** via Metals (above). Deduplicate.
2. **Freshness check.** For each module, run
   `scripts/coverage_freshness.py <module>`. It compares the mtime of
   `coverage-reports/<module>/scoverage-report/scoverage.xml` against the
   newest mtime under `<module>/src/main/scala` and `<module>/src/test/scala`.
   Exit code 0 = fresh, 1 = stale, 2 = missing.
3. **Run coverage only for stale/missing modules.** Single invocation:
   `sbt "coverageModules <m1> <m2> ..."`. Skip entirely if all fresh.
4. **Read what the user asked for, not the whole report.**
   - Module overview → `module_summary.py <module>`: overall stmt/branch %,
     then one line per class: `FQN  stmt%  branch%  uncovered-lines:N`.
   - Class numbers only → `class_summary.py <module> <FQN>`: overall
     stmt/branch %, per-method stmt/branch %.
   - Class uncovered lines only → `class_uncovered_lines.py <module> <FQN>`:
     compressed source-line ranges grouped by file
     (`Scale.scala: 88-91, 134, 201-205`), built by collapsing
     `<statement ... line="N" invocation-count="0">` entries.
5. **Cite line numbers as `file:line` so they're navigable.** Don't paste
   raw XML into the response.

**Anti-patterns the skill calls out:**

- Don't re-run `coverageModules` if the freshness script says fresh.
- Don't `cat` `scoverage.xml` — always go through the helper scripts.
- Don't use `coverageAll` for "check a few classes" work.
- Don't run `coverageClean` as a precaution; it forces re-runs on
  unrelated modules without protecting against any real staleness bug.
- Reports under `coverage-reports/` survive `sbt clean`, so a clean
  between unrelated tasks does **not** invalidate them — only source/test
  edits do. The freshness script is the source of truth.

### Script implementation notes

All scripts use Python's stdlib `xml.etree.ElementTree.iterparse` so they
stream the file (constant memory) and only emit a few hundred bytes of
text. **No third-party deps** — Python 3 stdlib only. Each accepts the
repo root via `--root` (default `$PWD`). Compressed line ranges use the
standard "collapse consecutive integers" idiom.

### Python as a project prerequisite

Skill scripts run on the contributor's machine, not bundled into the JVM
build. Add a "Python 3" entry to **`docs/development/README.md`** (the
existing dev-prereqs guide already lists JDK, SBT, Coursier — Python
belongs here, not in the top-level `README.md` which is user-facing).
Note that:

- Python ≥ 3.10 is sufficient (macOS 14+ ships Python 3 by default;
  otherwise `brew install python` or use SDKMAN!).
- The skill scripts only use the stdlib, so **no venv or `pip install`
  step is needed**. If a future script needs a third-party package, the
  guide will need to grow a `requirements.txt` + venv section then.

### Verification

- `coverage_freshness.py intonation` exits 0 on a fresh report; after
  `touch`-ing a source file under `intonation/src/main/scala/...`, exits 1.
- `module_summary.py intonation` prints under ~50 lines and matches the
  percentages shown in `coverage-reports/intonation/scoverage-report/index.html`.
- `class_summary.py intonation org.calinburloiu.music.intonation.Scale`
  prints overall + per-method numbers matching the HTML.
- `class_uncovered_lines.py intonation org.calinburloiu.music.intonation.Scale`
  prints compressed ranges matching the highlighted lines in the HTML.
- **End-to-end agent run with two classes from two different modules:**
  ask Claude "check coverage of `org.calinburloiu.music.intonation.Scale`
  and `org.calinburloiu.music.microtonalist.tuner.MtsTuner`". Expected
  behavior: Metals resolves them to `intonation` and `tuner`, freshness
  is checked for both, a single `sbt "coverageModules intonation tuner"`
  runs only if needed, then `class_summary.py` (and/or
  `class_uncovered_lines.py`) is called once per class. No raw XML in
  the conversation.

## Order of work

1. Land the `Coverage.scala` rename + doc/memory updates first — the skill
   depends on the new command.
2. Add the Python prerequisite to `docs/development/README.md`.
3. Create the skill via `skill-creator` with the four scripts.
