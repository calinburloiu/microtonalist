# Plan — Fix and improve `scoverage-inspector` skill (issue #191 reopened)

## Context

Issue #191 was reopened to address three follow-up problems with the
`scoverage-inspector` skill that shipped in PR #192:

1. The Haiku subagent that runs the skill workflow has been observed to
   wander off-script when something fails — editing source files,
   inventing alternate workflows, or burning tokens trying to "fix" the
   well-known scoverage + Scala 3 TASTy bug documented in
   `docs/development/scoverage-issue.md`. The subagent must be strictly
   read-only (except for log writes) and must escalate failures to the
   main agent instead of trying to repair them.
2. Two helper scripts (`module_summary.py`, `class_summary.py`) print
   both an overall summary and a per-class / per-method breakdown.
   Callers that only need the overall numbers pay tokens for noise.
3. `AGENTS.md`'s `# Coverage` section has grown long. The detailed
   "how to run coverage manually" content should move into a dedicated
   `docs/development/coverage.md` so `AGENTS.md` keeps only the rules,
   pointers to the skill, and pointers to the issue/coverage docs.

The task is purely doc + skill wiring + small script CLI tweaks. No
production Scala code changes.

## Critical files

- `.claude/skills/scoverage-inspector/SKILL.md` — subagent prompt and
  workflow.
- `.claude/skills/scoverage-inspector/scripts/module_summary.py`
- `.claude/skills/scoverage-inspector/scripts/class_summary.py`
- `AGENTS.md` (symlinked from `CLAUDE.md`) — `# Coverage` section
  starting at line 187.
- `docs/development/coverage.md` — new file.
- `docs/development/scoverage-issue.md` — referenced (no edits).

## Change 1 — Subagent failure handling (SKILL.md)

Edit the "For the main agent: delegate to Haiku" section and the
"Workflow" section to encode these constraints. Generalize what the
current SKILL.md already sketches (it teеs one log; we need to handle
N runs, recognize the bug, and escalate cleanly).

**Subagent constraints to add to the spawn prompt:**

- "You are read-only with one exception: you may write log files under
  `logs/skills/scoverage-inspector/`. You must not edit any other file,
  not source code, not config, not `build.sbt`, not the helper Python
  scripts, not this SKILL.md."
- "If anything looks wrong — sbt failure, Python script error,
  suspicious 0% coverage where 0% does not make sense, missing report,
  any unexpected output — stop and report it back to the main agent.
  Do not investigate or fix. The main agent (or user) will handle it."
- "You may retry an `sbt coverage…` command **at most once**, and only
  if the failure mentions TASTy files, `error while loading`,
  `Not found: type`, `does not take parameters`, `is not a member of
  object`, or `NoClassDefFoundError` at test runtime. Any other failure
  → report and stop, do not retry."
- "Each sbt invocation must write to its own log file:
  `logs/skills/scoverage-inspector/sbt-run-<N>.log`, where `<N>` starts
  at 1 and increments on retry (`sbt-run-1.log`, `sbt-run-2.log`).
  The directory should be created with `mkdir -p` once."
- "When you report back to the main agent, list every sbt log file you
  wrote so a human or the main agent can inspect them."

**Workflow section edits:**

- Step 3: replace the single `tee … last-sbt-run.log` invocation with
  the numbered-log scheme above. Show the command form for both the
  initial run and a retry.
- Add a short "Failure handling" subsection after step 3 that restates
  the retry rule and the escalation rule in workflow voice (mirrors the
  spawn-prompt constraints, so the subagent sees them in both places).
- Anti-patterns: add "Don't try to fix sbt/scoverage failures by editing
  code. Report and stop." and "Don't reuse the same log file across
  retries."

Deliberately **do not** reference `docs/development/scoverage-issue.md`
from SKILL.md — it's long, not part of the skill bundle, and per the
user's note the one-sentence symptom hint above is enough Haiku-level
training.

## Change 2 — Overall-only flag for the two summary scripts

Both scripts already print the overall line first and the breakdown
second. Add a single new flag that suppresses the breakdown.

- `module_summary.py`: add `--overall-only` (suppresses the per-class
  rows; still prints the one-line module header).
- `class_summary.py`: add `--overall-only` (suppresses the per-method
  rows; still prints the one-line class header).

`--overall-only` reads naturally with both scripts and is symmetric.
Default behavior is unchanged. No new helpers, no refactor — guard the
existing for-loop that prints rows.

Update SKILL.md step 4 to mention the flag for the "I just want the
percentages" case.

## Change 3 — Restructure coverage docs

**Create `docs/development/coverage.md`** containing:

- Brief restatement of the project's coverage principles (the 80%
  target, the per-module floor, the new-file rule) — duplicated from
  `AGENTS.md` so the doc stands alone for human readers.
- The full "Running coverage" content currently at AGENTS.md:207–254
  (the `coverageAll` vs `coverageModules` choice, the post-run `clean`
  rule, the `sbtn` exception, the report locations, `coverageClean`).
- A short pointer at the top: "For routine coverage inquiries, prefer
  the `scoverage-inspector` skill over running these commands by hand."

**Trim AGENTS.md's `# Coverage` section** to:

- Coverage principles (lines 189–205, kept as-is).
- New paragraph: "For coverage inquiries — checking a class's coverage,
  finding gaps, verifying a module still meets its threshold — use the
  `scoverage-inspector` skill rather than running `sbt coverage…` by
  hand."
- New paragraph: "Coverage runs occasionally fail with TASTy /
  companion-class errors due to a known sbt-scoverage + Scala 3 bug
  documented in [`docs/development/scoverage-issue.md`]. If the
  `scoverage-inspector` skill reports such a failure, **stop and wait
  for user input** rather than retrying or modifying code."
- New paragraph: "For the manual `sbt coverageAll` /
  `sbt coverageModules` workflow (running coverage outside the skill,
  CI's `coverageCheck`, post-run `clean` rules), see
  [`docs/development/coverage.md`]."
- Keep the `## Shared test utilities` subsection unchanged.

The `# Coverage` rules and the new pointer paragraphs are what the
main agent needs at-a-glance to decide *what to do*; the operational
recipe lives one click away.

## Verification

1. `python3 .claude/skills/scoverage-inspector/scripts/module_summary.py
   intonation --overall-only` — prints only the header line, no class
   rows.
2. `python3 .claude/skills/scoverage-inspector/scripts/class_summary.py
   intonation org.calinburloiu.music.intonation.Scale --overall-only` —
   prints only the class header line, no method rows.
3. Both scripts without `--overall-only` produce identical output to
   today's behavior (regression check).
4. `--aggregate --overall-only` together still work.
5. Read `AGENTS.md` `# Coverage` section end-to-end and confirm:
   principles still present, `scoverage-inspector` mentioned, both
   doc links present, no operational recipe duplication with
   `coverage.md`.
6. Read `docs/development/coverage.md` end-to-end and confirm it stands
   alone (a new contributor could run coverage from this file without
   AGENTS.md).
7. Spot-check `SKILL.md`: subagent prompt mentions read-only, single
   retry on TASTy symptoms, numbered log files, and reporting log
   paths back. Workflow step 3 uses `sbt-run-<N>.log`.

End-to-end skill exercise: trigger the skill
on a small module (e.g. `intonation`) and confirm a single sbt log
appears at `logs/skills/scoverage-inspector/sbt-run-1.log` and the
returned answer cites it.
