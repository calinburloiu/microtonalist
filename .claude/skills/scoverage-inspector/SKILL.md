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

Helps you answer "is this class covered, and where are the gaps?" without
slurping `coverage-reports/<module>/scoverage-report/scoverage.xml` (~3000
lines / ~200 KB per module) into the conversation. The XML has no
off-the-shelf summary CLI, so this skill bundles four small Python stdlib
scripts that stream the file and emit a few hundred bytes each.

## When to use this skill

- The user asks about coverage of one or more specific classes/files.
- The user asks how the coverage of a module looks overall.
- After you edit Scala code, before reporting the task done, to verify the
  module's `coverageMinimumStmtTotal` / `coverageMinimumBranchTotal` floor
  in `build.sbt` still holds and any new files meet the 80% target.
- Any "what's missing in tests for X" / "where are the uncovered lines"
  question.

If the user only asks for a full project run (e.g. "run `sbt coverageAll`"),
defer to that — this skill is for *focused* coverage inspection.

## Workflow

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

For each module in the set, run:

```bash
python3 .claude/skills/scoverage-inspector/scripts/coverage_freshness.py <module>
```

Exit code: `0` fresh, `1` stale (sources newer than report), `2` missing.

Run coverage **only** for the modules that come back stale or missing.
If everything is fresh, skip step 3 entirely and go straight to step 4.

### 3. Run coverage for stale/missing modules — single batched invocation

```bash
sbt "coverageModules <m1> <m2> ..."
```

Use `coverageModules` (varargs) so the whole set runs in one
`clean → coverage → test → coverageReport → clean` cycle, rather than
paying that cost per module. This command is defined in
`project/Coverage.scala`.

**Do not** run `sbt coverageClean` first — the `clean` at the start of
`coverageModules` already wipes stale instrumentation under
`target/scoverage-data`, and the trailing `clean` removes instrumented
`.class`/`.tasty` files. `coverage-reports/` lives outside `target/` and
is preserved on purpose. Forcing `coverageClean` would just invalidate
unrelated modules' reports for no benefit.

**Do not** use `sbt coverageAll` for a focused "check these classes"
question — that runs every module's tests and is exactly what
`coverageModules` exists to avoid.

### 4. Delegate script execution to a Haiku subagent

Running the inspection scripts is purely mechanical — no reasoning needed.
Spawn a **Haiku** subagent via the `Agent` tool so the cheap work stays out
of your (more expensive) main context turns:

```
Agent(
  subagent_type = "general-purpose",
  model         = "haiku",
  prompt        = """
You are a coverage-report reader. In the repo at <absolute-path-to-repo>,
run each of the following commands exactly and return their complete stdout.
Do nothing else.

<commands>
python3 .claude/skills/scoverage-inspector/scripts/<script> <args>
...
</commands>
"""
)
```

Batch all the scripts you need for this query into one agent call — one
Haiku invocation per question, not one per script. The agent returns a few
hundred bytes of text; use that as your source of truth.

Available scripts and when to use each:

| Script | When |
|---|---|
| `module_summary.py <module>` | User asked for a module overview |
| `class_summary.py <module> <FQN>` | User needs stmt/branch % for one class |
| `class_uncovered_lines.py <module> <FQN>` | User needs uncovered lines for one class |

When you need both summary and uncovered lines for the same class, include
both commands in the single Haiku prompt — don't spawn two agents.

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

## Example session

User: "Are `Scale` and `MtsTuner` well covered? Where are the gaps?"

1. `mcp__metals__inspect` resolves `org.calinburloiu.music.intonation.Scale`
   → `intonation/src/main/scala/...` → module `intonation`.
2. `mcp__metals__inspect` resolves
   `org.calinburloiu.music.microtonalist.tuner.MtsTuner` → module `tuner`.
3. `coverage_freshness.py intonation` → 0; `coverage_freshness.py tuner` → 1.
4. `sbt "coverageModules tuner"` (intonation skipped — already fresh).
5. `class_summary.py intonation org.calinburloiu.music.intonation.Scale`
   then `class_uncovered_lines.py intonation ...Scale`.
6. Same pair for `MtsTuner` in `tuner`.
7. Report numbers per class and cite uncovered lines as `file:line`.
