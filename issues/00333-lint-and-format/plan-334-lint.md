# Compiler warnings and scalafix (#334) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-09-28
- **Base commit:** `bddbcae9cf034fddc81e55a713d5a310736955d0` (`feature/scalafmt`). Execution starts on `main`, after
  #338 merges (Task 0).
- **Issues:** #334, a sub-issue of #333

**Goal:** Make the compiler and scalafix enforce the coding and test conventions mechanically: warnings become compile
errors locally and in CI, and the Lint step applies and checks the scalafix rules.

**Architecture:** Three stacked, squash-merged PRs. The tooling PR adds the compiler warning flags as plain warnings,
sbt-scalafix with a tuned `.scalafix.conf`, scalafix in the `fix` alias, the hand fixes, and the docs; it enforces
nothing. The bulk PR holds only the output of `sbtn fix`. The finish PR, which starts after the bulk PR and #335's
finish PR have merged, records the bulk PR's squashed SHA in `.git-blame-ignore-revs`, turns on the `-Wconf` policy and
the strict property, extends the `lint` alias, and makes CI's `lint` job strict.

**Tech Stack:** sbt 1.10.7, Scala 3.6.3, sbt-scalafix 0.14.9 (scalafix's built-in rules), sbt-scalafmt 2.5.6 and
scalafmt 3.11.5 (from #335), GitHub Actions.

**Spec:** [`design.md`](design.md), sections 2 (delivery structure), 4 (#334: compiler warnings), 5 (#334: scalafix) and
7 (verification). The design is the source of truth; this plan doesn't reopen its decisions. Where the design's text
predates a decision in PR #337's "Decisions made without the user", the decision wins: agents format with `sbtn fix`,
never the Metals MCP `format-file` tool, and `.scalafmt.conf` has no `project.git`, so `sbtn fix` and `sbtn lint` also
cover files that aren't `git add`ed yet. The user's own decisions after the design also win; "Decisions after the
design" lists them. "Notes on the design" lists what writing this plan found.

## Decisions after the design

The user made these on 2026-09-28, after reviewing this plan's first version. They change the design where it says
otherwise:

1. **scalafix removes only unused imports.** No `RemoveUnused` rule: `OrganizeImports` (`removeUnused = true`) removes
   unused imports, and nothing removes unused methods, values, classes or parameters. The compiler reports those, and
   they're fixed by hand. The pre-measurement had shown `RemoveUnused` deleting live code (note 4).
2. **Unused imports stay warnings locally**, so that `sbtn fix` can remove them: scalafix only runs on code that
   compiles. The default `-Wconf` gets `msg=unused import:w`. `sbtn lint` still fails on an unused import (the
   `OrganizeImports` check), and strict mode makes it an error.
3. **Agents don't ignore warnings.** CLAUDE.md tells them to act on every warning a compile reports, and the Lint step
   ends with the strict check that CI runs, `sbt -Dmicrotonalist.build.strictWarnings=true lint`, which fails on every
   warning but a deprecation.

## Global Constraints

- `project/plugins.sbt`: `addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")`. It loads on sbt 1.10.7
  (pre-measured).
- `build.sbt`: `ThisBuild / semanticdbEnabled := true`.
- Compiler flags, added to `compilerOptions`: `-Wunused:all`, `-no-indent`, `-old-syntax` for main and test code;
  `-Wvalue-discard` and `-Wnonunit-statement` for main code only. Not added: `-Wsafe-init`, `-Wshadow`.
- Warnings policy (finish PR only), selected by the build property `microtonalist.build.strictWarnings`:
  - default: `-Wconf:any:e,cat=deprecation:w,msg=unused explicit parameter:w,msg=unused import:w`
  - strict (`-Dmicrotonalist.build.strictWarnings=true`): `-Wconf:any:e,cat=deprecation:w`
- Within one `-Wconf` option the rightmost matching rule wins, so `any:e` comes first. Any change to these strings is
  re-tested. Deprecations are never errors.
- scalafix rules: `OrganizeImports` (`targetDialect = Scala3`, `removeUnused = true`, `groupedImports = Keep`),
  `DisableSyntax` (`noReturns = true`, plus the regexes `//\s*TODO(?! #\d+)`, `Thread\.sleep` and `AnyFlatSpec`),
  `NoValInForComprehension`, `RedundantSyntax`. No `RemoveUnused`: scalafix removes unused imports and nothing else.
- The CLAUDE.md Lint step (finish PR): `sbtn fix`, then `sbtn lint`, then `sbt -Dmicrotonalist.build.strictWarnings=true
  lint`.
- Aliases in `build.sbt`:
  - tooling PR: `fix` = `scalafixAll`, `experiments/scalafixAll`, then #335's `scalafmtAll`, `scalafmtSbt`,
    `experiments/scalafmtAll`; `lint` unchanged.
  - finish PR: `lint` = `Test/compile`, `experiments/Test/compile`, `scalafixAll --check`,
    `experiments/scalafixAll --check`, then #335's `scalafmtCheckAll`, `scalafmtSbtCheck`,
    `experiments/scalafmtCheckAll`.
- CI's `lint` job (added by #335's finish PR) runs `sbt -Dmicrotonalist.build.strictWarnings=true lint` from #334's
  finish PR on.
- Suppressing: `@nowarn("msg=…")` at the narrowest scope, or `// scalafix:ok <RuleId>` on the line, always with a
  comment giving the reason, and a `// TODO #<issue>` if it's temporary.
- "Avoid `new`" becomes a recommendation, "Prefer omitting `new`", and isn't enforced.
- Scala stays on 3.6.3. No other plugin or dependency changes (#336 does those).
- Branches: `feature/lint` (tooling), `feature/lint-autofix` (bulk), `feature/lint-enforce` (finish). Switch branches in
  place; don't use git worktrees.
- PR titles and commit subjects start with `[#333/#334]`. Every PR is opened as a draft through the `contributing`
  skill's script, with label `feature` and milestone `Agentic Coding`.
- Only the finish PR links #334 for closing. The tooling and bulk PR bodies contain no closing keyword (close, fix,
  resolve or their variants) in front of an issue number, not even negated; GitHub links it anyway.
- The bulk PR contains only the output of `sbtn fix`, with no hand edits.
- `.git-blame-ignore-revs` gets the bulk PR's squashed SHA on `main`, never a branch SHA.
- Run sbt through `sbtn`. Two exceptions use plain `sbt`: coverage (`-Dmicrotonalist.build.targetSuffix=-scoverage`)
  and strict-mode runs (`-Dmicrotonalist.build.strictWarnings=true`), since the long-running server can't take a
  property for one command.
- Never copy a line number from the design or from this plan's measurements into an edit: #338 reformatted the files,
  and `main` keeps moving. Find each site with `grep` or with the compiler's output.
- The pre-commit hook formats staged `.scala` and `.sbt` files. Commit with `git add <files>` then `git commit -m …`,
  never `git commit <paths>`: the hook stops a path commit that it had to reformat.
- Any comment posted on a PR or issue starts with `Claude: `.
- End commit messages with the `Co-Authored-By` and `Claude-Session` trailer lines the harness specifies, and PR bodies
  with its attribution lines.

## Review Focus

1. **A private method that only reflection calls**, like `TrackManager`'s `@Subscribe` handlers, which Guava's
   `EventBus` calls. The compiler reports it unused, and once warnings are errors the build fails on it. Deleting it to
   get a green build would break the app (pre-measured: `RemoveUnused` did exactly that, and the build stayed green).
   Expected: it stays, with an `@nowarn` giving the reason. Tested in Task 3, Steps 2–4 (a mutation check shows
   `TrackManagerTest` fails without it) and Task 11, Step 4 (the bulk diff removes no definition).
2. **An unused value in a test that was meant to be checked** (pre-measured: a `ScaleContextConverterTest` case builds
   a context, then passes `None`). Deleting the value to silence the warning would hide the missing check. Expected:
   the fix adds the check. Tested in Task 3, Steps 5–7.
3. **A red-phase stub with an unused constructor parameter** compiles in default mode with one warning, and fails in
   strict mode, so one left over after green fails the Lint step's strict check and CI. Tested in Task 14, Step 6.
4. **Unused code during the Lint step.** `sbtn fix` removes an unused import, which compiles as a warning. It must not
   remove an unused method, value or class: those are compile errors, so scalafix can't run, and `sbtn fix` fails with
   the compiler's error (file, line, message), leaving the code in place. Tested in Task 14, Step 7.
5. **An instrumented build under the default policy**, as CI's `build` job runs `sbt coverageCheck`: scoverage must add
   no warning, or the job fails once warnings are errors. Pre-measured: the same 37 warnings. Tested in Task 9, Step 1
   and Task 15, Step 6.

## Conventions for this plan

- `$SCRATCH` is the session's scratchpad directory, never `/tmp`. Scratch files that the steps create inside the repo
  are never committed, and each step that creates one deletes it.
- `$REPO` is the repository root, `/Users/calinburloiu/Development/microtonalist`.
- **Stop points** are marked **STOP**. At each one, report to the user in chat (explain; don't just point to a file)
  and wait, unless the run is unattended.
- A command's "Expected" block says what it must print. When a check fails, find out why before going on.
- Shell variables don't survive between tool calls. Redefine `SCRATCH`, `D`, `T`, `SHA` and the like in each command
  that uses them.
- `FIX_FULL='scalafixAll --no-cache; experiments/scalafixAll --no-cache; scalafmtAll; scalafmtSbt; experiments/scalafmtAll'`
  is `sbtn fix` without scalafix's incremental cache. sbt-scalafix skips a file whose content it has already processed,
  even when that run's changes were reverted since (pre-measured: after `git restore`, a second `scalafixAll` changed
  nothing). Every step that must cover all the files runs `sbtn "$FIX_FULL"` instead of `sbtn fix`.

### Unattended run

When the user has said they're away, run Tasks 0–11 without waiting at any STOP point, except the Task 0 gate: make the
decision, record it in the decision log, and continue. The run ends at Task 11, Step 11. Tasks 12–15 need merges only
the user makes, so they never run unattended. Never merge a PR or push to `main`.

The **decision log** is `$SCRATCH/decisions.md`: one numbered entry per decision the user would otherwise have made,
written as soon as it's made (the file survives context compaction). Each entry gives the task and step, what was
decided, the alternatives, why, and how to change it later: which file or setting to edit, and whether Task 12 then
has to regenerate the bulk PR. The log goes into the tooling PR body (Task 10, Step 5, updated in Task 11, Step 11) and
into the final report.

Defaults for the decisions this plan already foresees:

- **Task 0, Step 1** (#338 isn't merged): stop the run. Nothing else can start.
- **Task 1, Step 8** (more hand fixes than the threshold): stop the run and report the counts. Re-scoping is outside
  the plan.
- **Task 3, Step 7** (an unused finding the plan doesn't list): apply Step 7's decision rule.
- **Task 7, Step 1** (TODO issue numbers and the reason for the production sleep): take the proposals in that step,
  including creating the new issue.
- **Task 8, Step 7** (IntelliJ IDEA's "Optimize Imports"): skip the check, and list it in the tooling PR body as an open
  review item.
- **Anything unforeseen**: choose the option most consistent with the design that is easiest to undo, and log it.
  Stop only when every option would be irreversible or reach outside the plan's scope, and then say why in the final
  report.

## Notes on the design

Found while writing this plan, on a scratch copy of #338's bulk commit. None of them changes a decision; each says how
the plan applies the design.

1. **Two `return` statements, not one.** Besides `MergeTuningReducer`, `JsonPreprocessorHttpRefLoader.load` has an early
   `return None`. Both are rewritten with `boundary` (Task 5). `JsonPreprocessorHttpRefLoader` has no test at all, so
   Task 5 adds one first.
2. **`Test / scalacOptions` starts from `Compile / scalacOptions`.** `Compile / scalacOptions ++= …` alone puts the
   main-only flags on tests too (verified with `show common/Test/scalacOptions`), which gave 3835 warnings on
   assertions. The build removes them from `Test` explicitly (Task 1).
3. **`fix` gains scalafix in the tooling PR.** The design lists both alias changes under the finish PR, but the bulk PR
   is defined as the output of `sbtn fix` (sections 2 and 5), so `fix` must run scalafix before the bulk PR exists.
   `lint` gains its checks in the finish PR, as the design says: before the bulk PR merges they would fail CI.
4. **`RemoveUnused` got some findings wrong,** which led to decision 1 in "Decisions after the design". It deleted
   `TrackManager`'s two `@Subscribe` handlers, which only Guava's `EventBus` calls, and a cascade of their helpers and
   imports. For unused test values it kept the right-hand side as a dead statement, which the compiler then reports as
   "A pure expression does nothing in statement position". Without it, the tooling PR fixes every non-import `-Wunused`
   finding by hand (Task 3), and the bulk PR holds only import changes and `RedundantSyntax`.
5. **The import groups.** The design's example `groups = ["*", "re:javax?\\.", "scala."]` puts a blank line between the
   `java` and `scala` imports, which the code never has: it changes 110 files. IntelliJ IDEA's default layout, which the
   code follows, has one blank line, after the other packages. `blankLines = Manual` with a single `"---"` reproduces it
   and changes 75 files (Task 8).
6. **`DisableSyntax.noReturns` has a fixed message** ("return should be avoided, consider using if/else instead"). Only
   the regex checks take a custom message pointing to the conventions; `linting.md` maps `noReturns` to its convention.
7. **The suppression comment names the rule:** `// scalafix:ok DisableSyntax.threadSleep`, verified to suppress only
   that check.
8. **`: Unit` doesn't silence `-Wvalue-discard` on 3.6.3.** `val _ = expr` does, in every form the fixes need (a `case`
   body, a `synchronized` block, a class-body `if`). Task 2 uses it.

## Pre-measured starting point

Measured while writing this plan, with plain `sbt` on a scratch copy of #338's bulk commit (`284d153`), with the flags
and sbt-scalafix added as Tasks 1, 4 and 8 add them:

- sbt-scalafix 0.14.9 loads on sbt 1.10.7, and the rules run on Scala 3.6.3 (the measurement also ran `RemoveUnused`,
  which the plan no longer uses).
- `-no-indent` and `-old-syntax` report nothing: the code has no indentation syntax and no `if … then`.
- An instrumented compile (`sbt coverage clean Test/compile`) reports the same 37 warnings as a plain one.
- `private[pkg]` members aren't checked by `-Wunused`; `@nowarn("msg=unused private member")` silences the compiler;
  `-Wunused:all` reports an `@nowarn` that suppresses nothing.
- The unused-import warning's message is "unused import", which the default `-Wconf` matches.

**Warnings with the new flags: 37.** None in `experiments`.

| Module | Scope | Kind | Count | Fixed in |
| --- | --- | --- | --- | --- |
| `app`, `common-test-utils`, `config`, `sc-midi`, `tuner` | main | discarded non-Unit value (`-Wvalue-discard`) | 14 (1, 2, 1, 3, 7) | Task 2 |
| `composition`, `format`, `tuner` | main | unused import | 6 (1, 3, 2) | bulk PR |
| `tuner` (`TrackManager`) | main | unused private member | 2 | Task 3 |
| `intonation`, `sc-midi`, `tuner` | test | unused import | 5 (1, 1, 3) | bulk PR |
| `tuner` (`MpeTunerTest`) | test | unused private member | 5 | Task 3 |
| `format`, `tuner` | test | unused local definition | 3 (1, 2) | Task 3 |
| `ui`, `tuner` | main, test | deprecation (`TuningService.tunings`) | 2 | stays |

Hand fixes: 14 + 2 + 5 + 3 = 24. No `-Wnonunit-statement` warning in main code, and no "unused explicit parameter".

**`DisableSyntax`: 7 violations**, all fixed by hand:

| Check | Where | Task |
| --- | --- | --- |
| `noReturns` | `MergeTuningReducer.reduceTunings`, `JsonPreprocessorHttpRefLoader.load` | 5 |
| `threadSleep` | `ConcurrentMidiTransmitterTest.awaitCondition`, `MicrotonalistApp`'s shutdown hook | 6 |
| `todoWithoutIssue` | `PlatformUtils.scala`, `TuningMapper.scala`, `TuningReference.scala` | 7 |

The mid-sentence "See the onAttach TODO above" in `TunerProcessor.scala` doesn't match, as the design intends.
`AnyFlatSpec` has no match.

**Autofix diff** (`scalafixAll` and `experiments/scalafixAll`, on the code before any hand fix):

| # | `OrganizeImports` layout | Files | Lines +/− | Kept |
| --- | --- | --- | --- | --- |
| A | the design's example: `groups = ["*", "re:javax?\\.", "scala."]` | 110 | +141/−106 | no: 52 added blank lines between `java` and `scala` imports |
| B | `blankLines = Manual`, `groups = ["*", "---", "re:javax?\\.", "scala."]` | 75 | +91/−106 | yes |
| C | B plus `coalesceToWildcardImportThreshold = 5` | 75 | +93/−107 | no: 4 more wildcard imports, and a bigger diff |

B's diff: imports −77/+72, 2 blank lines, 9 `s"…"` literals without an interpolated value (`RedundantSyntax`), and the
`RemoveUnused` edits from note 4, which the plan no longer makes. `sbtn fix` then reformats 2 of its lines (+89/−106 in
all). With Task 3's `@nowarn` in place, B changes 74 files, +89/−94, of which about 8 lines on each side are still
`RemoveUnused` edits to test values. So the bulk PR should come to about 74 files, +80/−85.

sbt-scalafix is incremental: after `git restore`, running `scalafixAll` again on the same content changed nothing, while
`scalafixAll --no-cache` redid the whole diff. Hence `FIX_FULL` (see "Conventions for this plan").

Import selectors in the code, for the wildcard threshold: 137 imports name 2 members, 48 name 3, 20 name 4, 3 name 5
and 1 names 6, plus a few wrapped over several lines.

## File structure

**Tooling PR (`feature/lint`):**

- Modify `build.sbt`: the flags, `semanticdbEnabled`, and scalafix in `fix`.
- Modify `project/plugins.sbt`: sbt-scalafix.
- Create `.scalafix.conf`: the rules (`DisableSyntax` first, the autofix rules in Task 8).
- Hand fixes, main code: `MicrotonalistApp.scala`, `LogCapture.scala`, `ConfigManagement.scala`,
  `JavaMidiDeviceReferenceCounter.scala`, `JavaMidiManager.scala`, `MpeChannelAllocator.scala`, `MpeTuner.scala`,
  `MtsMessageGenerator.scala`, `TuningService.scala`, `TrackManager.scala`, `MergeTuningReducer.scala`,
  `JsonPreprocessorHttpRefLoader.scala`, `PlatformUtils.scala`, `TuningMapper.scala`, `TuningReference.scala`.
- Hand fixes, tests: `MpeTunerTest.scala`, `MpeChannelAllocatorTest.scala`, `ScaleContextConverterTest.scala`,
  `ConcurrentMidiTransmitterTest.scala`.
- Create `format/src/test/scala/org/calinburloiu/music/microtonalist/format/JsonPreprocessorHttpRefLoaderTest.scala`.
- Create `docs/development/linting.md`.
- Modify `docs/development/coding-conventions.md`, `docs/development/test-conventions.md`, `docs/development/build.md`,
  `docs/development/README.md`, `CONTRIBUTING.md`.
- This plan (already on the branch).

**Bulk PR (`feature/lint-autofix`):** only the `.scala` files that `sbtn fix` changes.

**Finish PR (`feature/lint-enforce`):**

- Modify `.git-blame-ignore-revs`: the bulk PR's squashed SHA.
- Modify `build.sbt`: the `-Wconf` policy, the strict property, and the `lint` alias.
- Modify `.github/workflows/scala.yml`: strict mode in the `lint` job.
- Modify `AGENTS.md` (`CLAUDE.md` is a symlink to it): the red-phase note, the warnings and suppression facts, the Lint
  step.
- Modify `docs/development/linting.md`, `docs/development/build.md`.

---

## Phase 0: Gate

### Task 0: Wait for #338, then move the plan onto `main`

**Files:** none changed.

**Interfaces:**

- Consumes: #337 and #338 merged into `main`; the tooling PR, `<tooling PR>`, opened as a draft with only this plan
  and based on `feature/scalafmt` (find its number with `gh pr view feature/lint --json number`).
- Produces: `feature/lint` holding only the plan commit(s), on top of `main`, and `<tooling PR>` based on `main`; a
  running dev stack on that build.

- [ ] **Step 1: Check that #338 merged**

Call the GitHub MCP `pull_request_read` (method `get`) for #338.
Expected: `merged: true` and base `main`. If not, **STOP**: execution starts only after #338 merges. (It merges after
#337, so `main` then holds both.)

- [ ] **Step 2: Rebase only the plan commit onto `main`**

```bash
git status --short                                   # expected: empty
git fetch origin
git switch main && git merge --ff-only origin/main
PLAN=issues/00333-lint-and-format/plan-334-lint.md
FIRST=$(git log --reverse --format=%H feature/lint -- "$PLAN" | head -1)
FORK=$(git rev-parse "$FIRST^")
git diff --name-only "$FORK" feature/lint            # expected: only $PLAN
git rebase --onto main "$FORK" feature/lint
git log --oneline main..feature/lint                 # expected: only the plan commit(s)
git diff --name-only main feature/lint               # expected: only $PLAN
git push --force-with-lease origin feature/lint
```

Then set `<tooling PR>`'s base to `main` (GitHub MCP `update_pull_request` with `base: "main"`, or
`gh pr edit feature/lint --base main`), unless GitHub already did when `feature/scalafmt` was deleted. Check that its
"Files changed" lists only the plan.

`FORK` is the `feature/scalafmt` commit the branch started from, whatever happened to `feature/scalafmt` since. The
#335 commits below it reached `main` squashed, so they must not be replayed.

- [ ] **Step 3: Restart the dev stack on this build and check the baseline**

Run `bin/microtonalist-dev-stack restart`, then `bin/microtonalist-dev-stack status; echo "exit=$?"` (expected: exit 0),
then call `mcp__metals__list-modules` (see `docs/agents/dev-stack.md` if it doesn't answer), then:

```bash
sbtn lint; echo "exit=$?"                            # expected: exit=0 (#338 formatted everything)
ls .scalafmt.conf .git-blame-ignore-revs 2>&1        # the second exists only if #335's finish PR merged
```

Call `mcp__metals__compile-full`.
Expected: success, with only the two known deprecation warnings (`TuningService.tunings`).

Note whether #335's finish PR (`feature/scalafmt-enforce`) has merged. Phase 1 doesn't depend on it; Phase 3 does.

## Phase 1: Tooling PR (`feature/lint`)

### Task 1: The compiler warning flags, as plain warnings, and the count

**Files:**

- Modify: `build.sbt` (`compilerOptions` and `commonSettings`)
- Scratch: `$SCRATCH/count-warnings.py`, `$SCRATCH/warnings.log`, and three scratch sources in `common`

**Interfaces:**

- Consumes: the baseline from Task 0.
- Produces: `compilerOptions` (now with `-Wunused:all`, `-no-indent`, `-old-syntax`) and `mainOnlyCompilerOptions`
  in `build.sbt`, which Task 14 extends; `$SCRATCH/count-warnings.py`, used again in Tasks 2, 3, 8, 11 and 14.

- [ ] **Step 1: Write the warning counter**

Create `$SCRATCH/count-warnings.py`:

```python
#!/usr/bin/env python3
"""Counts the Scala 3 compiler warnings in an sbt log, by module, scope (main or test) and kind.

Usage: python3 count-warnings.py <sbt log> [--list]
With --list, it also prints every warning: module, scope, kind, file and line.
"""
import collections
import re
import sys

ANSI = re.compile(r'\x1b\[[0-9;]*m')
HEADER = re.compile(r'^\[warn\] -- (?:\[E\d+\] )?.*?Warning: (\S+?):(\d+):\d+')
MESSAGE = re.compile(r'^\[warn\]\s+\|\s*(\S.*)$')


def main():
    lines = [ANSI.sub('', line) for line in open(sys.argv[1], encoding='utf-8').read().splitlines()]
    warnings = []
    for i, line in enumerate(lines):
        header = HEADER.match(line)
        if not header:
            continue
        path, line_number = header.groups()
        kind = '?'
        for following in lines[i + 1:i + 12]:
            if HEADER.match(following):
                break
            message = MESSAGE.match(following)
            if message and not set(message.group(1)) <= set('^ '):
                kind = re.sub(r' of type .*', '', message.group(1))
                break
        relative = re.sub(r'^.*?/(?=[^/]+/src/)', '', path)
        scope = 'test' if '/src/test/' in relative else 'main'
        warnings.append((relative.split('/')[0], scope, kind, relative, int(line_number)))
    for key, count in sorted(collections.Counter(w[:3] for w in warnings).items()):
        print(count, *key, sep='\t')
    print('total', len(warnings), sep='\t')
    if '--list' in sys.argv:
        for warning in sorted(warnings):
            print(*warning, sep='\t')


main()
```

It reads each warning's header line and the first message line after the caret line. It needs a sequential compile
(Step 7), since parallel modules interleave their message lines.

- [ ] **Step 2: Write the scratch violations**

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
T=common/src/test/scala/org/calinburloiu/music/microtonalist/common
cat > "$D/LintScratch.scala" <<'EOF'
package org.calinburloiu.music.microtonalist.common

import scala.collection.mutable

import scala.util.Try

object LintScratch {
  private def unusedPrivate(): Int = 1

  def discarded(map: mutable.Map[Int, Int]): Unit = {
    map.remove(1)
  }
}
EOF
cat > "$D/IndentScratch.scala" <<'EOF'
package org.calinburloiu.music.microtonalist.common

class IndentScratch:
  def abs(x: Int): Int =
    if x > 0 then x else -x
EOF
cat > "$T/LintScratchTest.scala" <<'EOF'
package org.calinburloiu.music.microtonalist.common

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LintScratchTest extends AnyWordSpec with Matchers {
  "an assertion" should {
    "not warn when a later statement follows it" in {
      1 shouldBe 1
      2 shouldBe 2
    }
  }
}
EOF
```

- [ ] **Step 3: Check that nothing reports them yet**

```bash
sbtn "common/Test/compile" > "$SCRATCH/scratch.log" 2>&1; echo "exit=$?"
grep -E 'Scratch' "$SCRATCH/scratch.log"
```

Expected: exit 0, and no line naming a scratch file. (Indentation syntax and `if … then` are valid Scala 3.)

- [ ] **Step 4: Add the flags**

In `build.sbt`, replace `compilerOptions` with:

```scala
lazy val compilerOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-encoding", "utf8",
  "-language:implicitConversions",
  "-language:postfixOps",
  // Used for scalamock: trait Mock is marked as experimental
  "-experimental",
  // Unused imports, private members, local definitions, parameters, and `@nowarn` annotations that suppress nothing
  "-Wunused:all",
  // Brace syntax only: indentation syntax and `if … then` are compile errors. See
  // docs/development/coding-conventions.md#use-brace-syntax.
  "-no-indent",
  "-old-syntax",
)

// Warnings for production code only: ScalaTest's assertions return `Assertion`, so in tests they would fire on nearly
// every line. See docs/development/linting.md.
lazy val mainOnlyCompilerOptions = Seq(
  "-Wvalue-discard",
  "-Wnonunit-statement",
)
```

In `commonSettings`, after `scalacOptions ++= compilerOptions,`, add:

```scala
  Compile / scalacOptions ++= mainOnlyCompilerOptions,
  // `Test / scalacOptions` starts from `Compile / scalacOptions`, so the production-only flags are removed explicitly.
  Test / scalacOptions --= mainOnlyCompilerOptions,
```

- [ ] **Step 5: Restart the dev stack and check the scoping**

Run `bin/microtonalist-dev-stack restart` (Metals reads the compiler options only at import), then:

```bash
sbtn "show common/Compile/scalacOptions; show common/Test/scalacOptions" 2>&1 | grep -E '\* -(W|no-indent|old-syntax)'
```

Expected: the first block lists `-Wunused:all`, `-no-indent`, `-old-syntax`, `-Wvalue-discard` and
`-Wnonunit-statement`; the second lists only the first three.

- [ ] **Step 6: Check that the flags report the scratch violations**

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
T=common/src/test/scala/org/calinburloiu/music/microtonalist/common
sbtn "common/Test/compile" > "$SCRATCH/scratch.log" 2>&1; echo "exit=$?"
grep -E 'Scratch' "$SCRATCH/scratch.log"
rm "$D/IndentScratch.scala"
sbtn "common/Test/compile" > "$SCRATCH/scratch.log" 2>&1; echo "exit=$?"
python3 "$SCRATCH/count-warnings.py" "$SCRATCH/scratch.log" --list | grep Scratch
rm "$D/LintScratch.scala" "$T/LintScratchTest.scala"
git status --short                                  # expected: only build.sbt
```

Expected: the first compile exits non-zero, with errors in `IndentScratch.scala` (indentation syntax and `then` are
rejected). The second exits 0 with three warnings, all in `LintScratch.scala`: an unused import (`Try`; `mutable` is
used), an unused private member, and a discarded non-Unit value. None names `LintScratchTest.scala`: tests don't get
`-Wnonunit-statement`.

- [ ] **Step 7: Count every warning**

```bash
sbtn "set Global / concurrentRestrictions += Tags.limitAll(1)"
sbtn clean
sbtn "Test/compile; experiments/Test/compile" > "$SCRATCH/warnings.log" 2>&1; echo "exit=$?"
sbtn reload                                         # drops the `set`
python3 "$SCRATCH/count-warnings.py" "$SCRATCH/warnings.log"
```

Expected: exit 0, and the counts of "Pre-measured starting point" (37 in all). Small differences are fine when `main`
changed after #338; list them with `--list`.

- [ ] **Step 8: Decide whether the hand fixes fit in this PR**

The hand fixes are the discarded values plus the unused findings that aren't imports (pre-measured: 24). If there are
more than 60, **STOP** and re-scope with the user, as the design requires: give the counts per module and kind. At 60
or fewer, continue.

- [ ] **Step 9: Commit**

```bash
git add build.sbt
git commit -m "[#333/#334] Add the compiler warning flags, as plain warnings"
```

### Task 2: Stop discarding non-Unit values silently

**Files:**

- Modify: `app/.../MicrotonalistApp.scala`, `common-test-utils/.../LogCapture.scala`,
  `config/.../ConfigManagement.scala`, `sc-midi/.../javamidi/JavaMidiDeviceReferenceCounter.scala`,
  `sc-midi/.../javamidi/JavaMidiManager.scala`, `tuner/.../MpeChannelAllocator.scala`, `tuner/.../MpeTuner.scala`, `tuner/.../MtsMessageGenerator.scala`,
  `tuner/.../TuningService.scala` (all under `src/main/scala/org/calinburloiu/music/…`)

**Interfaces:**

- Consumes: the flags (Task 1); `$SCRATCH/count-warnings.py`.
- Produces: no `-Wvalue-discard` warning left.

These are behavior-preserving: each discarded value was already ignored. Existing tests cover the code; the compiler's
warning is the failing check.

- [ ] **Step 1: List the sites**

Run: `python3 "$SCRATCH/count-warnings.py" "$SCRATCH/warnings.log" --list | grep 'discarded non-Unit value'`
Expected: the 14 sites below, by file (line numbers are the compiler's, don't copy them from elsewhere).

- [ ] **Step 2: Assign each discarded value to `_`**

In each site, put `val _ = ` in front of the discarded expression. The enclosing construct stays as it is; a brace-less
`if` branch gets braces.

| File | Method | Before | After |
| --- | --- | --- | --- |
| `LogCapture` | `append` (in the anonymous `AppenderBase`) | `events.add(event)` | `val _ = events.add(event)` |
| `LogCapture` | `awaitSlf4jInitialization` | `LoggerFactory.getILoggerFactory` | `val _ = LoggerFactory.getILoggerFactory` |
| `ConfigManagement` | class body, `if (metaConfig.saveIntervalMillis > 0)` | `scheduledExecutorService.scheduleAtFixedRate(scheduledTask,` | `val _ = scheduledExecutorService.scheduleAtFixedRate(scheduledTask,` |
| `JavaMidiDeviceReferenceCounter` | `acquire` | `referenceCounts.put(javaDevice, referenceCount + 1)` | `val _ = referenceCounts.put(javaDevice, referenceCount + 1)` |
| `JavaMidiDeviceReferenceCounter` | `release`, `case referenceCount =>` | `referenceCounts.put(javaDevice, referenceCount - 1)` | `val _ = referenceCounts.put(javaDevice, referenceCount - 1)` |
| `JavaMidiManager` | `forgettingIfClosed` | `handles.remove(handle.id)` | `val _ = handles.remove(handle.id)` |
| `MpeChannelAllocator` | the `if (deallocated)` after `decrementReferenceCount` | `noteChannels.remove(noteIdentity)` | `val _ = noteChannels.remove(noteIdentity)` |
| `MpeChannelAllocator` | `dropNotesFromAffectedInputChannels` | `dropIdentities(state, affected, nextTime())` | `val _ = dropIdentities(state, affected, nextTime())` |
| `MpeTuner` | the `deselectsOnRelay` line | `if (MpeMessageRouting.deselectsOnRelay(msg)) outputRpnSelectors.remove(channel)` | `if (MpeMessageRouting.deselectsOnRelay(msg)) {` / `val _ = outputRpnSelectors.remove(channel)` / `}` |
| `MtsMessageGenerator` | the 1-byte `put…TuningValue` | `buffer.put(tuningValueByte)` | `val _ = buffer.put(tuningValueByte)` |
| `MtsMessageGenerator` | `put2ByteTuningValue` | `buffer.put(lsb)` | `val _ = buffer.put(lsb)` |
| `TuningService` | `changeTuning` | `case PreviousTuningChange => session.previousTuning()` | `case PreviousTuningChange => val _ = session.previousTuning()` |
| `TuningService` | `changeTuning` | `case NextTuningChange => session.nextTuning()` | `case NextTuningChange => val _ = session.nextTuning()` |

`dropNotesFromAffectedInputChannels`'s ScalaDoc already says its result is discarded on purpose. In
`MtsMessageGenerator`, the `buffer.put(msb)` before `buffer.put(lsb)` isn't reported (a call returning its receiver's
type is exempt), so leave it.

The 14th site is `MicrotonalistApp.main`, whose whole body is a discarded `Try`. Wrap it in a block:

```scala
  def main(args: Array[String]): Unit = {
    val _ = Try {
      logger.info(s"Welcome to Microtonalist ${BuildInfo.version}!")

      args match {
        case Array(inputUrlString: String) =>
          run(parseUrlArg(inputUrlString))
        case Array(inputUrlString: String, configFileName: String) =>
          run(parseUrlArg(inputUrlString), Some(parsePathArg(configFileName)))
        case _ => throw AppUsageException
      }
    }.recover {
      case appException: AppException => appException.exitWithMessage()
      case exception: Exception =>
        logger.error("Unexpected error", exception)
        System.exit(1000)
    }
  }
```

This keeps today's behavior, including one gap: a `Throwable` that is neither an `AppException` nor an `Exception` (e.g.
`NotImplementedError`) leaves a `Failure` that nobody looks at. Mention it in the PR body as a possible follow-up; don't
change it here.

- [ ] **Step 3: Check that no discarded value is left**

Run: `mcp__metals__compile-full`
Expected: success; no "discarded non-Unit value" warning. If a new site appears (code added to `main` after #338), fix
it the same way.

- [ ] **Step 4: Run the tests**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: all pass. The whole suite, since `LogCapture` (in `common-test-utils`) serves the tests of several modules.

- [ ] **Step 5: Commit**

```bash
git add -u
git status --short                                  # expected: nothing left unstaged
git commit -m "[#333/#334] Assign intentionally discarded values to _"
```

### Task 3: Fix the unused private members and values by hand

**Files:**

- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala`
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`,
  `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala`,
  `format/src/test/scala/org/calinburloiu/music/microtonalist/format/ScaleContextConverterTest.scala`

**Interfaces:**

- Consumes: the flags (Task 1).
- Produces: no unused private member or local definition left. scalafix doesn't remove them (decision 1), and they
  become compile errors in the finish PR; the only unused findings left are imports, which the bulk PR removes.

- [ ] **Step 1: List the findings**

Run: `python3 "$SCRATCH/count-warnings.py" "$SCRATCH/warnings.log" --list | grep -E 'unused (private member|local definition)'`
Expected (pre-measured): 2 in `TrackManager.scala`, 5 in `MpeTunerTest.scala`, 2 in `MpeChannelAllocatorTest.scala`,
1 in `ScaleContextConverterTest.scala`.

- [ ] **Step 2: Check that a test fails when a reflective handler is gone (Review Focus 1)**

Find the handlers with
`grep -n -A1 '@Subscribe' tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala`:
`onTuningChanged` and `onMidiEvent`, both private, both called only by Guava's `EventBus`. Temporarily delete the whole
`onTuningChanged` method (with its `@Subscribe`), then:

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.TrackManagerTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL, in the "a tuning event" cases (the tracks don't get the tunings).

Restore the file with `git restore tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala`,
and do the same with `onMidiEvent`: expected FAIL in the "a MIDI device event" cases. Restore it again. Deleting a
handler is the tempting way to silence its warning; this shows the tests catch it.

- [ ] **Step 3: Suppress the warning on the reflective handlers (Review Focus 1)**

Give each handler the annotation and a comment, right after `@Subscribe`:

```scala
  @Subscribe
  // Guava's EventBus calls it through @Subscribe, which the compiler can't see. Goes away with @Subscribe (#90).
  @nowarn("msg=unused private member")
  private def onTuningChanged(event: TuningEvent): Unit = {
```

```scala
  @Subscribe
  // Guava's EventBus calls it through @Subscribe, which the compiler can't see. Goes away with @Subscribe (#90).
  @nowarn("msg=unused private member")
  private def onMidiEvent(event: MidiEvent): Unit = event match {
```

Add `import scala.annotation.nowarn` to the `scala` imports, before `import scala.collection.immutable.VectorMap`.
Both handlers already sit under a `// TODO #90 Remove @Subscribe …` comment, which covers the suppression's removal.

- [ ] **Step 4: Check that the suppression works**

Run: `mcp__metals__compile-full`
Expected: no warning in `TrackManager.scala`, including no "@nowarn annotation does not suppress any warnings".

- [ ] **Step 5: Fix `ScaleContextConverterTest` (Review Focus 2)**

Find the case: `grep -n 'context has no name and intonation standard' format/src/test/scala/org/calinburloiu/music/microtonalist/format/ScaleContextConverterTest.scala`.
It builds a context with neither a name nor an intonation standard, then converts with `None`, so it only repeats the
"no context" case. Pass the context it built:

```scala
    "not do any conversion and return the same scale if the context has no name and intonation standard" in {
      // Given
      val context = Some(ScaleFormatContext(None, None))
      // When
      val result = scaleContextConverter.convert(maj4RatiosScale, context)
      // Then
      result shouldBe theSameInstanceAs(maj4RatiosScale)
    }
```

Run: `sbtn "format/testOnly org.calinburloiu.music.microtonalist.format.ScaleContextConverterTest -- -oNCXEHLOPQRMWS"`
Expected: PASS. `ScaleContextConverter.convert` returns the scale itself for `Some(ScaleFormatContext(None, None))`,
a branch no test covered until now. If it fails, **STOP**: either the production code or the expectation is wrong, and
that's the user's call.

- [ ] **Step 6: Fix `MpeTunerTest` and `MpeChannelAllocatorTest`**

The decision rule (Step 7) gives these fixes. Find each case by name with `grep -n`.

In `MpeTunerTest`, both cases named "not update released channel's pitch bend on tuning changes" (non-MPE and MPE
input) compute `releasedChannel` and never check it. The name promises that check, so add it as the last `Then` line:

```scala
        pitchBends.map(_.channel) should not contain releasedChannel
```

In `MpeTunerTest`, both cases named "preserve Note Off velocity" compute `noteOnChannel`, which the case doesn't need.
Replace their two `Given` lines

```scala
      private val noteOnOutput = noteOn(nonMpeInputChannel, C4, 100)
      private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
```

with the call alone (`mpeInputChannel` in the MPE case):

```scala
      noteOn(nonMpeInputChannel, C4, 100)
```

In `MpeTunerTest`, the case "ignore Polyphonic Key Pressure for non-active notes" computes `noteChannel`, which it
doesn't need. Replace its two `Given` lines

```scala
      private val noteOutput = noteOn(nonMpeInputChannel, C4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
```

with:

```scala
      noteOn(nonMpeInputChannel, C4)
```

In `MpeChannelAllocatorTest`, the case "prefer unoccupied channel with oldest last Note Off" defines `ch2`, which it
doesn't use: delete the line `val ch2 = r2.channel`. The case "prefer channel with lowest active note count when
sharing" defines `r3`, which it doesn't use, but the call has an effect: replace `val r3 = alloc.allocateNote(C3)` with
`alloc.allocateNote(C3)`.

- [ ] **Step 7: Recompile and apply the decision rule to anything left**

Run: `mcp__metals__compile-full`
Expected: no "unused private member" or "unused local definition" warning. Removing a value can make the one it was
computed from unused; fix that too.

For any unused finding not listed above (code added to `main` after #338), decide with this rule, and log it in an
unattended run:

- Something called only through reflection or by a framework: `@nowarn("msg=…")` with a comment, as in Step 3, after
  a mutation check like Step 2's when a test covers it.
- A test value that the case's name says should be checked: add the check.
- Any other value: delete it, keeping the call when it has an effect.
- A finding that points to a production bug: **STOP** and report.

- [ ] **Step 8: Run the tests**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all pass. If a new `releasedChannel` check fails, the two notes shared a channel, so the case never tested
what its name says: **STOP** and report.

- [ ] **Step 9: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala \
  tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala \
  tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala \
  format/src/test/scala/org/calinburloiu/music/microtonalist/format/ScaleContextConverterTest.scala
git commit -m "[#333/#334] Fix the unused private members and values by hand"
```

### Task 4: sbt-scalafix and the `DisableSyntax` checks

**Files:**

- Modify: `project/plugins.sbt`, `build.sbt` (`ThisBuild` settings)
- Create: `.scalafix.conf` (no license header: the `.conf` extension isn't checked, like `.scalafmt.conf`)

**Interfaces:**

- Consumes: nothing from earlier tasks.
- Produces: `.scalafix.conf` with `DisableSyntax`, which Tasks 5–7 make pass and Task 8 extends with the autofix rules;
  the regex ids `todoWithoutIssue`, `threadSleep`, `anyFlatSpec`, used in suppression comments and docs.

- [ ] **Step 1: Check that scalafix isn't there yet**

Run: `sbtn "scalafixAll DisableSyntax"; echo "exit=$?"`
Expected: an error that `scalafixAll` isn't a valid key or command, and a non-zero exit.

- [ ] **Step 2: Add the plugin and SemanticDB**

Append to `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")
```

In `build.sbt`, after `ThisBuild / organization := …`, add:

```scala
// SemanticDB, which scalafix's semantic rules read. Metals enables it too, but plain sbt (CI) needs it set.
ThisBuild / semanticdbEnabled := true
```

- [ ] **Step 3: Create `.scalafix.conf` with `DisableSyntax`**

```hocon
# scalafix configuration: the rules that `sbtn fix` applies and `sbtn lint` checks. See docs/development/linting.md.
rules = [
  DisableSyntax,
]

DisableSyntax.noReturns = true
DisableSyntax.regex = [
  {
    id = todoWithoutIssue
    pattern = "//\\s*TODO(?! #\\d+)"
    message = "A TODO needs an issue number: // TODO #<issue>. See docs/development/coding-conventions.md#todos-have-issue-numbers"
  }
  {
    id = threadSleep
    pattern = "Thread\\.sleep"
    message = "Don't sleep: synchronize with a CountDownLatch or a CompletableFuture. See docs/development/test-conventions.md#no-sleeping-in-tests"
  }
  {
    id = anyFlatSpec
    pattern = "AnyFlatSpec"
    message = "Tests extend AnyWordSpec, not AnyFlatSpec. See docs/development/test-conventions.md#behavior-driven-style"
  }
]
```

- [ ] **Step 4: Restart the dev stack and run the checks**

Run `bin/microtonalist-dev-stack restart`, then:

```bash
sbtn "scalafixAll DisableSyntax; experiments/scalafixAll DisableSyntax" 2>&1 | grep -E 'error\].*\[DisableSyntax' ; echo done
```

Expected: exactly the 7 violations of "Pre-measured starting point": `[DisableSyntax.return]` in
`MergeTuningReducer.scala` and `JsonPreprocessorHttpRefLoader.scala`, `[DisableSyntax.threadSleep]` in
`ConcurrentMidiTransmitterTest.scala` and `MicrotonalistApp.scala`, `[DisableSyntax.todoWithoutIssue]` in
`PlatformUtils.scala`, `TuningMapper.scala` and `TuningReference.scala`. None in `TunerProcessor.scala`.

- [ ] **Step 5: Check the regexes on edge cases**

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
cat > "$D/RegexScratch.scala" <<'EOF'
package org.calinburloiu.music.microtonalist.common

// TODO #123 Has an issue number, so it passes.
//TODO without a space, and without an issue number
object RegexScratch {
  // See the onAttach TODO above, mid-sentence, so it passes.
  def f(): Unit = Thread.sleep(1) // TODO(#12) the wrong issue format
}
EOF
sbtn "common/scalafix DisableSyntax" 2>&1 | grep -E 'RegexScratch.scala:[0-9]+:[0-9]+: error'
rm "$D/RegexScratch.scala"
```

Expected: three errors, on lines 4 (`todoWithoutIssue`), 7 (`threadSleep`) and 7 (`todoWithoutIssue`); none on lines
3 and 6.

- [ ] **Step 6: Commit**

`sbtn lint` still passes: it doesn't run scalafix until the finish PR.

```bash
git add project/plugins.sbt build.sbt .scalafix.conf
git commit -m "[#333/#334] Add sbt-scalafix with the DisableSyntax checks"
```

### Task 5: Replace the two `return` statements with `boundary`

**Files:**

- Create: `format/src/test/scala/org/calinburloiu/music/microtonalist/format/JsonPreprocessorHttpRefLoaderTest.scala`
- Modify: `format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonPreprocessorHttpRefLoader.scala`,
  `composition/src/main/scala/org/calinburloiu/music/microtonalist/composition/MergeTuningReducer.scala`

**Interfaces:**

- Consumes: `DisableSyntax.noReturns` (Task 4).
- Produces: no `return` in the code.

Both rewrites are refactors under the convention "No `return`". `MergeTuningReducerTest`'s "return an empty tuning list
with no tunings" covers `MergeTuningReducer`'s early return; nothing covers `JsonPreprocessorHttpRefLoader`, so a
characterization test comes first.

- [ ] **Step 1: Write the characterization test**

Create `JsonPreprocessorHttpRefLoaderTest.scala`:

```scala
package org.calinburloiu.music.microtonalist.format

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.JsPath

import java.net.URI
import java.net.http.HttpClient

class JsonPreprocessorHttpRefLoaderTest extends AnyWordSpec with Matchers {

  // Sends no request: these cases only use URIs that the loader leaves to another loader.
  private val loader: JsonPreprocessorHttpRefLoader = JsonPreprocessorHttpRefLoader(HttpClient.newHttpClient())

  "load" should {
    "leave a file URI to another loader" in {
      // When / Then
      loader.load(URI("file:///Users/john/Music/scale.json"), JsPath) shouldBe None
    }

    "leave a relative URI to another loader" in {
      // When / Then
      loader.load(URI("scales/scale.json"), JsPath) shouldBe None
    }
  }
}
```

- [ ] **Step 2: Run it, and check that it detects a missing early return**

Run: `sbtn "format/testOnly org.calinburloiu.music.microtonalist.format.JsonPreprocessorHttpRefLoaderTest -- -oNCXEHLOPQRMWS"`
Expected: PASS (it pins today's behavior).

Temporarily delete the `if (!(uri.isAbsolute && …)) { return None }` block and run it again.
Expected: FAIL (the loader tries to send the request and throws). Restore the block.

- [ ] **Step 3: Rewrite `JsonPreprocessorHttpRefLoader.load` with `boundary`**

```scala
  override def load(uri: URI, pathContext: JsPath): Option[JsObject] = boundary {
    if (!(uri.isAbsolute && UriScheme.HttpSet.contains(uri.getScheme))) {
      boundary.break(None)
    }
```

The rest of the method stays as it is. Add `import scala.util.boundary` after
`import java.net.http.{HttpClient, HttpRequest}` (no blank line: `java` and `scala` imports form one group).

- [ ] **Step 4: Rewrite `MergeTuningReducer.reduceTunings` with `boundary`**

```scala
  override def reduceTunings(tunings: Seq[Tuning],
                             globalFillTuning: Tuning = Tuning.Standard): TuningList = boundary {
    if (tunings.isEmpty) {
      boundary.break(TuningList(Seq.empty))
    }
```

The rest of the method stays as it is. Add `import scala.util.boundary` after `import scala.annotation.tailrec`.

- [ ] **Step 5: Run the tests and the check**

```bash
sbtn "format/testOnly org.calinburloiu.music.microtonalist.format.JsonPreprocessorHttpRefLoaderTest -- -oNCXEHLOPQRMWS"
sbtn "composition/testOnly org.calinburloiu.music.microtonalist.composition.MergeTuningReducerTest -- -oNCXEHLOPQRMWS"
sbtn "format/scalafix DisableSyntax; composition/scalafix DisableSyntax"; echo "exit=$?"
```

Expected: both tests pass; the scalafix run reports no `[DisableSyntax.return]` and exits 0.

- [ ] **Step 6: Commit**

```bash
git add format/src/test/scala/org/calinburloiu/music/microtonalist/format/JsonPreprocessorHttpRefLoaderTest.scala \
  format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonPreprocessorHttpRefLoader.scala \
  composition/src/main/scala/org/calinburloiu/music/microtonalist/composition/MergeTuningReducer.scala
git commit -m "[#333/#334] Replace the return statements with boundary"
```

### Task 6: The two `Thread.sleep` calls

**Files:**

- Modify: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitterTest.scala`
- Modify: `app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala`

**Interfaces:**

- Consumes: `DisableSyntax`'s `threadSleep` (Task 4).
- Produces: no unsuppressed `Thread.sleep`; the `// scalafix:ok DisableSyntax.threadSleep` form that `linting.md`
  documents.

The test polls `lock.hasQueuedThreads` with `Thread.sleep(1)` to prove that a read waits for the write lock. The rewrite
follows the test conventions' pattern for "something cannot happen": the read must not complete while the write lock is
held, which a short, named timeout shows. A transmitter whose reads skip the lock fails at once, since the read
completes straight away; only the passing case waits the whole timeout. `MidiProcessorTest`'s
`LockProbingMidiProcessor` already probes the lock this way.

- [ ] **Step 1: Replace the test case**

Find it: `grep -n 'block a read while another thread holds the write lock' sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitterTest.scala`.
Replace the whole case with:

```scala
    "block a read while another thread holds the write lock" in new ProbeFixture {
      // Given
      probe.addReceiver(receiver1)
      val readerStarted: CountDownLatch = CountDownLatch(1)
      val readDone: CountDownLatch = CountDownLatch(1)
      val readReceivers: AtomicReference[Seq[MidiReceiver]] = AtomicReference(Seq.empty)
      val reader: Thread = Thread(() => {
        readerStarted.countDown()
        readReceivers.set(probe.receivers)
        readDone.countDown()
      })
      reader.setDaemon(true)

      // When
      // A read that skipped the read lock would complete at once, and fail the test as soon as it does. A read that
      // takes it waits for the write lock, so only this passing case waits the whole BlockedReadWaitMillis.
      val (readerStartedInTime, readCompletedUnderWriteLock) = probe.holdingWriteLock {
        reader.start()
        val started = readerStarted.await(AwaitTimeoutMillis, TimeUnit.MILLISECONDS)
        (started, readDone.await(BlockedReadWaitMillis, TimeUnit.MILLISECONDS))
      }

      // Then
      readerStartedInTime shouldBe true
      readCompletedUnderWriteLock shouldBe false
      readDone.await(AwaitTimeoutMillis, TimeUnit.MILLISECONDS) shouldBe true
      readReceivers.get() shouldEqual Seq(receiver1)
    }
```

At class level, replace the `awaitCondition` method (and its ScalaDoc) with the two constants:

```scala
  /** How long a test waits for something that should happen at once; only a broken transmitter ever reaches it. */
  private val AwaitTimeoutMillis: Long = 30000L

  /**
   * How long a test waits for a read that the write lock must block. Only a transmitter whose reads skip the lock
   * completes the read within it; a correct one pays it once, so it is kept short.
   */
  private val BlockedReadWaitMillis: Long = 100L
```

Delete `WriteLockProbingTransmitter.lockHasQueuedThreads`, which nothing uses any more.

- [ ] **Step 2: Run it**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ConcurrentMidiTransmitterTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 3: Check that it fails at once when reads skip the lock**

In `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitter.scala`, temporarily change
`override def receivers: Seq[MidiReceiver] = withReadLock {` / `super.receivers` / `}` to
`override def receivers: Seq[MidiReceiver] = super.receivers`, and run Step 2's command.
Expected: FAIL in "block a read while another thread holds the write lock", with
`readCompletedUnderWriteLock` true, well under a second after the case starts. Restore the file:
`git restore sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitter.scala`.

- [ ] **Step 4: Suppress the production sleep, with its reason**

`MicrotonalistApp`'s shutdown hook sleeps a second after closing the modules. Find it:
`grep -n 'Thread.sleep' app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala`.
Change it to (the reason is settled in Task 7, Step 1; this is the proposal):

```scala
        // Gives the MIDI devices time to send the messages queued while closing, before the JVM halts.
        Thread.sleep(1_000) // scalafix:ok DisableSyntax.threadSleep
```

- [ ] **Step 5: Run the checks**

```bash
sbtn "sc-midi/scalafixAll DisableSyntax; app/scalafix DisableSyntax"; echo "exit=$?"
```

Expected: no `[DisableSyntax.threadSleep]`, exit 0. Then remove only the `// scalafix:ok …` comment, run
`sbtn "app/scalafix DisableSyntax"` again (expected: the `threadSleep` error on that line), and put it back.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitterTest.scala \
  app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala
git commit -m "[#333/#334] Replace the sleep in a test with a latch, and justify the one in the shutdown hook"
```

### Task 7: TODOs without an issue number

**Files:**

- Modify: `common/src/main/scala/org/calinburloiu/music/microtonalist/common/PlatformUtils.scala`,
  `composition/src/main/scala/org/calinburloiu/music/microtonalist/composition/TuningMapper.scala`,
  `composition/src/main/scala/org/calinburloiu/music/microtonalist/composition/TuningReference.scala`,
  and `MicrotonalistApp.scala` if the user gives another reason for the sleep

**Interfaces:**

- Consumes: `DisableSyntax`'s `todoWithoutIssue` (Task 4).
- Produces: `DisableSyntax` passes on the whole code.

- [ ] **Step 1: STOP: decide each TODO with the user, one by one**

The design leaves each of them to the user: an issue number, or removal. Present them in chat with these proposals, and
the sleep's reason from Task 6, Step 4:

| File | TODO | Proposal |
| --- | --- | --- |
| `PlatformUtils.scala` | `// TODO Add support for Windows and maybe GNU/Linux` | `#149` ("Windows support", open) |
| `TuningMapper.scala` | `// TODO Wouldn't a more functional approach than an exception be more appropriate? Or encode the conflicts inside?` | `#36` ("Refactor `TuningMapper` and `TuningReducer` to not use exceptions", open) |
| `TuningReference.scala` | `// TODO Add support for Scala-app-style implementation; also look at Ableton Live 12 (…)` (two lines) | a new issue: no open issue matches |
| `MicrotonalistApp.scala` | the reason for `Thread.sleep(1_000)` in the shutdown hook | "Gives the MIDI devices time to send the messages queued while closing, before the JVM halts." `git log -S` doesn't say why; the sleep dates from the sbt migration (#26). |

**Unattended run:** take the proposals. Create the new issue with the `contributing` skill's script (label `feature`,
title "Support a Scala-app-style TuningReference", body quoting the TODO with its Ableton Live 12 link), then log each
choice.

- [ ] **Step 2: Apply the decisions**

Each TODO keeps its text after the issue number, e.g. `// TODO #149 Add support for Windows and maybe GNU/Linux`. A
TODO that the user removes is deleted with its continuation line.

- [ ] **Step 3: Check that `DisableSyntax` passes everywhere**

Run: `sbtn "scalafixAll DisableSyntax; experiments/scalafixAll DisableSyntax"; echo "exit=$?"`
Expected: no error, exit 0.

- [ ] **Step 4: Commit**

```bash
git add -u
git status --short                                  # expected: nothing left unstaged
git commit -m "[#333/#334] Give every TODO an issue number"
```

### Task 8: The autofix rules, the import layout, and scalafix in `fix`

**Files:**

- Modify: `.scalafix.conf`, `build.sbt` (the `fix` alias and its comment)
- Scratch: `$SCRATCH/scalafix-tuning.md`

**Interfaces:**

- Consumes: `.scalafix.conf` (Task 4); the hand fixes (Tasks 2–7).
- Produces: the final `.scalafix.conf`; `fix` running scalafix, which the bulk PR (Task 11) is the output of.

- [ ] **Step 1: Add the autofix rules**

Replace `rules` in `.scalafix.conf`, and add the rules' settings after it:

```hocon
# scalafix removes unused imports only. The compiler reports any other unused code, which is fixed by hand, so there's
# no RemoveUnused rule: it would delete a method that only reflection calls, like a Guava @Subscribe handler.
rules = [
  OrganizeImports,
  DisableSyntax,
  NoValInForComprehension,
  RedundantSyntax,
]

# The import layout of IntelliJ IDEA's defaults, which the code follows: the other packages, one blank line, then the
# java, javax and scala imports, with no blank line between them.
OrganizeImports {
  targetDialect = Scala3
  removeUnused = true
  groupedImports = Keep
  blankLines = Manual
  groups = [
    "*",
    "---",
    "re:javax?\\.",
    "scala.",
  ]
}
```

- [ ] **Step 2: Add scalafix to `fix`**

In `build.sbt`, replace the formatting aliases' comment and `fix`:

```scala
// Code formatting and linting: `fix` applies the scalafix autofixes, then formats the sources with scalafmt; `lint`
// checks the formatting without changing anything. `root` doesn't aggregate `experiments`, so both name it explicitly.
// See docs/development/build.md#formatting and docs/development/linting.md.
addCommandAlias("fix", "scalafixAll; experiments/scalafixAll; scalafmtAll; scalafmtSbt; experiments/scalafmtAll")
```

`lint` stays as it is until the finish PR. Commit both files now, so that the resets below can't revert them:

```bash
git add .scalafix.conf build.sbt
git commit -m "[#333/#334] Apply the scalafix autofixes in sbtn fix"
sbtn reload
```

- [ ] **Step 3: Measure the autofix diff**

```bash
FIX_FULL='scalafixAll --no-cache; experiments/scalafixAll --no-cache; scalafmtAll; scalafmtSbt; experiments/scalafmtAll'
sbtn "$FIX_FULL"; echo "exit=$?"
git diff --shortstat -- '*.scala' '*.sbt'
git diff -U0 -- '*.scala' | grep -E '^-[^-]' | grep -vE '^-\s*(import\b|[A-Z][A-Za-z0-9_]*(, *[A-Z][A-Za-z0-9_]*)*,?$|\}$|$)' | grep -v 's"'
echo "blank lines added: $(git diff -U0 -- '*.scala' | grep -cE '^\+\s*$')"
```

Expected: exit 0; about 74 files, +80/−85 (see "Pre-measured starting point"); the third command prints nothing, so
every removed line is an import (or one of a wrapped import's selector lines) or a redundant `s` interpolator; about
2 blank lines added. Start `$SCRATCH/scalafix-tuning.md` with the table from "Pre-measured starting point" and this row.

- [ ] **Step 4: Check that the result compiles cleanly**

Call `mcp__metals__compile-full`.
Expected: success, with only the two deprecation warnings: `OrganizeImports` removed the 11 unused imports.

- [ ] **Step 5: Tune only if the numbers are off**

If the diff is much bigger than expected, group it by kind (as in the pre-measurement), and try one `OrganizeImports`
setting at a time, resetting between runs with `git restore -- '*.scala' '*.sbt'` and measuring with `sbtn "$FIX_FULL"`:
`coalesceToWildcardImportThreshold` (unset, 5), `importSelectorsOrder` (`Ascii`, `Keep`), `importsOrder` (`Ascii`,
`Keep`). Keep a change only if it shrinks the diff; log each run. The documentation of the settings is at
<https://scalacenter.github.io/scalafix/docs/rules/OrganizeImports.html>.

- [ ] **Step 6: Pick the samples for the IntelliJ IDEA check**

Leave the `sbtn fix` output in the working tree. Pick three changed files: one with both `java` and `scala` imports
(e.g. `MicrotonalistApp.scala`), one test whose imports were reordered (e.g. `TuningSeqMappingIntegrationTest.scala`),
and one with an import of 5 or more members (`git grep -nE 'import .*\{([^,}]*,){4,}' -- '*.scala' | head -3`).

- [ ] **Step 7: STOP: the user checks IntelliJ IDEA's "Optimize Imports"**

The design requires that IntelliJ IDEA's "Optimize Imports" and `OrganizeImports` never undo each other, and only
IntelliJ IDEA can show it. Explain in chat what `OrganizeImports` does (the layout, the counts, one example hunk), then
ask the user to open the three files in IntelliJ IDEA with its default Scala settings, run **Code → Optimize Imports**
on each, and run `git diff --stat` on them:

- No change: the two agree. Continue.
- A change: tune `.scalafix.conf` to match (Step 5's settings), or, if that can't match, document in `linting.md`
  "Run `sbtn fix` instead of IntelliJ IDEA's Optimize Imports" (the design's fallback). Undo IntelliJ's edits with
  `git restore` before measuring again.

**Unattended run:** skip this step and list it in the tooling PR body under "Open review items".

- [ ] **Step 8: Reset the output and commit any tuning**

```bash
git restore -- '*.scala' '*.sbt'
git status --short                                  # expected: empty, or only .scalafix.conf
```

If Step 5 or 7 changed `.scalafix.conf`, commit it: `git add .scalafix.conf` and
`git commit -m "[#333/#334] Tune OrganizeImports to IntelliJ IDEA's import layout"`.

### Task 9: Coverage builds, and the docs

**Files:**

- Create: `docs/development/linting.md`
- Modify: `docs/development/coding-conventions.md`, `docs/development/test-conventions.md`, `docs/development/build.md`,
  `docs/development/README.md`, `CONTRIBUTING.md`

**Interfaces:**

- Consumes: everything from Tasks 1–8.
- Produces: `linting.md`, which Task 15 extends with the warnings policy; the "Enforced by" notes; the `build.md`
  "Linting" section, which Task 15 extends.

- [ ] **Step 1: Check that coverage instrumentation adds no warning (Review Focus 5)**

```bash
sbt -Dmicrotonalist.build.targetSuffix=-scoverage "set Global / concurrentRestrictions += Tags.limitAll(1)" \
  coverage clean "Test/compile" > "$SCRATCH/warnings-coverage.log" 2>&1; echo "exit=$?"
python3 "$SCRATCH/count-warnings.py" "$SCRATCH/warnings-coverage.log"
```

Expected: exit 0, and 13 warnings: the 11 unused imports that the bulk PR removes and the 2 deprecations, the same as
`sbtn` reports now. Any other warning would become an error in CI's `build` job in the finish PR: **STOP** and report
it, with the design's fallback (a narrow `-Wconf` rule for instrumented builds).

- [ ] **Step 2: Write `docs/development/linting.md`**

````markdown
# Linting

How the build checks the code beyond formatting: compiler warning flags and
[scalafix](https://scalacenter.github.io/scalafix/) rules. Formatting is in [`build.md`](build.md#formatting). The
conventions that these checks enforce are in [`coding-conventions.md`](coding-conventions.md) and
[`test-conventions.md`](test-conventions.md), where each one says what enforces it.

## Commands

- `sbtn fix` applies the scalafix autofixes, then formats the code with scalafmt.
- `sbtn lint` checks the formatting.

Both are command aliases in `build.sbt`. Besides the modules that `root` aggregates, they cover the `experiments`
module, which `root` doesn't aggregate.

scalafix is incremental: it skips a file whose content it has already processed, even when that run's changes were
reverted since. To run it on every file, add `--no-cache`:

```bash
sbtn "scalafixAll --no-cache; experiments/scalafixAll --no-cache"
```

## Compiler warnings

The flags are in `compilerOptions` and `mainOnlyCompilerOptions` in `build.sbt`:

| Flag | Code | What it reports |
| --- | --- | --- |
| `-Wunused:all` | main and test | Unused imports, private members, local definitions and parameters, and `@nowarn` annotations that suppress nothing |
| `-no-indent`, `-old-syntax` | main and test | Compile errors, not warnings: indentation syntax and `if … then`, for the brace syntax convention |
| `-Wvalue-discard` | main | A non-`Unit` value discarded where `Unit` is expected, e.g. `map.remove(key)` as the last statement of a `Unit` method |
| `-Wnonunit-statement` | main | A non-`Unit` expression used as a statement |
| `-deprecation`, `-feature`, `-unchecked` | main and test | Deprecated APIs, language features that need an import, unchecked type patterns |

Tests don't get `-Wvalue-discard` and `-Wnonunit-statement`: ScalaTest's assertions return `Assertion`, so they would
fire on nearly every line. `Test / scalacOptions` starts from `Compile / scalacOptions`, so `build.sbt` removes them
from it explicitly.

Not used, for now: `-Wsafe-init` (slower compiles, and no convention needs it) and `-Wshadow`.

When a value is discarded on purpose, assign it to `_`:

```scala
if (handle.state == State.Closed) {
  val _ = handles.remove(handle.id)
}
```

On Scala 3.6.3, ascribing `: Unit` to the expression doesn't silence `-Wvalue-discard`.

## scalafix rules

The rules are in `.scalafix.conf`:

| Rule | Kind | What it does |
| --- | --- | --- |
| `OrganizeImports` | autofix | Removes the unused imports and orders the rest in IntelliJ IDEA's default layout: the other packages, one blank line, then `java`, `javax` and `scala` |
| `RedundantSyntax` | autofix | Removes redundant syntax, e.g. the `s` of an `s"…"` literal without an interpolated value |
| `NoValInForComprehension` | autofix | Removes `val` from the definitions in a `for` comprehension |
| `DisableSyntax` | check | Reports `return` (`noReturns`), and the regular expressions below |

| `DisableSyntax` id | Pattern | Convention |
| --- | --- | --- |
| `todoWithoutIssue` | `//\s*TODO(?! #\d+)` | [TODOs have issue numbers](coding-conventions.md#todos-have-issue-numbers). A mid-sentence "the onAttach TODO above" doesn't match. |
| `threadSleep` | `Thread\.sleep` | [No sleeping in tests](test-conventions.md#no-sleeping-in-tests). It checks production code too. |
| `anyFlatSpec` | `AnyFlatSpec` | [Behavior-driven style](test-conventions.md#behavior-driven-style) |

`noReturns` enforces [No `return`](coding-conventions.md#no-return). Its message is fixed by scalafix ("return should be
avoided, consider using if/else instead"): the convention shows how to use `boundary` instead.

The autofixes need SemanticDB, so scalafix compiles the code first (`ThisBuild / semanticdbEnabled := true`).

### Unused code

scalafix removes unused imports and nothing else. There's deliberately no `RemoveUnused` rule: the compiler reports an
unused method, value, class or parameter, and you decide what to do with it. Delete it when it's dead. When a test value
is unused, check first whether the case forgot to check it: then add the check.

The compiler can't see a call made through reflection, so it reports a private method that only reflection calls as
unused. Guava's `EventBus` calls the `@Subscribe` methods this way. Don't delete such a method: suppress the warning,
as `TrackManager` does.

## Suppressing a finding

Fix the code when you can. Otherwise:

- **A compiler warning:** `@nowarn("msg=<message regex>")` on the narrowest definition that contains it.
  `-Wunused:all` reports an `@nowarn` that no longer suppresses anything.
- **A scalafix finding:** `// scalafix:ok <RuleId>` at the end of the line, e.g.
  `Thread.sleep(1_000) // scalafix:ok DisableSyntax.threadSleep`.

Either way, add a comment giving the reason, and a `// TODO #<issue>` if the suppression is temporary.

## Adding a rule

1. Add a compiler flag to `compilerOptions`, or a rule to `.scalafix.conf` (the built-in ones are listed at
   <https://scalacenter.github.io/scalafix/docs/rules/overview.html>).
2. Check it on a scratch file with a violation, then count the violations in the code: compile, or run
   `sbtn "scalafixAll <Rule>"`.
3. Fix them. An autofix's output across the code goes in a PR of its own, whose squashed commit is then added to
   `.git-blame-ignore-revs`.
4. Document the rule here, and in the convention it enforces.
````

- [ ] **Step 3: Add the "Enforced by" notes to `coding-conventions.md`**

After the intro paragraph, add: "Each convention says whether a tool enforces it. See [`linting.md`](linting.md)."

Then add one line at the end of each section:

| Section | Line to add |
| --- | --- |
| General formatting | `Enforced by scalafmt, except the ScalaDoc rule, which isn't enforced.` |
| Use brace syntax | ``Enforced by the compiler flags `-no-indent` and `-old-syntax`, which make indentation syntax and `if … then` compile errors, and by scalafmt, which never removes braces.`` |
| Use `enum` | `Not enforced.` |
| Avoid `case class` for mutable data structures | `Not enforced.` |
| TODOs have issue numbers | ``Enforced by scalafix (`DisableSyntax`, `todoWithoutIssue`).`` |
| Prefer for-comprehensions for nested monads | `Not enforced.` |
| No `return` | ``Enforced by scalafix (`DisableSyntax.noReturns`).`` |
| Class private internal backing variables for public getter / setter | `Not enforced.` |

Rename "## Avoid `new` when instantiating a class" to "## Prefer omitting `new` when instantiating a class", replace
its "Wrong:" and "Correct:" labels with "Avoid:" and "Prefer:", and add: "This is a recommendation, not enforced: the
code still has many `new X(...)` calls."

Add a section before "## No `return`":

````markdown
## No unused code

Remove unused imports, private members, local definitions and parameters. In production code, don't discard a
non-`Unit` value silently either: when discarding it is intended, assign it to `_`.

```scala
if (handle.state == State.Closed) {
  val _ = handles.remove(handle.id)
}
```

Enforced by the compiler (`-Wunused:all`, and for production code `-Wvalue-discard` and `-Wnonunit-statement`).
`sbtn fix` removes unused imports with scalafix. Nothing removes other unused code automatically: fix it by hand, and
when a test value is unused, check first whether the case forgot to check it.
````

- [ ] **Step 4: Add the "Enforced by" notes to `test-conventions.md`**

Add one line at the end of each section:

| Section | Line to add |
| --- | --- |
| Directory structure and naming | `Not enforced.` |
| Behavior-driven style | `` `AnyFlatSpec` is reported by scalafix (`DisableSyntax`, `anyFlatSpec`); the rest isn't enforced. `` |
| Use Given / When / Then comments in tests | `Not enforced.` |
| Use fixtures to reduce duplication in test cases setup | `Not enforced.` |
| No `if`s around assertions | `Not enforced.` |
| No sleeping in tests | ``Enforced by scalafix (`DisableSyntax`, `threadSleep`), in production code too, where a justified sleep carries `// scalafix:ok DisableSyntax.threadSleep` and a comment giving the reason.`` |
| Shared test utilities | `Not enforced.` |

- [ ] **Step 5: Add a "Linting" section to `build.md`, and link the new doc**

In `docs/development/build.md`, before "## Building the fat JAR", add:

```markdown
## Linting

Before formatting, `sbtn fix` applies the autofixes of [scalafix](https://scalacenter.github.io/scalafix/): it removes
unused code and imports, orders the imports, and removes redundant syntax. The compiler also warns about unused code and
discarded values. [`linting.md`](linting.md) lists every compiler flag and scalafix rule, and how to suppress a
finding.
```

In the same file's "Formatting" section, change "Format everything:" to "Apply the scalafix autofixes (see
[Linting](#linting)), then format everything:".

In `docs/development/README.md`, "Documents in this directory", after the `test-conventions.md` item, add:

```markdown
- [`linting.md`](linting.md) — compiler warnings and scalafix rules: what each checks, and how to suppress a finding.
```

In `CONTRIBUTING.md`, "Getting set up", after the "Test conventions" item, add:

```markdown
- [Linting](docs/development/linting.md) — compiler warnings and scalafix rules.
```

- [ ] **Step 6: Commit**

```bash
git add docs/development/linting.md docs/development/coding-conventions.md docs/development/test-conventions.md \
  docs/development/build.md docs/development/README.md CONTRIBUTING.md
git commit -m "[#333/#334] Document the compiler warnings and the scalafix rules"
```

### Task 10: Verify the tooling branch and update the draft PR

**Files:** none changed.

**Interfaces:**

- Consumes: everything from Tasks 1–9.
- Produces: `<tooling PR>` (opened with the plan, before Task 0) with its final title and body; its number is used in
  Task 11's PR body.

- [ ] **Step 1: Run the full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: all tests pass.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill (the CLAUDE.md Coverage step) and apply its policy to the modified files and
modules. The branch adds tests (`JsonPreprocessorHttpRefLoaderTest`) and changes no statement's coverage otherwise, so
this reduces to "no module drops below its floor":

Run: `sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageCheck`
Expected: success.

- [ ] **Step 3: Confirm the branch's contents**

```bash
git status --short                                  # expected: empty
git log --oneline main..HEAD                        # expected: the plan, then the commits of Tasks 1–9
git diff --stat main..HEAD                          # expected: only the files under "Tooling PR" in "File structure"
sbtn lint; echo "exit=$?"                           # expected: exit=0 (every commit went through the hook)
sbtn "scalafixAll DisableSyntax; experiments/scalafixAll DisableSyntax"; echo "exit=$?"   # expected: exit=0
```

- [ ] **Step 4: Negative check: `DisableSyntax` in a test and in `experiments`**

```bash
T=sc-midi/src/test/scala/org/calinburloiu/music/scmidi
E=experiments/src/main/scala/org/calinburloiu/music/microtonalist/experiments
printf 'package org.calinburloiu.music.scmidi\n\nimport org.scalatest.flatspec.AnyFlatSpec\n\nclass FlatScratchTest extends AnyFlatSpec\n' > "$T/FlatScratchTest.scala"
printf 'package org.calinburloiu.music.microtonalist.experiments\n\nobject SleepScratch {\n  def f(): Unit = Thread.sleep(1)\n}\n' > "$E/SleepScratch.scala"
sbtn "sc-midi/scalafixAll DisableSyntax" 2>&1 | grep -E 'FlatScratchTest.*anyFlatSpec'
sbtn "experiments/scalafixAll DisableSyntax" 2>&1 | grep -E 'SleepScratch.*threadSleep'
rm "$T/FlatScratchTest.scala" "$E/SleepScratch.scala"
git status --short                                  # expected: empty
```

Expected: each `grep` prints the error.

- [ ] **Step 5: Write the PR body**

Write `$SCRATCH/pr-tooling.md`, replacing each `<…>` with its value:

```markdown
Part of #334, a sub-issue of #333. The first of three stacked PRs: tooling (this one), then the bulk autofix, then the
finish PR, which turns enforcement on. This PR enforces nothing: the new warnings are plain warnings, and `sbtn lint`
still checks only the formatting.

## Changes

- Compiler flags: `-Wunused:all`, `-no-indent` and `-old-syntax` for all code; `-Wvalue-discard` and
  `-Wnonunit-statement` for production code only (`Test / scalacOptions` inherits `Compile`'s, so they're removed from
  it explicitly).
- sbt-scalafix 0.14.9 and `.scalafix.conf`: `OrganizeImports` (removes unused imports, and orders them in IntelliJ
  IDEA's default layout), `DisableSyntax` (no `return`, TODOs with an issue number, no `Thread.sleep`, no
  `AnyFlatSpec`), `NoValInForComprehension`, `RedundantSyntax`. `sbtn fix` now applies the autofixes before formatting.
  scalafix removes unused imports only: no `RemoveUnused`, since it deleted `TrackManager`'s `@Subscribe` handlers.
- Hand fixes: <n> discarded values assigned to `_`; `TrackManager`'s `@Subscribe` handlers get `@nowarn`, since Guava's
  `EventBus` calls them through reflection, which the compiler can't see; unused test values fixed by hand, one of them
  a real test bug (`ScaleContextConverterTest` passed `None` instead of the context it built); two `return`s rewritten
  with `boundary`, with a new test for `JsonPreprocessorHttpRefLoader`; a polling sleep in
  `ConcurrentMidiTransmitterTest` replaced with a latch; the shutdown hook's sleep justified; every TODO has an issue
  number.
- Docs: new `docs/development/linting.md`; "Enforced by" notes in the coding and test conventions; "Avoid `new`" is now
  a recommendation; build reference, development setup and contributing guide.
- This sub-issue's implementation plan, `issues/00333-lint-and-format/plan-334-lint.md`. Its "Decisions after the
  design" record what changed after the design was approved (no `RemoveUnused`; unused imports stay warnings locally;
  the Lint step ends with CI's strict check), and its "Notes on the design" say where this PR applies the design
  differently from the design's text, and why: two `return`s rather than one, the import groups, scalafix already in
  `fix`.

## What the bulk PR will change

About <files> files, +<added>/−<removed>: unused imports removed, imports ordered, and <n> `s"…"` literals without an
interpolated value. The warning count goes from <count> (11 unused imports and 2 deprecations) to the 2 deprecations,
which stay warnings.

**Merge this PR and the bulk PR back to back:** in between, `sbtn fix` on `main` (the Lint step, once #335's finish PR
has merged) would rewrite the imports of about 75 files in any branch.

## Possible follow-up, not changed here

`MicrotonalistApp.main` discards the `Try` it builds, so a `Throwable` that is neither an `AppException` nor an
`Exception` (e.g. `NotImplementedError`) ends the program silently. The hand fix keeps that behavior.

<if Task 8, Step 7 was skipped: "## Open review items" — IntelliJ IDEA's Optimize Imports on the three sample files>

<unattended run only: "## Decisions made without the user", with the entries of $SCRATCH/decisions.md>

## Verification

- The flags report the violations of a scratch file, and not an assertion in a test; `show Test/scalacOptions` has no
  main-only flag. An instrumented (scoverage) compile reports the same warnings as a plain one.
- `DisableSyntax` reported exactly the 7 known violations before the fixes, none after, and reports `AnyFlatSpec` in a
  test and `Thread.sleep` in `experiments`. The TODO regex skips a TODO with an issue number and a mid-sentence
  "TODO".
- Mutation checks: `TrackManagerTest` fails without either `@Subscribe` handler; `ConcurrentMidiTransmitterTest` fails
  at once when reads skip the lock; `JsonPreprocessorHttpRefLoaderTest` fails without the early return.
- The full test suite and `coverageCheck` pass; `sbtn lint` passes.

<the harness's PR attribution lines>
```

Check the body for closing keywords: `grep -niE '(close|fix|resolve)[sd]?:? +#[0-9]' "$SCRATCH/pr-tooling.md"` must
print nothing.

- [ ] **Step 6: Push, and update the draft PR**

The draft PR already exists: it was opened with only this plan, before Task 0. Push the branch with
`git push origin feature/lint`, then call the GitHub MCP `update_pull_request` for `<tooling PR>` with:

- `title`: `[#333/#334] Add compiler warning flags and scalafix, and fix the violations by hand`
- `body`: the content of `$SCRATCH/pr-tooling.md`

Keep it a draft. Its label, milestone, assignee and project were set when it was opened.

- [ ] **Step 7: Check that the PR links no issue for closing**

```bash
gh api graphql -f query='query { repository(owner: "calinburloiu", name: "microtonalist") {
  pullRequest(number: <tooling PR>) { isDraft baseRefName closingIssuesReferences(first: 10) { nodes { number } } } } }'
```

Expected: `"isDraft":true`, `"baseRefName":"main"` and `"nodes":[]`.

## Phase 2: Bulk PR (`feature/lint-autofix`)

### Task 11: Generate, verify and open the bulk PR

**Files:** only the `.scala` files that `sbtn fix` changes.

**Interfaces:**

- Consumes: the tooling branch; `<tooling PR>` (Task 10).
- Produces: the bulk PR's number, `<bulk PR>`, used in Tasks 12–15.

- [ ] **Step 1: Branch off the tooling branch**

Run: `git switch -c feature/lint-autofix` (from `feature/lint`, with a clean working tree).

- [ ] **Step 2: Run the autofixes and the formatter**

```bash
FIX_FULL='scalafixAll --no-cache; experiments/scalafixAll --no-cache; scalafmtAll; scalafmtSbt; experiments/scalafmtAll'
sbtn "$FIX_FULL"; echo "exit=$?"
```

Expected: exit 0. This is `sbtn fix` on every file: Task 8's measurements left scalafix's cache believing it had
already processed most of them.

- [ ] **Step 3: Check that only tool output changed**

```bash
git status --short | grep -vE '\.scala$'            # expected: no output
git diff --shortstat
```

Expected: the numbers of Task 8, Step 3 (about 74 files, +80/−85).

- [ ] **Step 4: Check that no definition was removed (Review Focus 1)**

```bash
git diff -U0 -- '*.scala' | grep -E '^-[^-]' | grep -vE '^-\s*(import\b|[A-Z][A-Za-z0-9_]*(, *[A-Z][A-Za-z0-9_]*)*,?$|\}$|$)' | grep -v 's"'
git diff -U0 -- '*.scala' | grep -cE '^-.*(def |val |var |@Subscribe)'
```

Expected: the first prints nothing, and the second prints 0: every removed line is an import or a redundant `s`
interpolator. Anything else means an autofix removed code, which decision 1 rules out: **STOP** and report. The fix
belongs in `.scalafix.conf` on the tooling branch, never in a hand edit here.

- [ ] **Step 5: Check that the build compiles cleanly and the tests pass**

Call `mcp__metals__compile-full`.
Expected: success, with only the two deprecation warnings.

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`, then (with the `scoverage-inspector` skill's policy, as in Task 10)
`sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageCheck`.
Expected: both pass.

- [ ] **Step 6: Commit**

```bash
git add -u
git commit -m "[#333/#334] Apply the scalafix autofixes" -m "Output of \`sbtn fix\`, with no hand edits."
git status --short                                  # expected: empty
```

The hook runs scalafmt on the staged files again. It must change nothing (an empty `git status` afterwards).

- [ ] **Step 7: Check that `lint` passes and that the commit is reproducible**

```bash
sbtn lint; echo "exit=$?"                           # expected: exit=0
FIX_FULL='scalafixAll --no-cache; experiments/scalafixAll --no-cache; scalafmtAll; scalafmtSbt; experiments/scalafmtAll'
git switch --detach feature/lint
sbtn "$FIX_FULL"
git diff --quiet feature/lint-autofix -- '*.scala' '*.sbt'; echo "differs=$?"   # expected: differs=0
git restore -- '*.scala' '*.sbt'
git switch feature/lint-autofix
```

Use the exit code for the verdict, not a diff listing.

- [ ] **Step 8: Check that a second run changes nothing**

Run: `sbtn "$FIX_FULL"; git status --short` (with `FIX_FULL` defined as in Step 7)
Expected: empty. The autofixes and the formatter agree with each other.

- [ ] **Step 9: Write the PR body**

Write `$SCRATCH/pr-bulk.md`, replacing each `<…>`:

````markdown
Part of #334, a sub-issue of #333. The second of three stacked PRs, based on #<tooling PR>.

This PR holds only the output of scalafix's autofixes and the formatter, with no hand edits. One command reproduces it:

```bash
sbtn fix   # scalafixAll, experiments/scalafixAll, then the scalafmt commands
```

What it changes: unused imports removed, imports ordered in IntelliJ IDEA's default layout, and `s"…"` literals without
an interpolated value turned into plain ones. It removes no definition: scalafix removes unused imports only, and
#<tooling PR> fixed every other unused finding by hand.

**Reviewing it:** rather than reading the diff line by line, re-run the command on the base branch and compare:

```bash
git switch --detach <base branch>
sbtn "scalafixAll --no-cache; experiments/scalafixAll --no-cache; scalafmtAll; scalafmtSbt; experiments/scalafmtAll"
git diff --quiet feature/lint-autofix -- '*.scala' '*.sbt'; echo "differs=$?"   # expected: differs=0
git restore -- '*.scala' '*.sbt' && git switch -
```

That's `sbtn fix` with scalafix's incremental cache off: the cache skips a file whose content scalafix has already
processed, even when those changes were reverted since.

The commit is regenerated right before merging, on top of the latest `main`, so it never carries conflict resolutions.
Please merge it soon after the tooling PR. Once it's merged, the finish PR adds its squashed SHA to
`.git-blame-ignore-revs`, so `git blame` skips it.

## Verification

- Every removed line is an import or a redundant `s` interpolator.
- The only compiler warnings left are the two known deprecations. The full test suite and `coverageCheck` pass.
- `sbtn lint` passes, and a second `sbtn fix` changes nothing.

<the harness's PR attribution lines>
````

Check it: `grep -niE '(close|fix|resolve)[sd]?:? +#[0-9]' "$SCRATCH/pr-bulk.md"` prints nothing.

- [ ] **Step 10: Open the draft PR and stack it on the tooling PR**

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr "[#333/#334] Apply the scalafix autofixes" \
  "$(cat "$SCRATCH/pr-bulk.md")" --milestone "Agentic Coding" --dry-run
```

Check the dry run, then run it without `--dry-run`. The script opens the PR against `main`, so retarget it at once:
GitHub MCP `update_pull_request` with `base: "feature/lint"`, or `gh pr edit <bulk PR> --base feature/lint`. Then run
the GraphQL query from Task 10, Step 7 for `<bulk PR>`.
Expected: `"isDraft":true`, `"baseRefName":"feature/lint"`, `"nodes":[]`.

- [ ] **Step 11: STOP and report**

Give the user both PR links, the bulk PR's shortstat, and the next steps: they review and merge the tooling PR; Task 12
regenerates the bulk commit when asked; the finish PR starts after the bulk PR and #335's finish PR have merged.

**Unattended run:** if Tasks 10–11 added decision log entries after the tooling PR was opened, update the tooling PR
body's "Decisions made without the user" section (GitHub MCP `update_pull_request`). The final report lists every
decision in the log, each with how to change it, followed by anything that failed or was skipped.

### Task 12: Regenerate the bulk commit (on the user's request)

Run this task only when the user asks: when the tooling PR changes `.scalafix.conf`, `.scalafmt.conf`, `build.sbt` or
any Scala file during review, and always right before the bulk PR merges, after the tooling PR has merged.

**Files:** the same as Task 11.

**Interfaces:**

- Consumes: `<bulk PR>`; the base `B`: `origin/feature/lint` while the tooling PR is open, `origin/main` once it has
  merged.
- Produces: the regenerated bulk commit, force-pushed to `<bulk PR>`.

- [ ] **Step 1: Reset the branch onto the base**

```bash
git status --short                                  # expected: empty
git fetch origin
git switch feature/lint-autofix
git reset --hard "$B"
sbtn reload
```

The old bulk commit is discarded on purpose. It's regenerated in Step 2, so it never carries conflict resolutions. If
`B` changed `project/plugins.sbt` or the compiler options, run `bin/microtonalist-dev-stack restart` instead of
`sbtn reload`.

- [ ] **Step 2: Regenerate and verify**

Run Task 11, Steps 2–8, with `$B` as the base in Step 7's reproducibility check instead of `feature/lint`. If `main`
gained code with new warnings other than unused imports, Step 5's compile shows them: fix them on the base first (a
small PR of its own once the tooling PR has merged), never here.

- [ ] **Step 3: Push and retarget**

Run: `git push --force-with-lease origin feature/lint-autofix`.
If `B` is `origin/main` and the PR's base is still `feature/lint`, set it to `main` (GitHub MCP `update_pull_request`,
or `gh pr edit <bulk PR> --base main`). Update the base branch named in the PR body.

- [ ] **Step 4: STOP**

Tell the user the bulk PR is regenerated and ready. Recommend a squash merge soon, before other branches move `main`.

## Phase 3: Finish PR (`feature/lint-enforce`), after the bulk PR and #335's finish PR merge

### Task 13: Check what #335's finish PR landed, and `.git-blame-ignore-revs`

**Files:**

- Modify: `.git-blame-ignore-revs`

**Interfaces:**

- Consumes: `<bulk PR>` and #335's finish PR, both merged into `main`.
- Produces: `SHA`, the bulk PR's squashed commit; the list of differences between this plan and #335's finish PR, which
  Tasks 14–15 apply.

- [ ] **Step 1: Confirm both merges and get the squashed SHA**

Call the GitHub MCP `pull_request_read` (method `get`) for `<bulk PR>`, and search the pull requests for #335's finish
PR (head `feature/scalafmt-enforce`).
Expected: both `merged: true`. Take the bulk PR's `merge_commit_sha` as `SHA`. If either isn't merged, **STOP**: this
phase waits for both.

- [ ] **Step 2: Create the branch and check the SHA**

```bash
git fetch origin
git switch -c feature/lint-enforce origin/main
git log -1 --format='%H %s' "$SHA"                  # expected: "[#333/#334] Apply the scalafix autofixes (#<bulk PR>)"
git merge-base --is-ancestor "$SHA" origin/main && echo on-main   # expected: on-main
```

- [ ] **Step 3: Check the plan against what #335's finish PR landed**

This plan was written before #335's finish PR existed; its Tasks 14–15 assume what `plan-335-scalafmt.md` (Tasks 9–10)
planned. Read each item on `main` and note any difference:

| What | Where | Assumed by this plan |
| --- | --- | --- |
| The CI `lint` job | `.github/workflows/scala.yml` | A job `lint` whose last step, "Check formatting", runs `sbt lint` |
| The Lint final check | `AGENTS.md`, "Coding Workflow" | `- **Lint**. Run `sbtn fix` to format the code, then make sure `sbtn lint` passes.` after **Documentation** |
| `.git-blame-ignore-revs` | repository root | A comment header, then one `# <commit subject>` line and one full SHA per entry |
| `blame.ignoreRevsFile` | `CONTRIBUTING.md` | Documented after the `core.hooksPath` block |
| CI enforcement in the docs | `docs/development/build.md`, "Formatting" | "CI's `lint` job runs `sbt lint` on every pull request, in parallel with the tests." |
| The `lint` alias | `build.sbt` | `scalafmtCheckAll; scalafmtSbtCheck; experiments/scalafmtCheckAll` |

For each difference, adapt the matching edit in Tasks 14–15 to what landed, keeping the design's intent (e.g. a
different step name: edit that step). Report the differences in chat. If a difference changes the design's intent (e.g.
no `lint` job at all), **STOP** and ask.

- [ ] **Step 4: Append the bulk commit to `.git-blame-ignore-revs`**

At the end of the file, following the format of the existing entry:

```
# [#333/#334] Apply the scalafix autofixes (#<bulk PR>)
<SHA>
```

- [ ] **Step 5: Check that blame skips it**

Pick a file and a line that the bulk commit changed in place, preferably an `s"…"` literal turned into a plain one
(git maps such a line back most reliably):

```bash
git show "$SHA" -U0 -- '*.scala' | grep -B8 -E '^\+.*logger\.info\("' | grep -E '^(\+\+\+|@@)'
```

Set `F` to the file after `+++ b/` and `L` to the `+L` number of its hunk, then:

```bash
git blame -L "$L,$L" -- "$F"                                            # expected: attributed to $SHA
git blame --ignore-revs-file .git-blame-ignore-revs -L "$L,$L" -- "$F"  # expected: a commit older than $SHA
```

- [ ] **Step 6: Commit**

```bash
git add .git-blame-ignore-revs
git commit -m "[#333/#334] Skip the scalafix autofixes in git blame"
```

### Task 14: The warnings policy, and the red-phase rule

**Files:**

- Modify: `build.sbt` (the property and `compilerOptions`), `AGENTS.md` (the red-phase note)
- Scratch: the negative-check sources in `common`

**Interfaces:**

- Consumes: `compilerOptions` (Task 1).
- Produces: `strictWarnings` and `warningsPolicy` in `build.sbt`; from here on, every warning is an error except
  deprecations, and, in default mode, a red-phase stub's unused parameter and an unused import.

- [ ] **Step 1: Write the negative-check sources and see them compile today**

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
printf 'package org.calinburloiu.music.microtonalist.common\n\nclass RedPhaseStub(x: Int) {\n  def f: Int = ???\n}\n' > "$D/RedPhaseStub.scala"
printf 'package org.calinburloiu.music.microtonalist.common\n\nimport scala.util.Try\n\nobject UnusedImportScratch\n' > "$D/UnusedImportScratch.scala"
printf 'package org.calinburloiu.music.microtonalist.common\n\nobject UnusedPrivateScratch {\n  private def unused(): Int = 1\n}\n' > "$D/UnusedPrivateScratch.scala"
sbtn "common/compile"; echo "exit=$?"
```

Expected: exit 0, with three warnings: the unused parameter `x`, the unused import, the unused private member. Keep the
files for Steps 5–7.

- [ ] **Step 2: Add the property and the policy**

In `build.sbt`, before `compilerOptions`, add:

```scala
// When `-Dmicrotonalist.build.strictWarnings=true` is passed to sbt, the warnings that the default mode allows are
// compile errors too. CI's `lint` job and the Lint step's last check set it. See
// docs/development/linting.md#warnings-policy.
lazy val strictWarnings: Boolean = sys.props.get("microtonalist.build.strictWarnings").contains("true")

// Every warning is a compile error, except deprecations and, unless strictWarnings, two more: an unused constructor
// parameter, the one warning a TDD red-phase stub needs, and an unused import, which `sbtn fix` removes (scalafix only
// runs on code that compiles). Within one -Wconf option the rightmost matching rule wins (not the leftmost, as scalac's
// help says), so `any:e` comes first. Re-test any change on a scratch file.
lazy val warningsPolicy: String =
  if (strictWarnings) "-Wconf:any:e,cat=deprecation:w"
  else "-Wconf:any:e,cat=deprecation:w,msg=unused explicit parameter:w,msg=unused import:w"
```

and add `warningsPolicy,` as the last element of `compilerOptions`.

- [ ] **Step 3: Restart the dev stack and check both modes' options**

Run `bin/microtonalist-dev-stack restart`, then:

```bash
sbtn "show common/scalacOptions" 2>&1 | grep Wconf
sbt -Dmicrotonalist.build.strictWarnings=true "show common/scalacOptions" 2>&1 | grep Wconf
```

Expected: `-Wconf:any:e,cat=deprecation:w,msg=unused explicit parameter:w,msg=unused import:w`, then
`-Wconf:any:e,cat=deprecation:w`.

- [ ] **Step 4: Check that the code compiles in both modes**

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
mv "$D/RedPhaseStub.scala" "$D/UnusedImportScratch.scala" "$D/UnusedPrivateScratch.scala" "$SCRATCH/"
sbtn "Test/compile; experiments/Test/compile"; echo "exit=$?"
sbt -Dmicrotonalist.build.strictWarnings=true "Test/compile" "experiments/Test/compile"; echo "exit=$?"
```

Expected: both exit 0, with the two deprecation warnings still warnings. If `main` gained warnings since the bulk PR,
fix them in a commit of their own before going on.

- [ ] **Step 5: Check an unused import in both modes**

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
mv "$SCRATCH/UnusedImportScratch.scala" "$D/"
sbtn "common/compile"; echo "exit=$?"
sbt -Dmicrotonalist.build.strictWarnings=true "common/compile"; echo "exit=$?"
mv "$D/UnusedImportScratch.scala" "$SCRATCH/"
```

Expected: the first exits 0 with one warning ("unused import"); the second exits non-zero, with that as an error.

- [ ] **Step 6: Check the red-phase stub in both modes (Review Focus 3)**

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
mv "$SCRATCH/RedPhaseStub.scala" "$D/"
sbtn "common/compile"; echo "exit=$?"
sbt -Dmicrotonalist.build.strictWarnings=true "common/compile"; echo "exit=$?"
rm "$D/RedPhaseStub.scala"
```

Expected: the first exits 0 with one warning ("unused explicit parameter"); the second exits non-zero, with that as an
error.

- [ ] **Step 7: Check what `sbtn fix` does with unused code (Review Focus 4)**

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
mv "$SCRATCH/UnusedImportScratch.scala" "$D/"
sbtn fix; echo "exit=$?"
grep -c 'import scala.util.Try' "$D/UnusedImportScratch.scala"
rm "$D/UnusedImportScratch.scala"
mv "$SCRATCH/UnusedPrivateScratch.scala" "$D/"
sbtn fix > "$SCRATCH/fix.log" 2>&1; echo "exit=$?"
grep -E 'UnusedPrivateScratch|unused private member' "$SCRATCH/fix.log"
grep -c 'private def unused' "$D/UnusedPrivateScratch.scala"
rm "$D/UnusedPrivateScratch.scala"
git status --short                                  # expected: only build.sbt
```

Expected: the first `sbtn fix` exits 0 and removes the import (count 0). The second exits non-zero with the compiler's
error, naming the file, the line and "unused private member", and leaves the method in place (count 1): scalafix never
ran.

- [ ] **Step 8: Add the red-phase note to `AGENTS.md`**

In "Coding Workflow", in the **Red** bullet, after "create the thinnest possible stub (`???` bodies, no logic) to get
them to compile", add the sentence: "A stub may leave constructor parameters unused; that warning is allowed until
green. Everything else must compile."

- [ ] **Step 9: Commit**

```bash
git add build.sbt AGENTS.md
git commit -m "[#333/#334] Make compiler warnings errors, except deprecations, red-phase parameters and unused imports"
```

### Task 15: The `lint` alias, CI's strict mode, the docs, and the finish PR

**Files:**

- Modify: `build.sbt` (the `lint` alias and its comment), `.github/workflows/scala.yml`, `AGENTS.md`,
  `docs/development/linting.md`, `docs/development/build.md`

**Interfaces:**

- Consumes: `warningsPolicy` (Task 14); the differences found in Task 13, Step 3.
- Produces: the finish PR, which closes #334.

- [ ] **Step 1: Extend the `lint` alias**

In `build.sbt`, replace the aliases' comment and `lint`:

```scala
// Code formatting and linting: `fix` applies the scalafix autofixes, then formats the sources with scalafmt; `lint`
// compiles all the code with warnings as errors, then checks the scalafix rules and the formatting without changing
// anything. `root` doesn't aggregate `experiments`, so both name it explicitly. See
// docs/development/build.md#formatting and docs/development/linting.md.
addCommandAlias("fix", "scalafixAll; experiments/scalafixAll; scalafmtAll; scalafmtSbt; experiments/scalafmtAll")
addCommandAlias("lint", "Test/compile; experiments/Test/compile; " +
  "scalafixAll --check; experiments/scalafixAll --check; " +
  "scalafmtCheckAll; scalafmtSbtCheck; experiments/scalafmtCheckAll")
```

Run `sbtn reload`.

- [ ] **Step 2: Negative checks: each violation fails `lint` (design section 7)**

Run each on its own, since `lint` stops at the first failing command. After each, the cleanup must leave
`git status --short` showing only this task's edits.

```bash
D=common/src/main/scala/org/calinburloiu/music/microtonalist/common
E=experiments/src/main/scala/org/calinburloiu/music/microtonalist/experiments
T=common/src/test/scala/org/calinburloiu/music/microtonalist/common
P='package org.calinburloiu.music.microtonalist.common\n\n'
lint_fails_with() { sbtn lint > "$SCRATCH/lint.log" 2>&1; echo "exit=$? $(grep -cE "$1" "$SCRATCH/lint.log") match(es) of $1"; }

printf "${P}class IndentScratch:\n  def f: Int = 1\n" > "$D/Scratch.scala"; lint_fails_with 'Scratch.scala'; rm "$D/Scratch.scala"
printf "${P}object Scratch {\n  def f(x: Int): Int = if x > 0 then x else 0\n}\n" > "$D/Scratch.scala"; lint_fails_with 'Scratch.scala'; rm "$D/Scratch.scala"
printf "${P}import scala.util.Try\n\nobject Scratch\n" > "$D/Scratch.scala"; lint_fails_with 'Scratch.scala'; rm "$D/Scratch.scala"
printf "${P}object Scratch {\n  private def unused(): Int = 1\n}\n" > "$D/Scratch.scala"; lint_fails_with 'unused private member'; rm "$D/Scratch.scala"
printf "${P}object Scratch {\n  def f(m: scala.collection.mutable.Map[Int, Int]): Unit = {\n    m.remove(1)\n  }\n}\n" > "$D/Scratch.scala"; lint_fails_with 'discarded non-Unit'; rm "$D/Scratch.scala"
printf "${P}object Scratch {\n  @scala.annotation.nowarn(\"msg=unused import\")\n  def f: Int = 1\n}\n" > "$D/Scratch.scala"; lint_fails_with 'does not suppress'; rm "$D/Scratch.scala"
printf "${P}object Scratch {\n  def f(x: Int): Int = {\n    if (x > 0) {\n      return x\n    }\n    0\n  }\n}\n" > "$D/Scratch.scala"; lint_fails_with 'DisableSyntax.return'; rm "$D/Scratch.scala"
printf "${P}// TODO Something\nobject Scratch\n" > "$D/Scratch.scala"; lint_fails_with 'todoWithoutIssue'; rm "$D/Scratch.scala"
printf "${P}object Scratch {\n  def f(): Unit = Thread.sleep(1)\n}\n" > "$D/Scratch.scala"; lint_fails_with 'threadSleep'; rm "$D/Scratch.scala"
printf "${P}import org.scalatest.flatspec.AnyFlatSpec\n\nclass ScratchTest extends AnyFlatSpec\n" > "$T/ScratchTest.scala"; lint_fails_with 'anyFlatSpec'; rm "$T/ScratchTest.scala"
printf "${P}object Scratch {\n  def f(a:Int,b:Int)=a+b\n}\n" > "$D/Scratch.scala"; lint_fails_with 'Scratch.scala'; rm "$D/Scratch.scala"
printf 'package org.calinburloiu.music.microtonalist.experiments\n\nimport scala.util.Try\n\nobject Scratch\n' > "$E/Scratch.scala"; lint_fails_with 'Scratch.scala'; rm "$E/Scratch.scala"
```

Expected: every line shows a non-zero exit and at least one match: indentation syntax, `if … then`, an unused import
(only a warning in this mode, so it fails at the `OrganizeImports` check), an unused private member, a discarded value,
a stale `@nowarn`, `return`, a bare TODO, `Thread.sleep`, `AnyFlatSpec`, unformatted code, and an unused import in
`experiments`.

- [ ] **Step 3: Check the whole tree in strict mode, as CI will**

Run: `sbt -Dmicrotonalist.build.strictWarnings=true lint; echo "exit=$?"`
Expected: exit=0. The two deprecations stay warnings.

- [ ] **Step 4: CI's `lint` job in strict mode**

In `.github/workflows/scala.yml`, `lint` job, replace its last step (see Task 13, Step 3 for its landed name) with:

```yaml
    - name: Check compiler warnings, scalafix rules and formatting
      run: sbt -Dmicrotonalist.build.strictWarnings=true lint
```

Run: `ruby -ryaml -e 'y = YAML.load_file(".github/workflows/scala.yml"); puts y["jobs"]["lint"]["steps"].last["run"]'`
Expected: `sbt -Dmicrotonalist.build.strictWarnings=true lint`.

- [ ] **Step 5: The docs and `AGENTS.md`**

In `docs/development/linting.md`, replace the "Commands" section's list with:

```markdown
- `sbtn fix` applies the scalafix autofixes, then formats the code with scalafmt. scalafix compiles first, so it can't
  run while the code has a compile error: fix those by hand first. An unused import is only a warning here, so
  `sbtn fix` removes it; any other unused code is an error, which you fix.
- `sbtn lint` compiles the main and test code of every module, then checks the scalafix rules and the formatting,
  without changing anything.
- `sbt -Dmicrotonalist.build.strictWarnings=true lint` is the same check in strict mode, as CI's `lint` job runs it. It
  also fails on the warnings that the default mode allows, apart from deprecations (see below). It's the last command
  of the agents' Lint step.
```

and add, after "## Compiler warnings" and its content:

````markdown
## Warnings policy

Every compiler warning is a compile error, with these exceptions:

| Mode | Used by | `-Wconf` |
| --- | --- | --- |
| Default | Local builds, Metals and `sbtn`, IntelliJ IDEA, CI's `build` job (`coverageCheck`) | `-Wconf:any:e,cat=deprecation:w,msg=unused explicit parameter:w,msg=unused import:w` |
| Strict (`-Dmicrotonalist.build.strictWarnings=true`) | CI's `lint` job, the last check of the agents' Lint step | `-Wconf:any:e,cat=deprecation:w` |

- Deprecations are never errors. Don't add a use of a deprecated API all the same.
- An unused constructor parameter ("unused explicit parameter") is the only warning that a TDD red-phase stub needs, so
  the default mode allows it until green.
- An unused import is a warning in default mode, so that the code still compiles and `sbtn fix` can remove it: scalafix
  only runs on code that compiles. `sbtn lint` fails on it all the same, at the `OrganizeImports` check.
- Strict mode makes both errors, so one left over fails the Lint step and CI. A warning is never noise to ignore: each
  one is either a deprecation or an error in strict mode.
- The long-running `sbtn` server can't take a property for a single command, so run a strict check with plain `sbt`,
  which uses a separate JVM and writes to `target/` rather than the server's `target-bsp/`:

  ```bash
  sbt -Dmicrotonalist.build.strictWarnings=true lint
  ```

**`-Wconf` ordering.** Within a single `-Wconf` option the rightmost matching rule wins (verified on Scala 3.6.3), even
though the compiler's help text says "leftmost". So `any:e` comes first and the exceptions after it. Re-test any change
to these strings on a scratch file.

**Phase quirk.** An error in an earlier compiler phase, such as an unused private member, stops compilation before later
phases report their warnings, such as deprecations. Those appear on the next compile, once the errors are fixed.
````

In `docs/development/build.md`, "Linting" section, add after its paragraph:

````markdown
`sbtn lint` also compiles every module with warnings as errors, and checks the scalafix rules. By default three warnings
stay warnings: deprecations; an unused constructor parameter, which a TDD red-phase stub needs; and an unused import,
which `sbtn fix` removes (`sbtn lint` still fails on it). The build property `microtonalist.build.strictWarnings` makes
the last two errors too; CI's `lint` job sets it. Run it locally with plain `sbt`, since the `sbtn` server can't take a
property for one command:

```bash
sbt -Dmicrotonalist.build.strictWarnings=true lint
```
````

and, in "Formatting", replace the sentence about CI's `lint` job (Task 13, Step 3) with "CI's `lint` job runs
`sbt -Dmicrotonalist.build.strictWarnings=true lint` on every pull request, in parallel with the tests."

In `AGENTS.md`, change the Lint final check to:

```markdown
    - **Lint**. Run `sbtn fix` to apply the scalafix autofixes and format the code, then make sure `sbtn lint` passes,
      and last that CI's strict check passes: `sbt -Dmicrotonalist.build.strictWarnings=true lint`.
```

and add a section after "# Coverage":

```markdown
# Warnings and Lint Rules

Never ignore a compiler warning. Read the warnings of every compile, and deal with each one your change introduced
before the task ends. Most warnings are compile errors. Three stay warnings in local builds:

- An unused import: `sbtn fix` removes it.
- A red-phase stub's unused constructor parameter: allowed until green, then use or remove the parameter.
- A deprecation: don't add a use of a deprecated API.

The Lint step's strict check, which CI runs too, fails on the first two, so only deprecations can remain.

Nothing removes other unused code automatically: fix it by hand. Before deleting an "unused" private method, check
that nothing calls it through reflection (e.g. Guava's `@Subscribe`); before deleting an unused test value, check
whether the case forgot to check it. `sbtn fix` compiles before it applies the autofixes, so fix any compile error it
reports first. scalafix also checks import order, no `return`, TODOs with an issue number, no `Thread.sleep` and no
`AnyFlatSpec`.

Fix a finding rather than suppress it. When a suppression is justified, use `@nowarn("msg=<message regex>")` on the
narrowest definition for a compiler warning, or `// scalafix:ok <RuleId>` at the end of the line for a scalafix
finding, with a comment giving the reason, and a `// TODO #<issue>` if it's temporary. See
[`docs/development/linting.md`](docs/development/linting.md).
```

- [ ] **Step 6: Verify locally**

```bash
sbtn lint; echo "exit=$?"                           # expected: exit=0
git status --short                                  # expected: only this task's edits
```

Then run the full test suite (`sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`) and, with the `scoverage-inspector`
skill's policy as in Task 10, `sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageCheck`, which now compiles
the instrumented code with warnings as errors (Review Focus 5). Both must pass.

- [ ] **Step 7: Commit**

```bash
git add build.sbt .github/workflows/scala.yml AGENTS.md docs/development/linting.md docs/development/build.md
git commit -m "[#333/#334] Enforce the compiler warnings and scalafix in CI and the agent workflow"
```

- [ ] **Step 8: Open the draft PR**

Write `$SCRATCH/pr-finish.md`:

```markdown
The last of the three #334 PRs. The bulk autofix merged in #<bulk PR> as `<short SHA>`.

- Compiler warnings are compile errors, except deprecations, an unused constructor parameter (which a TDD red-phase
  stub needs) and an unused import (which `sbtn fix` removes; scalafix only runs on code that compiles). The build
  property `microtonalist.build.strictWarnings` makes the last two errors too.
- `sbtn lint` now also compiles all the code and checks the scalafix rules. CI's `lint` job runs it in strict mode.
- `.git-blame-ignore-revs` lists the bulk commit, so `git blame` skips it.
- CLAUDE.md: never ignore a warning (what to do with each kind that can remain), the red-phase exception, the
  suppression rules, and a Lint step that ends with CI's strict check. Docs: the warnings policy in `linting.md`, the
  strict property in `build.md`.

<if Task 13, Step 3 found differences: "## Adapted to #335's finish PR", listing them>

## Verification

- A deliberate violation of each check fails `sbtn lint`: indentation syntax, `if … then`, an unused import (also in
  `experiments`), an unused private member, a discarded value, a stale `@nowarn`, `return`, a bare TODO,
  `Thread.sleep`, `AnyFlatSpec`, and unformatted code.
- A red-phase stub and an unused import compile with a warning in default mode and fail in strict mode; deprecations
  stay warnings in both. `sbtn fix` removes an unused import, and fails on an unused private method with the
  compiler's error, leaving the method in place.
- `sbt -Dmicrotonalist.build.strictWarnings=true lint`, the full test suite and `coverageCheck` pass locally, and the
  CI jobs are green on this PR.
- `git blame --ignore-revs-file .git-blame-ignore-revs` attributes a line the bulk commit changed to an older commit.

<the harness's PR attribution lines>
```

This PR closes #334, so pass the issue spec. The script adds the `[#333/#334]` prefix and the `Resolves #334` line:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 333/334 \
  "Make compiler warnings errors and enforce scalafix in CI and the agent workflow" \
  "$(cat "$SCRATCH/pr-finish.md")" --dry-run
```

Check the dry run (title prefix, `Resolves #334`, milestone inherited from #334), then run it without `--dry-run`. Run
the GraphQL query from Task 10, Step 7 for this PR.
Expected: `"nodes":[{"number":334}]`.

- [ ] **Step 9: Wait for CI and report**

Watch the PR's checks (GitHub MCP `pull_request_read` with `get_check_runs`, or `gh pr checks <finish PR> --watch`).
Expected: `build`, `lint` and `license-headers` all green; the strict `lint` job must be green on its first run (design
section 7). Then report the PR link and the CI result to the user. **STOP.**

---

## Design coverage

| Design item | Task |
| --- | --- |
| §2 Three squash-merged PRs, titles, branches | 10, 11, 15 |
| §2 Bulk PR: only tool output, reproducible, regenerated before merging | 11, 12 |
| §2 Finish PR: squashed SHA in `.git-blame-ignore-revs` | 13 |
| §4 Flags, main-only scoping confirmed with `show Test/scalacOptions` | 1 |
| §4 Warnings policy, strict property, `-Wconf` ordering | 14, 15 |
| §4 Exceptions: `@nowarn` at the narrowest scope, `-Wunused:nowarn` | 3, 9, 15 |
| §4 Tooling PR without `-Wconf`, finish PR with it | 1, 14 |
| §4 CLAUDE.md red-phase note | 14 |
| §5 sbt-scalafix, SemanticDB | 4 |
| §5 Rules checked on Scala 3.6.3 | pre-measured; 4, 8 |
| §5 `OrganizeImports` layout, wildcard threshold, IntelliJ IDEA's Optimize Imports | 8 |
| §5 `DisableSyntax` with messages pointing to the conventions | 4 |
| §5 Existing violations: `return`, TODOs, sleeps, discarded values, unused code; count first | 1, 2, 3, 5, 6, 7 |
| §5 Aliases, CI strict `lint` | 8, 15 |
| §5 Docs: `linting.md`, CLAUDE.md, conventions' "Enforced by", "Avoid `new`", `build.md` | 9, 14, 15 |
| §7 Negative checks, red-phase stub in both modes | 1, 4, 10, 14, 15 |
| §7 Bulk PR reproducible | 11, 12 |
| §7 Tests and `coverageCheck` after every PR | 10, 11, 15 |
| §7 Metals `compile-full` still works | 0, 2, 3, 8, 11 |
| §7 The finish PR's first `lint` run is green | 15 |
| §8 Coverage instrumentation warnings | 9, 15 |
| Decision 1: scalafix removes only unused imports; other unused code is fixed by hand | 3, 8, 9, 11, 14 |
| Decision 2: unused imports stay warnings locally, so `sbtn fix` removes them | 14, 15 |
| Decision 3: agents don't ignore warnings (CLAUDE.md rule, strict check in the Lint step) | 15 |
