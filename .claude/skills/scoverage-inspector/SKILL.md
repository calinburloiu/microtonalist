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

## For the main agent: delegate to Haiku

When this skill triggers, **do not execute the workflow yourself**. Instead,
spawn a single Haiku subagent that executes the full workflow and returns the
answer. This keeps all the mechanical tool calls (Metals MCP, Bash, script
reads) out of your expensive main-agent turns.

```
Agent(
  subagent_type = "general-purpose",
  model         = "haiku",
  prompt        = """
You are executing a coverage inspection for the microtonalist Scala project.

User's question: <USER_QUESTION_VERBATIM>

Working directory (repo root): <ABSOLUTE_CWD>

IMPORTANT: All commands must be run with cwd = <ABSOLUTE_CWD>. Always call the
helper scripts with RELATIVE paths from the repo root (e.g.
`python3 .claude/skills/scoverage-inspector/scripts/coverage_freshness.py <module>`).
Never construct absolute paths to the scripts.

**You are read-only with one exception: you may write log files under
`logs/skills/scoverage-inspector/`. You must not edit any other file —
not source code, not config, not `build.sbt`, not the helper Python
scripts, not this SKILL.md.**

**If anything looks wrong — sbt failure, Python script error, suspicious
0% coverage where 0% does not make sense, missing report, any unexpected
output — stop and report it back to the main agent. Do not investigate
or fix. The main agent (or user) will handle it.**

**You may retry an `sbt coverage…` command at most once, and only if the
failure mentions TASTy files, `error while loading`, `Not found: type`,
`does not take parameters`, `is not a member of object`, or
`NoClassDefFoundError` at test runtime. Any other failure → report and
stop, do not retry.**

**When you report back to the main agent, list every sbt log file you
wrote so a human or the main agent can inspect them.**

Read the Workflow section of `.claude/skills/scoverage-inspector/SKILL.md`
and follow it exactly to answer the user's question. Return a concise answer
with coverage percentages and uncovered line numbers cited as `file:line`.
"""
)
```

Return Haiku's response verbatim. Do not add your own commentary.

---

## Workflow (executed by the Haiku subagent)

Helps you answer "is this class covered, and where are the gaps?" without
slurping `coverage-reports/<module>/scoverage-report/scoverage.xml` (~3000
lines / ~200 KB per module) into the conversation. The XML has no
off-the-shelf summary CLI, so this skill bundles four small Python stdlib
scripts that stream the file and emit a few hundred bytes each.

### 1. Resolve target classes → sbt module IDs

For each class the user named, get its source file path and walk up to the
segment immediately preceding `src/main/scala`. That directory name **is**
the sbt module ID.

Prefer **Metals MCP** over `find` because it costs one MCP call and avoids
loading source files into context:

```
mcp__metals__inspect with symbol = "<fully.qualified.ClassName>"
```

The result includes the source file path; e.g.
`/<repo>/intonation/src/main/scala/.../Scale.scala` → module `intonation`.

Fallback only if Metals isn't available:
`find . -path '*/src/main/scala/*' -name '<ClassName>.scala'`.

Deduplicate the resulting module set.

### 2. Freshness check (per module)

For each module in the set, run (always use these relative paths — never absolute):

```bash
python3 .claude/skills/scoverage-inspector/scripts/coverage_freshness.py <module>
```

Exit code: `0` fresh, `1` stale (sources newer than report), `2` missing.

Run coverage **only** for the modules that come back stale or missing.
If everything is fresh, skip step 3 entirely and go straight to step 4.

### 3. Run coverage for stale/missing modules — single batched invocation

Create the log directory once before the first sbt invocation:

```bash
mkdir -p logs/skills/scoverage-inspector
```

All sbt coverage commands must be run with `-Dmicrotonalist.build.targetSuffix=-scoverage` which forces the target
subdirectories to have `-scoverage` suffix, being named `target-scoverage` instead of `target`. This avoids clashes with
concurrent builds that don't include code instrumented for coverage such as a build from an IDE.

Initial run (`N=1`):

```bash
sbt -Dmicrotonalist.build.targetSuffix=-scoverage "coverageModules <m1> <m2> ..." 2>&1 | tee logs/skills/scoverage-inspector/sbt-run-1.log
```

Retry run (`N=2`), only if the initial failure matches the TASTy/companion-class
symptoms described in the failure-handling subsection below:

```bash
sbt -Dmicrotonalist.build.targetSuffix=-scoverage "coverageModules <m1> <m2> ..." 2>&1 | tee logs/skills/scoverage-inspector/sbt-run-2.log
```

Use `coverageModules` (varargs) so the whole set runs in one
`clean → coverage → test → coverageReport → clean` cycle, rather than
paying that cost per module. This command is defined in
`project/Coverage.scala`.

**Per-module-only by design — caller tests are excluded.** `coverageModules <m>`
runs only `<m>/test`, so the report reflects what `<m>`'s own tests cover, not
what the entire codebase covers. If the user wants `Scale`'s coverage including
all callers (e.g. tests in `composition` or `tuner` that exercise `Scale`),
they want the **aggregate** report instead:

- Run it the same way as `coverageModules`, using the numbered log scheme:

  ```bash
  sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageAll 2>&1 | tee logs/skills/scoverage-inspector/sbt-run-1.log
  ```

  No `coverageModules` shortcut covers this case — the aggregate is built by
  `coverageAggregate` from every module's report, so `coverageAll` is required.
- Then pass `--aggregate` to all four scripts (see step 4). The scripts then
  read `coverage-reports/root/scoverage-report/scoverage.xml` and the freshness
  check considers edits in **any** module, since any change can move the
  aggregate numbers.

#### Failure handling

**Retry rule:** if the sbt run fails and the output mentions TASTy files,
`error while loading`, `Not found: type`, `does not take parameters`,
`is not a member of object`, or `NoClassDefFoundError` at test runtime,
retry **once** using `sbt-run-2.log`. Any other failure — stop and report
back to the main agent. Do not retry more than once for any reason.

**Escalation rule:** any unexpected outcome (non-TASTy sbt failure, Python
script error, suspicious 0% coverage, missing report) must be reported back
to the main agent verbatim. Do not investigate or fix it yourself.

**Always report log paths:** when returning your answer (success or failure),
list every `sbt-run-N.log` file you wrote so the main agent or user can
inspect them.

Without `--aggregate`, the freshness script intentionally ignores edits in
dependent modules because re-running `coverageModules <m>` after such an edit
would produce identical numbers.

**Do not** run `sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageClean` first — the `clean` at the start of
`coverageModules` already wipes stale instrumentation under
`target/scoverage-data`, and the trailing `clean` removes instrumented
`.class`/`.tasty` files. `coverage-reports/` lives outside `target/` and
is preserved on purpose. Forcing `coverageClean` would just invalidate
unrelated modules' reports for no benefit.

**Do not** use `sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageAll` for a focused "check these classes"
question — that runs every module's tests and is exactly what
`coverageModules` exists to avoid.

### 4. Read only what was asked

All three readers stream the XML with `xml.etree.iterparse` (constant
memory, terse output). Run **only the script(s) that directly answer the
question** — never run a script whose output the user did not ask for:

| User asks for…                           | Script(s) to run                                           |
|------------------------------------------|------------------------------------------------------------|
| Module-level percentage(s) only          | `module_summary.py … --overall-only`                       |
| Module overview with per-class breakdown | `module_summary.py …`                                      |
| A class's percentage(s) only             | `class_summary.py … --overall-only`                        |
| A class's percentages + method breakdown | `class_summary.py …`                                       |
| Where are the gaps / uncovered lines     | `class_uncovered_lines.py …`                               |
| Both percentages AND gaps for a class    | `class_summary.py …` **then** `class_uncovered_lines.py …` |

Add `--aggregate` to any of these scripts when the user wants caller-test
coverage (see the note in step 3). Without `--aggregate` the script reads
the per-module report; with it, the script reads
`coverage-reports/root/scoverage-report/scoverage.xml`. The `<module>` arg
stays in the same position either way; in aggregate mode it's used to
filter the module summary to that module's classes (and is informational
for the per-class scripts, which look up by FQN).

**Module overview** — overall + one line per class:

```bash
python3 .claude/skills/scoverage-inspector/scripts/module_summary.py <module> [--aggregate] [--overall-only]
```

Use `--overall-only` to suppress the per-class rows when you only need the
module's headline percentages (saves tokens).

**Single class, percentages only** (overall + per-method):

```bash
python3 .claude/skills/scoverage-inspector/scripts/class_summary.py <module> <FQN> [--aggregate] [--overall-only]
```

Use `--overall-only` to suppress the per-method rows when you only need the
class headline percentages.

**Single class, uncovered source lines only** (compressed ranges):

```bash
python3 .claude/skills/scoverage-inspector/scripts/class_uncovered_lines.py <module> <FQN> [--aggregate]
```

The summary and uncovered-lines scripts are split intentionally — when
you only need percentages, don't pay for streaming every uncovered
statement, and vice versa.

### 5. Cite findings as `file:line`

When you point the user at a gap, use the short relative form printed by
the script (e.g. `org/calinburloiu/music/intonation/Scale.scala:88`) so it
renders as a clickable location. Do not paste raw XML into the response.

## Anti-patterns

- **Don't `cat scoverage.xml`.** Always go through the helper scripts.
- **Don't re-run `coverageModules` if `coverage_freshness.py` says fresh.**
  Reports under `coverage-reports/` survive `sbt clean` — only edits to
  `<module>/src/main/scala` or `<module>/src/test/scala` invalidate them.
- **Don't use `coverageAll` for focused questions.** That defeats the
  point of this skill.
- **Don't run `coverageClean` as a precaution.** It only forces re-runs
  on unrelated modules; it doesn't protect against any real staleness
  bug.
- **Don't print the full module summary when the user asked about one
  class.** Use `class_summary.py` / `class_uncovered_lines.py` instead.
- **Don't run `class_uncovered_lines.py` when the user only asked for
  percentages.** The table in step 4 is the decision rule — follow it
  exactly. "What's the coverage?" → `class_summary.py --overall-only`.
  "Where are the gaps?" → `class_uncovered_lines.py`. Only run both when
  both were asked for.
- **Don't try to fix sbt/scoverage failures by editing code.** Report
  and stop. Let the main agent or user decide what to do.
- **Don't reuse the same log file across retries.** Each sbt invocation
  gets its own numbered log: `sbt-run-1.log`, `sbt-run-2.log`.

## Example session

User: "Are `Scale` and `MtsTuner` well covered? Where are the gaps?"

Main agent spawns a Haiku subagent with that question. Haiku:

1. `mcp__metals__inspect` resolves `org.calinburloiu.music.intonation.Scale`
   → `intonation/src/main/scala/...` → module `intonation`.
2. `mcp__metals__inspect` resolves
   `org.calinburloiu.music.microtonalist.tuner.MtsTuner` → module `tuner`.
3. `coverage_freshness.py intonation` → 0; `coverage_freshness.py tuner` → 1.
4. `mkdir -p logs/skills/scoverage-inspector`, then
   `sbt -Dmicrotonalist.build.targetSuffix=-scoverage "coverageModules tuner" 2>&1 | tee logs/skills/scoverage-inspector/sbt-run-1.log` (
   intonation skipped — already fresh).
5. `class_summary.py intonation org.calinburloiu.music.intonation.Scale`,
   then `class_uncovered_lines.py intonation ...Scale`.
6. Same pair for `MtsTuner` in `tuner`.
7. Returns numbers per class with uncovered lines as `file:line`.

Main agent returns Haiku's answer verbatim.
