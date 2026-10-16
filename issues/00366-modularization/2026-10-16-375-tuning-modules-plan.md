# `tuning`, `tuning-changer` and `tuning-changer-pedal` Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-10-16
- **Based on commit:** `3f74046` (`refactoring/sc-midi-javamidi`, step 2 implemented and reviewed, in PR #373, not
  merged; the same tree as `ddafa76`)
- **Issue:** #375, step 3 of #366

**Goal:** Split `tuner`'s contracts into a `tuning` module (`Tuning`, `DefaultCentsTolerance`, the `TuningChange` ADT)
and a `tuning-changer` module (`TuningChanger`, `TuningChangeTriggers`), and move `PedalTuningChanger` into a
`tuning-changer-pedal` module, so that `composition` depends on `tuning` and not on `tuner`, without changing behaviour.

**Architecture:** Two commits move the code with `git mv`. The first creates `tuning`, which keeps the `…tuner`
package, so no import changes, and switches `composition` from `tuner` to it. The second creates `tuning-changer`
(`…tuner.changer`) and `tuning-changer-pedal` (`…tuner.changer.pedal`), whose new packages change the imports and
ScalaDoc links of `rig` and `format`. A third commit documents the modules: `tuner`'s `ARCHITECTURE.md` splits into
four.

**Tech Stack:** sbt 1, Scala 3, scoverage, Markdown.

**Spec:** [`2026-10-16-design.md`](2026-10-16-design.md): *Module conventions* and step *3. `tuning`, `tuning-changer`
and `tuning-changer-pedal`*.

## Global Constraints

- The modules, all in the `tuner/` grouping folder, each with `name := "microtonalist-<id>"`, `commonSettings`,
  `disablePlugins(AssemblyPlugin)`, and aggregated by `root` (`…` abbreviates `org.calinburloiu.music.microtonalist`):

  | Module | `lazy val` | Package | Holds | `dependsOn` | Libraries |
  | ------ | ---------- | ------- | ----- | ----------- | --------- |
  | `tuning` | `tuningModule` | `…tuner` | `Tuning.scala`, `package.scala` (`DefaultCentsTolerance`), `TuningChange.scala` | `scMidiModule` | `guava`, which `Tuning` imports |
  | `tuner` | `tunerModule` | `…tuner` | the rest of today's `tuner` | adds `tuningModule` | unchanged |
  | `tuning-changer` | `tuningChangerModule` | `…tuner.changer` | `TuningChanger`, `TuningChangeTriggers` | `commonModule`, `scMidiModule`, `tuningModule` | `jsr305`, for `@NotThreadSafe` |
  | `tuning-changer-pedal` | `tuningChangerPedalModule` | `…tuner.changer.pedal` | `PedalTuningChanger` | `scMidiModule`, `tuningChangerModule`, `tuningModule` | none |

- Their users: `composition` depends on `common`, `intonation`, `sc-midi` and `tuning`, and no longer on `tuner`. `rig`
  adds `tuning`, `tuning-changer` and, in `Test` scope, `tuning-changer-pedal`. `ui` adds `tuning`. `format` adds
  `tuning`, `tuning-changer` and `tuning-changer-pedal`: the design names only the last, which `format` no longer
  reaches through `tuner`; the other two follow the rule that a module declares every module it imports from. `app`
  imports only `TunerModule` from the package, so it doesn't change.
- Done when `composition` depends on `tuning` and `sc-midi`, and not on `tuner`. `tuning`, `tuning-changer` and
  `tuning-changer-pedal` don't reach `tuner` either: `tuner` and `tuning-changer` are siblings.
- `Tuning.scala`, `package.scala`, `TuningChange.scala` and their tests move unchanged. The changer files change only
  their `package` clause and imports, and their users in `rig` and `format` only imports and ScalaDoc links. The other
  source edit is to `TuningChange`'s ScalaDoc (Task 4), which can't link to the modules downstream of `tuning`.
- None of the moved tests uses `common-test-utils`, so no new module depends on it. `tuner` keeps it.
- Coverage floors are measured with each module's own tests, by Tasks 2 and 3: 80/80 for a module that reaches it,
  otherwise its measured coverage minus 3, rounded down to a whole percent, with a `// TODO #<n>` citing its coverage
  issue. `tuner` has no coverage issue, having met 80/80, so a `tuning` below it gets a new one. No step writes tests to
  raise coverage. A simulation at `3f74046`, running only the tests that move with each module, measured `tuning` at
  58.47% of statements and 52.17% of branches (floor 55/49), `tuning-changer` at 97.56% and 100%,
  `tuning-changer-pedal` at 100% and 100%, and the rest of `tuner` at 98.27% and 96.66%.
- The move changes no behaviour, so the existing suite is its test, and it keeps its 1475 tests. The red/green checks
  are the classpath check (`composition` reaches `tuner` today) and the ScalaDoc link warnings.
- Every commit message starts with `[#366/#375]` and ends with the attribution lines that your session's instructions
  give.
- Don't edit `issues/` (other than checking off this plan) or `docs/agent-evals/`. `docs/release-notes.md` is out of
  scope.
- Docs follow `docs/development/doc-conventions.md`, with lines of at most 120 columns.
- Work in this clone, on `refactoring/tuning-modules`, with `git switch`: no git worktree. The `microtonalist` and
  `microtonalist2` clones may each run a session and a development stack: never touch the other clone's processes.
  Don't push and don't open a PR.
- The plan deletes build output only with `sbtn "tuner/clean"`, which empties `tuner/tuner/target-bsp/`, and through
  the coverage scripts, which clean `target-scoverage/`.
- Shell notes: the Bash tool runs zsh, which doesn't word-split an unquoted `$VAR`, so module lists are spelled out.
  rtk rewrites `grep` to `rg` and `find` to a version without `-prune`, `-exec` or `-delete`, and filters `git status`,
  `git diff` and `ps`: use `grep -F` for literal text, and `rtk proxy <cmd>` for `find` and where the exact output
  matters. In zsh, quote a bare `====` (`echo '===='`).
- The link checker lists the files with `git ls-files`, so stage a new doc before counting links.
  `sbtn "<module>/doc"` writes `target-bsp/scala-*/api/`, which a `find` for stale build output also lists.
- `$SCRATCH` is your session's scratchpad directory.

## Review Focus

1. **Stale build output in `tuner`'s `target-bsp/`.** The SemanticDB files of a moved source can survive Zinc, and
   Metals then reports the old location, or `TuningChanger` in its old package. Task 2, step 5 and Task 3, step 7
   clean `tuner` and check what Metals reports.
2. **sbt or Metals on the old build definition.** A server that didn't reload `build.sbt` builds the old modules.
   Tasks 2 and 3 count the projects and the Metals build targets.
3. **The package that `tuning` and `tuner` share.** Agents derive a symbol's module from the *Packages* table of
   `module-overview.md` for the Metals symbol tools, which return nothing when given a module without the symbol, e.g.
   `tuning` for `Tuner`. Task 4, step 5 says which module holds what, and step 8 runs `get-usages` with the modules
   that the table gives.
4. **References that no compiler checks:** `rig`'s ScalaDoc links to `…tuner.TuningChanger`, `TuningChange`'s links to
   `Tuner` and `TuningChanger`, which `tuning` can't see, and docs that put `Tuning` or the tuning changers in `tuner`.
   Task 3, step 8 and Task 4, steps 2 and 10, against Task 1's baselines.
5. **A floor estimated rather than measured.** A module's coverage counts its own tests only, so a class loses the
   coverage that the tests staying behind gave it: `Tuning` keeps only `TuningTest`'s. A floor estimated per class, or
   left at 80/80, fails CI's `coverageCheck`. Tasks 2 and 3 measure each module.

---

### Task 1: Baseline

**Files:**
- Create (untracked): `$SCRATCH/check_md_links.py`, `$SCRATCH/doc-links-before.txt`

**Interfaces:**
- Produces: the link checker, run as `python3 "$SCRATCH/check_md_links.py" .` from the repository root; the ScalaDoc
  link warnings in `$SCRATCH/doc-links-before.txt`; the baseline numbers that Tasks 2–5 compare against.

- [ ] **Step 1: Check the starting point**

```bash
git switch refactoring/tuning-modules
rtk proxy git status --short
git log --oneline -2
bin/mtlist-dev-stack status; echo "exit=$?"
```

Expected: no output from `git status`; the log shows this plan's commit on top of `3f74046`; `exit=0`. If the stack
isn't running, follow `docs/agents/dev-stack.md`. Then run `mcp__metals__list-modules`, which lists 33 build targets,
and `mcp__metals__compile-full`: success, with only the two deprecation warnings on `TuningService.tunings` (in
`TuningServiceTest` and `TuningListFrame`).

- [ ] **Step 2: Save the link checker**

Step 1's plan holds a link checker that checks every relative Markdown link, `#heading` link and `@import` outside
`issues/` and `docs/agent-evals/`. Extract it:

```bash
awk '/^````python$/{f=1;next} /^````$/{if(f){exit}} f' \
  issues/00366-modularization/2026-10-16-367-grouping-folders-plan.md > "$SCRATCH/check_md_links.py"
wc -l < "$SCRATCH/check_md_links.py"
```

Expected: `90`.

- [ ] **Step 3: Record the docs baseline**

```bash
python3 "$SCRATCH/check_md_links.py" .
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py | wc -l
```

Expected: `0 broken of 247 relative link(s) and import(s)`; `21` docs.

- [ ] **Step 4: Record the classpath check (red)**

The *Done when* criterion as a command: the projects whose `Compile` classpath holds `tuner`.

```bash
sbtn "show Compile/internalDependencyClasspath" 2>&1 \
  | awk '/ \/ Compile \/ internalDependencyClasspath/ {project = $2} /\/tuner\/tuner\/target-bsp\// {print project}' \
  | sort | tr '\n' ' '; echo
```

Expected: `app composition format rig ui`.

- [ ] **Step 5: Record the ScalaDoc link warnings**

```bash
for m in tuner rig format composition ui; do
  sbtn "$m/doc" 2>&1 | grep -F 'link query: ' | sed -E "s/.*(link query: [^ ]+).*/$m: \1/"
done | sort > "$SCRATCH/doc-links-before.txt"
cut -d: -f1 "$SCRATCH/doc-links-before.txt" | uniq -c
```

Expected: `4 composition`, `45 format` and `5 rig`, none from `tuner` or `ui`. No step fixes these 54; the checks look
only at the warnings that the move adds.

- [ ] **Step 6: Record the test counts**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 \
  | awk '/Total number of tests run/ {n += $NF; m++} /FAILED|\[error\]/ {print} END {print m " modules, " n " tests"}'
```

Expected: 540 tests in 14 suites, of which `TuningTest` has 7, `TuningChangeTest` 4, `TuningChangeTriggersTest` 7 and
`PedalTuningChangerTest` 22; `11 modules, 1475 tests` and no other line. (At `3f74046`, `composition` has 55 tests in 8
suites, `rig` 70 in 7, `format` 218 in 23, and `ui` none.)

- [ ] **Step 7: Record the statements that put the moved types in `tuner`**

```bash
git grep -nF -e '(`Tuner`, `TuningChanger`)' -e 'Depends on `tuner`** for' -e 'Its only direct dependency is `tuner`' \
  -e 'detects tuning-change triggers' -e '`tuner`-module types' -e '`tuner` (the tuner and tuning-changer' \
  -e '`format`, `tuner`, and `ui` reach it' -e '`Tuner` and `TuningChanger` are plugins' \
  -e 'composition → { intonation, tuner }' -- ':!issues/' ':!docs/agent-evals/' ':!docs/release-notes.md'
```

Expected: 9 lines, in the docs of `common`, `format`, `composition`, `intonation`, `tuner` (2), `ui` (2) and `app`.
Task 4 fixes them all.

---

### Task 2: Move `Tuning` and `TuningChange` into a `tuning` module

**Files:**
- Move (`git mv`): `Tuning.scala`, `TuningChange.scala` and `package.scala` from
  `tuner/tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/` to
  `tuner/tuning/src/main/scala/org/calinburloiu/music/microtonalist/tuner/`
- Move (`git mv`): `TuningTest.scala` and `TuningChangeTest.scala` from
  `tuner/tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/` to
  `tuner/tuning/src/test/scala/org/calinburloiu/music/microtonalist/tuner/`
- Modify: `build.sbt` (`root`'s `aggregate`; the `dependsOn` of `compositionModule`, `tunerModule`, `rigModule`,
  `formatModule` and `uiModule`; a new `tuningModule`)

**Interfaces:**
- Consumes: Task 1's baseline.
- Produces: the sbt project `tuning` (`lazy val tuningModule`), whose Metals build targets are `tuning` and
  `tuning-test`; `Tuning`, `DefaultCentsTolerance` and the `TuningChange` ADT, unchanged, in it; `tuning`'s coverage
  floor and, if it is below 80/80, its coverage issue.

- [ ] **Step 1: Move the files**

```bash
T=org/calinburloiu/music/microtonalist/tuner
mkdir -p tuner/tuning/src/main/scala/$T tuner/tuning/src/test/scala/$T
git mv tuner/tuner/src/main/scala/$T/Tuning.scala tuner/tuner/src/main/scala/$T/TuningChange.scala \
  tuner/tuner/src/main/scala/$T/package.scala tuner/tuning/src/main/scala/$T/
git mv tuner/tuner/src/test/scala/$T/TuningTest.scala tuner/tuner/src/test/scala/$T/TuningChangeTest.scala \
  tuner/tuning/src/test/scala/$T/
rtk proxy git diff --cached -M --name-status | cut -f1 | sort | uniq -c
```

Expected: `5 R100`.

- [ ] **Step 2: Declare the module in `build.sbt`**

In `root`'s `aggregate`, add `tuningModule,` after `tunerModule,`.

In `compositionModule`, replace:

```scala
  .dependsOn(
    intonationModule,
    tunerModule,
  )
```

with:

```scala
  .dependsOn(
    commonModule,
    intonationModule,
    scMidiModule,
    tuningModule,
  )
```

In the `dependsOn` of `rigModule`, `formatModule` and `uiModule`, add `tuningModule,` after `tunerModule,`; in that of
`tunerModule`, after `scMidiModule,`.

After `tunerModule`'s definition, add:

```scala
lazy val tuningModule = (project in file("tuner/tuning"))
  .withId("tuning")
  .dependsOn(
    scMidiModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuning",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    coverageSettings(stmt = 55, branch = 49),
  )
```

The floor is the simulation's; step 8 measures it. Then:

```bash
grep -nF 'tuningModule' build.sbt
```

Expected: 7 lines: `root`'s `aggregate`, `tuningModule`'s own `lazy val`, and the `dependsOn` of `uiModule`,
`compositionModule`, `tunerModule`, `rigModule` and `formatModule`.

- [ ] **Step 3: Refresh the build**

Run `mcp__metals__import-build`, which reloads the build on the stack's sbt server too. Then:

```bash
sbtn projects
sbtn 'show tuning/target'
```

Expected: 17 projects, the 16 of before and `tuning`; a path ending in `/tuner/tuning/target-bsp`. If `tuning` is
missing, run `sbtn reload` and check again.

- [ ] **Step 4: Check that Metals sees the module**

Run `mcp__metals__list-modules`. Expected: 35 build targets, the 33 of before and `tuning` and `tuning-test`. If they
are missing, restart the stack with `bin/mtlist-dev-stack restart`; if the Metals MCP tools then stop answering, ask
the user to reconnect them with `/mcp`.

- [ ] **Step 5: Clean `tuner` and compile**

Zinc deletes the class files of the moved sources, but not always their SemanticDB files. Cleaning `tuner` removes
both:

```bash
sbtn "tuner/clean"
```

Run `mcp__metals__compile-full`. Expected: success, with only the two deprecation warnings of Task 1. A compile error
in `composition`, `rig`, `format` or `ui` means a missing `dependsOn`. Then:

```bash
rtk proxy find tuner/tuner/target-bsp -name 'Tuning.*' -o -name 'TuningChange.*' -o -name 'package.*' \
  -o -name 'TuningTest*' -o -name 'TuningChangeTest*'
rtk proxy find tuner/tuning/target-bsp -name 'Tuning.class'
```

Expected: nothing from the first; one `classes/…/tuner/Tuning.class` from the second.

Then run `mcp__metals__get-usages` with `fqcn = "org.calinburloiu.music.microtonalist.tuner.IndexTuningChange"` and
`module = "tuning"`. Expected: usages under `tuner/tuning/src/`, among them `TuningChange.scala` and
`TuningChangeTest.scala`, and in `tuner`, `rig` and `ui`; none in a file that has moved, i.e.
`tuner/tuner/src/main/…/TuningChange.scala` or `tuner/tuner/src/test/…/TuningChangeTest.scala`.

- [ ] **Step 6: Run the classpath check (green)**

```bash
sbtn "show Compile/internalDependencyClasspath" 2>&1 \
  | awk '/ \/ Compile \/ internalDependencyClasspath/ {project = $2} /\/tuner\/tuner\/target-bsp\// {print project}' \
  | sort | tr '\n' ' '; echo
sbtn "show composition/Compile/internalDependencyClasspath" 2>&1 \
  | grep -oE '[a-z]+/[a-z-]+/target-bsp/' | sed 's#/target-bsp/##' | sort -u | tr '\n' ' '; echo
```

Expected: `app format rig ui`; `base/businessync base/common midi/sc-midi theory/intonation tuner/tuning`.

- [ ] **Step 7: Run the tests of the modules involved, then the full suite**

```bash
sbtn "tuning/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "composition/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "rig/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 \
  | awk '/Total number of tests run/ {n += $NF; m++} /FAILED|\[error\]/ {print} END {print m " modules, " n " tests"}'
```

Expected: 11 tests in 2 suites; 529 in 12; 55 in 8; 70 in 7; 218 in 23; `12 modules, 1475 tests` and no other line.

- [ ] **Step 8: Measure the coverage floors**

Invoke the `scoverage-inspector` skill. The coverage scripts don't work through `sbtn`, and take a few minutes:

```bash
bin/mtlist-coverage-modules tuning tuner
bin/mtlist-scoverage-inspector module-summary tuning --overall-only
bin/mtlist-scoverage-inspector module-summary tuner --overall-only
```

Expected: both modules pass their floors; `tuning` near 58.47% of statements and 52.17% of branches; `tuner` above 80%
for both, near 98% and 97%. Then apply the rule of the Global Constraints:

- If `tuning`'s floor isn't its measured coverage minus 3, rounded down, for either number, correct it in `build.sbt`
  and rerun the first command.
- If `tuning` reaches 80% for both, set `coverageSettings(stmt = 80, branch = 80)` and skip step 9.
- If `tuner` falls below 80% for either, stop and report it: the simulation didn't predict that.

- [ ] **Step 9: Open `tuning`'s coverage issue**

Modelled on `format`'s, #179, with step 8's numbers for `<stmt>` and `<branch>`:

```bash
.claude/skills/contributing/scripts/microtonalist-gh issue 'Improve test coverage for `tuning` module to 80%' \
  "$(cat <<'EOF'
The `tuning` module, split out of `tuner` by #375, is below the 80% coverage target: its own tests cover less of `Tuning` than the tests of `tuner` did.

**Current coverage:**
- Statement: <stmt>%
- Branch: <branch>%

**Target:** 80% statement and branch coverage.

The configured threshold is set to current coverage minus a 3% buffer. As coverage improves, the threshold in `build.sbt` should be raised toward 80%.
EOF
)" --label refactoring
```

Expected: the URL of the new issue, `#<n>`. In `tuningModule`, add above `coverageSettings`:

```scala
    // TODO #<n> Raise toward 80% statement and branch coverage.
```

- [ ] **Step 10: Commit**

```bash
git add build.sbt
git commit -m "[#366/#375] Move Tuning and TuningChange into a tuning module" -m "<attribution lines>"
rtk proxy git show -M --format= --name-status HEAD | cut -f1 | sort | uniq -c
```

Expected: `1 M` (`build.sbt`) and `5 R100`, so `git log --follow` keeps each file's history.

---

### Task 3: Move the tuning changers into `tuning-changer` and `tuning-changer-pedal`

**Files** (`$T` is `org/calinburloiu/music/microtonalist/tuner`):
- Move (`git mv`), then edit the `package` clause and imports:
  - `tuner/tuner/src/main/scala/$T/TuningChanger.scala` and `TuningChangeTriggers.scala` to
    `tuner/tuning-changer/src/main/scala/$T/changer/`
  - `tuner/tuner/src/test/scala/$T/TuningChangeTriggersTest.scala` to `tuner/tuning-changer/src/test/scala/$T/changer/`
  - `tuner/tuner/src/main/scala/$T/PedalTuningChanger.scala` to
    `tuner/tuning-changer-pedal/src/main/scala/$T/changer/pedal/`
  - `tuner/tuner/src/test/scala/$T/PedalTuningChangerTest.scala` to
    `tuner/tuning-changer-pedal/src/test/scala/$T/changer/pedal/`
- Modify (imports), under `rig/rig/src/{main,test}/scala/org/calinburloiu/music/microtonalist/rig/`: `RigSpec.scala`,
  `TuningChangeProcessor.scala`, `TrackTest.scala`, `TuningChangeProcessorTest.scala`, `RigModuleIntegrationTest.scala`
- Modify (imports), under `format/format/src/{main,test}/scala/org/calinburloiu/music/microtonalist/format/`:
  `JsonRigFormat.scala`, `JsonTuningChangerPluginFormat.scala`, `JsonRigFormatTest.scala`,
  `JsonTuningChangerPluginFormatTest.scala`
- Modify (ScalaDoc links): `rig/rig/src/main/scala/org/calinburloiu/music/microtonalist/rig/RigSpec.scala:29`,
  `Track.scala:46`, `TuningChangeProcessor.scala:30,33,53,83`
- Modify: `build.sbt` (`root`'s `aggregate`, `rigModule`, `formatModule`; new `tuningChangerModule` and
  `tuningChangerPedalModule`)

**Interfaces:**
- Consumes: Task 2's `tuningModule`; Task 1's `$SCRATCH/doc-links-before.txt`.
- Produces: `org.calinburloiu.music.microtonalist.tuner.changer.{TuningChanger, TuningChangeTriggers}` in the sbt
  project `tuning-changer` (`lazy val tuningChangerModule`);
  `org.calinburloiu.music.microtonalist.tuner.changer.pedal.PedalTuningChanger`, with its `CcNumber` type, in
  `tuning-changer-pedal` (`lazy val tuningChangerPedalModule`); the Metals build targets `tuning-changer`,
  `tuning-changer-test`, `tuning-changer-pedal` and `tuning-changer-pedal-test`.

- [ ] **Step 1: Move the files**

```bash
T=org/calinburloiu/music/microtonalist/tuner
C=tuner/tuning-changer/src
P=tuner/tuning-changer-pedal/src
mkdir -p $C/main/scala/$T/changer $C/test/scala/$T/changer $P/main/scala/$T/changer/pedal \
  $P/test/scala/$T/changer/pedal
git mv tuner/tuner/src/main/scala/$T/TuningChanger.scala tuner/tuner/src/main/scala/$T/TuningChangeTriggers.scala \
  $C/main/scala/$T/changer/
git mv tuner/tuner/src/test/scala/$T/TuningChangeTriggersTest.scala $C/test/scala/$T/changer/
git mv tuner/tuner/src/main/scala/$T/PedalTuningChanger.scala $P/main/scala/$T/changer/pedal/
git mv tuner/tuner/src/test/scala/$T/PedalTuningChangerTest.scala $P/test/scala/$T/changer/pedal/
rtk proxy git diff --cached -M --name-status | cut -f1 | sort | uniq -c
```

Expected: `5 R100`.

- [ ] **Step 2: Give the moved files their packages and imports**

As in the design, `…tuner` abbreviates `org.calinburloiu.music.microtonalist.tuner`, which the code spells out.
`sbtn fixLint` (Task 5) orders the imports.

| File | `package` | Imports |
| ---- | --------- | ------- |
| `TuningChanger.scala` | `…tuner.changer` | add `…tuner.TuningChange` |
| `TuningChangeTriggers.scala` | `…tuner.changer` | add `…tuner.{IndexTuningChange, NextTuningChange, NoTuningChange, PreviousTuningChange, TuningChange}` |
| `TuningChangeTriggersTest.scala` | `…tuner.changer` | add `…tuner.{IndexTuningChange, NextTuningChange, NoTuningChange, PreviousTuningChange}` |
| `PedalTuningChanger.scala` | `…tuner.changer.pedal` | `…tuner.PedalTuningChanger.CcNumber` becomes `…tuner.changer.pedal.PedalTuningChanger.CcNumber`; add `…tuner.changer.{TuningChangeTriggers, TuningChanger}` and `…tuner.{MayTriggerTuningChange, NoTuningChange, TuningChange}` |
| `PedalTuningChangerTest.scala` | `…tuner.changer.pedal` | the same `CcNumber` change; add `…tuner.changer.{TuningChangeTriggers, TuningChanger}` and `…tuner.{IndexTuningChange, MayTriggerTuningChange, NextTuningChange, NoTuningChange, PreviousTuningChange, TuningChange}` |

- [ ] **Step 3: Point the users at the new packages**

| File | Change |
| ---- | ------ |
| `RigSpec.scala`, `JsonRigFormat.scala` | `…tuner.{Tuner, TuningChanger}` becomes `…tuner.Tuner` and `…tuner.changer.TuningChanger` |
| `TuningChangeProcessor.scala`, `TrackTest.scala`, `TuningChangeProcessorTest.scala` | drop `TuningChanger` from the `…tuner.{…}` import; add `…tuner.changer.TuningChanger` |
| `RigModuleIntegrationTest.scala` | drop `PedalTuningChanger` from the `…tuner.{…}` import; add `…tuner.changer.pedal.PedalTuningChanger` |
| `JsonTuningChangerPluginFormat.scala`, `JsonTuningChangerPluginFormatTest.scala` | `…tuner.PedalTuningChanger.CcNumber` and `…tuner.{PedalTuningChanger, TuningChangeTriggers, TuningChanger}` become `…tuner.changer.pedal.PedalTuningChanger`, `…tuner.changer.pedal.PedalTuningChanger.CcNumber` and `…tuner.changer.{TuningChangeTriggers, TuningChanger}` |
| `JsonRigFormatTest.scala` | keep `…tuner.*`; add `…tuner.changer.pedal.PedalTuningChanger` |

- [ ] **Step 4: Declare the modules in `build.sbt`**

In `root`'s `aggregate`, add `tuningChangerModule,` and `tuningChangerPedalModule,` after `tuningModule,`. In
`rigModule`'s `dependsOn`, add `tuningChangerModule,` and `tuningChangerPedalModule % Test,` after `tuningModule,`; in
`formatModule`'s, `tuningChangerModule,` and `tuningChangerPedalModule,`.

After `tuningModule`'s definition, add:

```scala
lazy val tuningChangerModule = (project in file("tuner/tuning-changer"))
  .withId("tuning-changer")
  .dependsOn(
    commonModule,
    scMidiModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuning-changer",
    commonSettings,
    libraryDependencies ++= Seq(
      jsr305,
    ),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tuningChangerPedalModule = (project in file("tuner/tuning-changer-pedal"))
  .withId("tuning-changer-pedal")
  .dependsOn(
    scMidiModule,
    tuningChangerModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuning-changer-pedal",
    commonSettings,
    coverageSettings(stmt = 80, branch = 80),
  )
```

Then:

```bash
grep -nE 'tuningChanger(Pedal)?Module' build.sbt
```

Expected: `tuningChangerModule` in `root`'s `aggregate`, in its own `lazy val`, in `tuningChangerPedalModule`'s
`dependsOn` and in those of `rigModule` and `formatModule`; `tuningChangerPedalModule` in `root`'s `aggregate`, in its
own `lazy val`, in `rigModule`'s `dependsOn` with `% Test` and in `formatModule`'s without.

- [ ] **Step 5: Refresh the build**

Run `mcp__metals__import-build`. Then:

```bash
sbtn projects
sbtn 'show tuning-changer-pedal/target'
```

Expected: 19 projects, the 17 of before and `tuning-changer` and `tuning-changer-pedal`; a path ending in
`/tuner/tuning-changer-pedal/target-bsp`. If either is missing, run `sbtn reload` and check again.

- [ ] **Step 6: Check that Metals sees the modules**

Run `mcp__metals__list-modules`. Expected: 39 build targets, the 35 of before and `tuning-changer`,
`tuning-changer-test`, `tuning-changer-pedal` and `tuning-changer-pedal-test`. If they are missing, act as in Task 2,
step 4.

- [ ] **Step 7: Clean `tuner` and compile**

```bash
sbtn "tuner/clean"
```

Run `mcp__metals__compile-full`. Expected: success, with only the two deprecation warnings of Task 1. A `Not found`
error names a type that step 2 or 3 missed; an error that a package doesn't exist, a missing `dependsOn`. Then:

```bash
rtk proxy find tuner/tuner/target-bsp -name '*TuningChanger*' -o -name 'TuningChangeTriggers*'
```

Expected: nothing (the pattern `*TuningChanger*` catches `PedalTuningChanger` too). Then run `mcp__metals__get-usages`
with `fqcn = "org.calinburloiu.music.microtonalist.tuner.changer.TuningChanger"` and `module = "tuning-changer"`.
Expected: usages under `tuner/tuning-changer/src/`, `tuner/tuning-changer-pedal/src/`, `rig/rig/src/` and
`format/format/src/`, and none under `tuner/tuner/`.

- [ ] **Step 8: Fix `rig`'s ScalaDoc links (red, then green)**

Collect the link warnings of the modules involved, and compare them with Task 1's:

```bash
for m in tuner tuning tuning-changer tuning-changer-pedal rig format composition ui; do
  sbtn "$m/doc" 2>&1 | grep -F 'link query: ' | sed -E "s/.*(link query: [^ ]+).*/$m: \1/"
done | sort > "$SCRATCH/doc-links-after.txt"
comm -13 "$SCRATCH/doc-links-before.txt" "$SCRATCH/doc-links-after.txt"
comm -23 "$SCRATCH/doc-links-before.txt" "$SCRATCH/doc-links-after.txt"
```

Expected from the first `comm`, the warnings that the move added: lines
`rig: link query: org.calinburloiu.music.microtonalist.tuner.TuningChanger`, up to six, and `tuning:` lines naming
`Tuner` and `TuningChanger`, which Task 4 fixes. Nothing from the second.

Point `rig`'s links at the new package:

```bash
perl -pi -e 's/microtonalist\.tuner\.TuningChanger\]\]/microtonalist.tuner.changer.TuningChanger]]/g' \
  rig/rig/src/main/scala/org/calinburloiu/music/microtonalist/rig/RigSpec.scala \
  rig/rig/src/main/scala/org/calinburloiu/music/microtonalist/rig/Track.scala \
  rig/rig/src/main/scala/org/calinburloiu/music/microtonalist/rig/TuningChangeProcessor.scala
```

The longer links may push a ScalaDoc line past 120 columns; `sbtn fixLint` (Task 5) refills the paragraph. Rerun the
loop and the two `comm`s. Expected: only the `tuning:` lines from the first, nothing from the second. A line about a
short link in a moved file, e.g. `tuning-changer: link query: TuningChange`, means that scaladoc didn't find the
`…tuner` type from the subpackage: write that link fully qualified and rerun.

- [ ] **Step 9: Run the classpath check**

```bash
sbtn "show Compile/internalDependencyClasspath" 2>&1 \
  | awk '/ \/ Compile \/ internalDependencyClasspath/ {project = $2} /\/tuner\/tuner\/target-bsp\// {print project}' \
  | sort | tr '\n' ' '; echo
for m in composition tuning tuning-changer tuning-changer-pedal; do
  printf '%s: ' "$m"
  sbtn "show $m/Compile/internalDependencyClasspath" 2>&1 \
    | grep -oE '[a-z]+/[a-z-]+/target-bsp/' | sed 's#/target-bsp/##' | sort -u | tr '\n' ' '; echo
done
```

Expected:

```text
app format rig ui
composition: base/businessync base/common midi/sc-midi theory/intonation tuner/tuning
tuning: base/businessync base/common midi/sc-midi
tuning-changer: base/businessync base/common midi/sc-midi tuner/tuning
tuning-changer-pedal: base/businessync base/common midi/sc-midi tuner/tuning tuner/tuning-changer
```

- [ ] **Step 10: Run the tests of the modules involved, then the full suite**

```bash
sbtn "tuning-changer/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuning-changer-pedal/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "rig/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 \
  | awk '/Total number of tests run/ {n += $NF; m++} /FAILED|\[error\]/ {print} END {print m " modules, " n " tests"}'
```

Expected: 7 tests in 1 suite; 22 in 1; 500 in 10; 70 in 7; 218 in 23; `14 modules, 1475 tests` and no other line.

- [ ] **Step 11: Measure the coverage floors**

```bash
bin/mtlist-coverage-modules tuning-changer tuning-changer-pedal tuner
bin/mtlist-scoverage-inspector module-summary tuning-changer --overall-only
bin/mtlist-scoverage-inspector module-summary tuning-changer-pedal --overall-only
bin/mtlist-scoverage-inspector module-summary tuner --overall-only
```

Expected: the three pass 80/80: `tuning-changer` near 97.56% and 100% (it misses `TuningChanger.familyName`, which
only `PedalTuningChangerTest` covers), `tuning-changer-pedal` at 100% and 100%, `tuner` near 98.27% and 96.66%. A
module below 80% for either gets the floor of the Global Constraints, under a new coverage issue opened as in Task 2,
step 9.

- [ ] **Step 12: Commit**

```bash
git add build.sbt tuner rig format
rtk proxy git status --short
git commit -m "[#366/#375] Move the tuning changers into tuning-changer and tuning-changer-pedal" \
  -m "<attribution lines>"
rtk proxy git show -M --format= --name-status HEAD | cut -f1 | sort | uniq -c
```

Expected before the commit: only the files of this task's **Files** are staged. After it: `11 M` (`build.sbt`, six
`rig` files and four `format` files) and five renames, each `R` with a similarity of `090` or more.

---

### Task 4: Document the modules

**Files:**
- Modify (ScalaDoc only): `tuner/tuning/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TuningChange.scala`
- Create: `README.md`, `CLAUDE.md` and `docs/ARCHITECTURE.md` in each of `tuner/tuning/`, `tuner/tuning-changer/` and
  `tuner/tuning-changer-pedal/`
- Modify: `tuner/tuner/docs/ARCHITECTURE.md`
- Modify: `docs/architecture/module-overview.md`, `docs/architecture/README.md`, `docs/architecture/domain-concepts.md`
- Modify: `app/app/docs/ARCHITECTURE.md`, `base/common/docs/ARCHITECTURE.md`, `format/format/docs/ARCHITECTURE.md`,
  `rig/rig/docs/ARCHITECTURE.md`, `theory/composition/docs/ARCHITECTURE.md`, `theory/intonation/docs/ARCHITECTURE.md`,
  `ui/ui/docs/ARCHITECTURE.md`

`data-flow.md` doesn't change: no stage of it changes module. `tuner`'s `mpe-tuner.md` and its paper stay until step 4.

**Interfaces:**
- Consumes: Tasks 2 and 3's modules; Task 1's link checker and baselines.
- Produces: docs whose links resolve and which put each type in its module.

- [ ] **Step 1: Invoke the `architecture-docs` skill**

`AGENTS.md` asks for it when a change adds a module. Its maintenance checklist applies to `tuner`'s doc, which splits.

- [ ] **Step 2: Unlink the types downstream of `tuning` (green)**

In `TuningChange.scala`, replace:

```scala
/**
 * An object describing an operation that controls to which [[Tuning]] from the tuning sequence should the [[Tuner]]
 * tune.
 *
 * @see [[TuningChanger]] which returns such an object as a decision based on the input MIDI received.
 */
```

with:

```scala
/**
 * An object describing an operation that controls to which [[Tuning]] from the tuning sequence should the `Tuner`
 * tune.
 *
 * @see `TuningChanger`, in the `tuning-changer` module, which returns such an object as a decision based on the input
 *   MIDI received.
 */
```

and replace:

```scala
 * operation via [[TuningChanger.decide]] it part of a series/pattern that may eventually trigger an effective tuning
```

with:

```scala
 * operation via `TuningChanger.decide` is part of a series/pattern that may eventually trigger an effective tuning
```

Rerun the loop and the two `comm`s of Task 3, step 8. Expected: nothing from either. Then run
`mcp__metals__compile-module` with `module = "tuning"`: success.

- [ ] **Step 3: Create the new modules' docs**

For each `<id>` of `tuning`, `tuning-changer` and `tuning-changer-pedal`, `tuner/<id>/README.md`:

```markdown
# `<id>` module

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module's architecture.
```

and `tuner/<id>/CLAUDE.md`:

```markdown
# `<id>` module

@docs/ARCHITECTURE.md
```

`tuner/tuning/docs/ARCHITECTURE.md`:

```markdown
# `tuning` module architecture

## Responsibility

`tuning` holds the values that the tuning engine shares with its clients: the `Tuning` that `composition` builds and a
tuner applies, and the `TuningChange` that a tuning changer decides and `TuningService` carries out. It has no MIDI
protocol, thread or plugin of its own, so that `composition` and `ui` use these values without depending on the engine
of [`tuner`](../../tuner/docs/ARCHITECTURE.md), whose package it shares.

## Key types

- **`Tuning`** — 12 optional cent offsets, one per pitch class; `Tuning.Standard` is 12-EDO. `DefaultCentsTolerance`,
  in the package object, is the tolerance for comparing cent values.
- **`TuningChange`** — a decision about the tuning: effective (`PreviousTuningChange`, `NextTuningChange`,
  `IndexTuningChange`) or ineffective (`NoTuningChange`, `MayTriggerTuningChange`). The
  [tuning changers](../../tuning-changer/docs/ARCHITECTURE.md) return it, and `TuningService.changeTuning` applies the
  effective ones.

## Subject to change

- A `Tuning` will keep the lineage of its scales, which will make `tuning` depend on `intonation` (#34).
```

`tuner/tuning-changer/docs/ARCHITECTURE.md`:

```markdown
# `tuning-changer` module architecture

## Responsibility

`tuning-changer` holds the contract of the tuning changers: the plugins that find, in a track's MIDI messages, when to
change the tuning, e.g. when a pedal is pressed. It sits beside [`tuner`](../../tuner/docs/ARCHITECTURE.md), not on
it: a tuning changer returns a [`TuningChange`](../../tuning/docs/ARCHITECTURE.md), which `rig`'s
`TuningChangeProcessor` hands to `TuningService` (see `rig`'s
[tuning-change flow](../../../rig/rig/docs/ARCHITECTURE.md#tuning-change-flow)).

Each implementation has a module of its own, in a subpackage of `org.calinburloiu.music.microtonalist.tuner.changer`;
[`tuning-changer-pedal`](../../tuning-changer-pedal/docs/ARCHITECTURE.md) is the only one so far.

## Key types

- **`TuningChanger`** — the plugin, of the `tuningChanger` family, which `format` (de)serializes by its `typeName`. It
  decides a `TuningChange` for each MIDI message, and whether the messages that trigger a change reach the output.
- **`TuningChangeTriggers`** — the triggers, e.g. Control Change numbers, that select the previous, the next or a given
  tuning; an implementation looks up the `TuningChange` of the trigger it detects.

## Threading

Tuning changers are `@NotThreadSafe`: a track runs MIDI messages through them on the input device's callback thread,
and resets them on the business thread (see [`rig`](../../../rig/rig/docs/ARCHITECTURE.md#threading)).
```

`tuner/tuning-changer-pedal/docs/ARCHITECTURE.md`:

```markdown
# `tuning-changer-pedal` module architecture

## Responsibility

`tuning-changer-pedal` holds `PedalTuningChanger`, the [tuning changer](../../tuning-changer/docs/ARCHITECTURE.md) of
type `pedal`, which changes the tuning when a pedal is pressed. It suits the pedals that, like a piano's, return to
their initial position once released.

## Key types

- **`PedalTuningChanger`** — triggers a change when the value of a trigger's Control Change rises above a threshold,
  and again only after it has fallen back to it, so that a pedal held down changes the tuning once. Its triggers are
  the controller numbers 0–119, MIDI 1.0 reserving 120–127 for the Channel Mode messages.
```

- [ ] **Step 4: Split `tuner`'s `ARCHITECTURE.md`**

In `tuner/tuner/docs/ARCHITECTURE.md`, replace the body of *Responsibility*:

```markdown
`tuner` is the low-level MIDI tuning engine. It turns a `Tuning` into the MIDI messages an output instrument needs to
play microtonally, detects tuning-change triggers in the MIDI messages, and holds the runtime tuning state behind a
thread-safe service.

It doesn't decide which tunings exist: `composition` builds them and hands over a `Seq[Tuning]`.
```

with:

```markdown
`tuner` is the low-level MIDI tuning engine. It turns a `Tuning` into the MIDI messages an output instrument needs to
play microtonally, and holds the runtime tuning state behind a thread-safe service.

It doesn't decide which tunings exist: `composition` builds them and hands over a `Seq[Tuning]`. The values it works
with, `Tuning` and `TuningChange`, are in [`tuning`](../../tuning/docs/ARCHITECTURE.md), with which it shares its
package. Finding when to change the tuning is the job of the
[tuning changers](../../tuning-changer/docs/ARCHITECTURE.md).
```

In *Key types*, replace:

```markdown
`Tuner` and `TuningChanger` are plugins, which `format` (de)serializes by their `familyName` and `typeName`.

**`Tuning`** holds 12 optional cent offsets, one per pitch class; `Tuning.Standard` is 12-EDO.
```

with:

```markdown
`Tuner` is a plugin, which `format` (de)serializes by its `familyName` and `typeName`.
```

delete the paragraph:

```markdown
**Tuning-change detection.** A `TuningChanger` inspects each message and returns a `TuningChange`: effective (previous,
next or by index) or ineffective. `PedalTuningChanger` triggers on a pedal-like Control Change crossing a threshold.
```

and replace:

```markdown
every change, which the rig and the UI follow. It runs on the business thread, behind the thread-safe
`TuningService`. `TunerModule` wires the two.
```

with:

```markdown
every change, which the rig and the UI follow. It runs on the business thread, behind the thread-safe
`TuningService`, whose `changeTuning` applies a `TuningChange`. `TunerModule` wires the two.
```

In *Threading*, replace:

```markdown
[`businessync`](../../../base/businessync/docs/ARCHITECTURE.md)); other threads go through `TuningService`. The tuners
and the tuning changers are `@NotThreadSafe` too: a track runs MIDI messages through them on the input device's
callback thread, and tunes and resets them on the business thread (see
[`rig`](../../../rig/rig/docs/ARCHITECTURE.md#threading)).
```

with:

```markdown
[`businessync`](../../../base/businessync/docs/ARCHITECTURE.md)); other threads go through `TuningService`. The tuners
are `@NotThreadSafe` too: a track runs MIDI messages through them on the input device's callback thread, and tunes and
resets them on the business thread (see [`rig`](../../../rig/rig/docs/ARCHITECTURE.md#threading)).
```

In *Subject to change*, add a last line:

```markdown
- The tuners will move to modules of their own: `tuner-mpe`, `tuner-mts` and `tuner-mpb` (#366).
```

Then, as the skill's checklist asks, run `mcp__metals__glob-search` for each type that the four docs name and check
that it exists: `Tuning`, `DefaultCentsTolerance`, `TuningChange`, `PreviousTuningChange`, `NextTuningChange`,
`IndexTuningChange`, `NoTuningChange`, `MayTriggerTuningChange`, `Tuner`, `MpeTuner`, `MonophonicPitchBendTuner`,
`TuningSession`, `TuningService`, `TuningEvent`, `TunerModule`, `TuningChanger`, `TuningChangeTriggers`,
`PedalTuningChanger`, `TuningChangeProcessor`.

- [ ] **Step 5: Add the modules to the cross-cutting docs**

In `docs/architecture/module-overview.md`, replace the tree:

```text
app
├── ui               (GUI, depends on tuner)
├── composition      (domain model, depends on intonation + tuner)
├── format           (JSON/file I/O, depends on composition + rig + tuner)
├── rig              (setup of per-instrument MIDI tracks, depends on tuner)
├── tuner            (MIDI tuning, depends on sc-midi + businessync)
├── sc-midi-javamidi (Java Sound implementation of sc-midi, with CoreMIDI4J, depends on sc-midi + businessync)
├── sc-midi          (Scala-idiomatic MIDI API, depends on businessync)
├── intonation       (interval math, no application deps)
├── businessync      (event bus + threading, no application deps)
├── common           (shared utilities)
└── config           (HOCON config, depends on common)
```

with:

```text
app
├── ui                   (GUI, depends on tuner + tuning)
├── composition          (domain model, depends on intonation + tuning)
├── format               (JSON/file I/O, depends on composition + rig + tuner + tuning-changer-pedal)
├── rig                  (setup of per-instrument MIDI tracks, depends on tuner + tuning-changer)
├── tuning-changer-pedal (pedal tuning changer, depends on tuning-changer)
├── tuning-changer       (contract of the tuning changers, depends on tuning + sc-midi)
├── tuner                (MIDI tuning, depends on tuning + sc-midi + businessync)
├── tuning               (Tuning and TuningChange values, depends on sc-midi)
├── sc-midi-javamidi     (Java Sound implementation of sc-midi, with CoreMIDI4J, depends on sc-midi + businessync)
├── sc-midi              (Scala-idiomatic MIDI API, depends on businessync)
├── intonation           (interval math, no application deps)
├── businessync          (event bus + threading, no application deps)
├── common               (shared utilities)
└── config               (HOCON config, depends on common)
```

In its *Packages* table, add after the `tuner` row:

```text
| `tuning` | `tuner/tuning` | `org.calinburloiu.music.microtonalist.tuner` |
| `tuning-changer` | `tuner/tuning-changer` | `org.calinburloiu.music.microtonalist.tuner.changer` |
| `tuning-changer-pedal` | `tuner/tuning-changer-pedal` | `org.calinburloiu.music.microtonalist.tuner.changer.pedal` |
```

and, after the table:

```markdown

`tuning` and `tuner` share the `org.calinburloiu.music.microtonalist.tuner` package: `tuning` holds `Tuning`,
`DefaultCentsTolerance` and the `TuningChange` ADT, and `tuner` the rest of the package.
```

In `docs/architecture/README.md`, add after the `tuner` row of the *Module index*:

```text
| `tuning` | [tuner/tuning/docs/ARCHITECTURE.md](../../tuner/tuning/docs/ARCHITECTURE.md) |
| `tuning-changer` | [tuner/tuning-changer/docs/ARCHITECTURE.md](../../tuner/tuning-changer/docs/ARCHITECTURE.md) |
| `tuning-changer-pedal` | [tuner/tuning-changer-pedal/docs/ARCHITECTURE.md](../../tuner/tuning-changer-pedal/docs/ARCHITECTURE.md) |
```

In `docs/architecture/domain-concepts.md`, replace the *Tuner* group:

```markdown
**Tuner (`tuner` module, `org.calinburloiu.music.microtonalist.tuner`):**

- `Tuning` — 12 optional cent offsets for pitch classes; `Tuning.Standard` is 12-EDO
- `Tuner` (trait, Plugin) — processes MIDI messages: `reset()`, `tune(tuning)`, `process(message)`; keeps the current
```

with:

```markdown
**Tuning (`tuning` module, `org.calinburloiu.music.microtonalist.tuner`):**

- `Tuning` — 12 optional cent offsets for pitch classes; `Tuning.Standard` is 12-EDO
- `TuningChange` (sealed trait) — a decision to change the tuning, to the previous, the next or a given index, or not
  to change it

**Tuner (`tuner` module, `org.calinburloiu.music.microtonalist.tuner`):**

- `Tuner` (trait, Plugin) — processes MIDI messages: `reset()`, `tune(tuning)`, `process(message)`; keeps the current
```

and replace:

```markdown
- `TuningChanger` (trait, Plugin) — decides when to change tuning by inspecting MIDI messages
- `TuningSession` / `TuningService` — holds current tuning index and exposes thread-safe API for changing it
```

with:

```markdown
- `TuningSession` / `TuningService` — holds current tuning index and exposes thread-safe API for changing it

**Tuning changer (`tuning-changer` module, `org.calinburloiu.music.microtonalist.tuner.changer`):**

- `TuningChanger` (abstract class, Plugin) — decides when to change tuning by inspecting MIDI messages, returning a
  `TuningChange`; `PedalTuningChanger`, in the `tuning-changer-pedal` module, triggers on a pedal
```

- [ ] **Step 6: Fix the module docs that put the moved types in `tuner`**

In `theory/composition/docs/ARCHITECTURE.md`, replace:

```markdown
- **Depends on `tuner`** for the output value type `Tuning` (and `Tuning.Standard`). The arrow points *into* `tuner`:
  this module produces `Tuning`s but does not perform MIDI tuning. It also uses `sc-midi` types such as `PitchClass` and
  `MidiNote` (reached via `tuner`) and `common`'s `Plugin` trait.
```

with:

```markdown
- **Depends on [`tuning`](../../../tuner/tuning/docs/ARCHITECTURE.md)** for the output value type `Tuning` (and
  `Tuning.Standard`), and not on the tuning engine of `tuner`: this module produces `Tuning`s but does not perform MIDI
  tuning. It also uses `sc-midi`'s `PitchClass` and `MidiNote`, and `common`'s `Plugin` trait.
```

In `rig/rig/docs/ARCHITECTURE.md`, replace:

```markdown
**Processors.** They are the stages of a track. `TuningChangeProcessor` asks the track's tuning changers in order, the
first effective decision winning, and calls `TuningService.changeTuning`. `TunerProcessor` wraps a `Tuner`: it sends
the tuner's reset to every receiver attached to its transmitter and, as a courtesy, restores 12-EDO on every receiver
detached from it.
```

with:

```markdown
**Processors.** They are the stages of a track. `TuningChangeProcessor` asks the track's
[tuning changers](../../../tuner/tuning-changer/docs/ARCHITECTURE.md) in order, the first effective decision winning,
and calls `TuningService.changeTuning`. `TunerProcessor` wraps a `Tuner`: it sends the tuner's reset to every receiver
attached to its transmitter and, as a courtesy, restores 12-EDO on every receiver detached from it.
```

In `ui/ui/docs/ARCHITECTURE.md`, replace the heading and the sentence under it (no link points to the heading):

```markdown
### Collaborators (in `tuner`)

The frame talks only to these `tuner`-module types — the stable contract for the planned JavaFX rewrite:
```

with:

```markdown
### Collaborators (in `tuner` and `tuning`)

The frame talks only to these types of `tuner` and, for `IndexTuningChange`, `tuning` — the stable contract for the
planned JavaFX rewrite:
```

and replace:

```markdown
Its only direct dependency is `tuner`; `businessync`, `common`, and `sc-midi` arrive transitively through it (the frame
uses Businessync types via that path). Externally it uses Java Swing and Guava's `EventBus`/`@Subscribe`. Coverage
thresholds are currently 0 with `// TODO #182` to raise them — appropriate for code slated for replacement under #99.
```

with:

```markdown
Its direct dependencies are `tuner` and `tuning`; `businessync`, `common`, and `sc-midi` arrive transitively through
them (the frame uses Businessync types via that path). Externally it uses Java Swing and Guava's
`EventBus`/`@Subscribe`. Coverage thresholds are currently 0 with `// TODO #182` to raise them — appropriate for code
slated for replacement under #99.
```

In `format/format/docs/ARCHITECTURE.md`, replace:

```markdown
the `RigRepo` trait and the track I/O plugin families), `tuner` (the tuner and tuning-changer plugin families), `common`
(the `Plugin` type, `Locking`, and shared utilities), and — transitively — `intonation`
```

with:

```markdown
the `RigRepo` trait and the track I/O plugin families), `tuner` and `tuning-changer` (the tuner and tuning-changer
plugin families), `tuning-changer-pedal` (`PedalTuningChanger`), `tuning` (`DefaultCentsTolerance`), `common` (the
`Plugin` type, `Locking`, and shared utilities), and — transitively — `intonation`
```

In `base/common/docs/ARCHITECTURE.md`, replace:

```markdown
`SoftChromaticGenusMapping`), `tuner` (`Tuner`, `TuningChanger`) and `rig` (the track I/O specs `TrackInputSpec` /
`TrackOutputSpec`). The serialization side lives in `format` (`JsonPluginFormat[P]` keys off these
```

with:

```markdown
`SoftChromaticGenusMapping`), `tuner` (`Tuner`), `tuning-changer` (`TuningChanger`) and `rig` (the track I/O specs
`TrackInputSpec` / `TrackOutputSpec`). The serialization side lives in `format` (`JsonPluginFormat[P]` keys off these
```

and replace:

```markdown
common logging/test stack. It is depended on directly by `config`, `tuner`, `rig`, `format`, `sc-midi`,
`sc-midi-javamidi`, and `app`, and transitively by `composition` and `ui` (via `tuner`) and `cli` (via `sc-midi`);
`experiments` does not depend on it.
```

with:

```markdown
common logging/test stack. It is depended on directly by `config`, `composition`, `tuner`, `tuning-changer`, `rig`,
`format`, `sc-midi`, `sc-midi-javamidi`, and `app`, and transitively by `ui` (via `tuner`) and by `cli`, `tuning` and
`tuning-changer-pedal` (via `sc-midi`); `experiments` does not depend on it.
```

In `theory/intonation/docs/ARCHITECTURE.md`, replace:

```markdown
(research executables over scales), and `app` directly; `format`, `tuner`, and `ui` reach it transitively through
`composition`.
```

with:

```markdown
(research executables over scales), and `app` directly; `format` reaches it transitively through `composition`.
```

The old sentence was already wrong: neither `tuner` nor `ui` depends on `composition`.

In `app/app/docs/ARCHITECTURE.md`, replace the *Dependencies* block's lines:

```text
app → ui → tuner → sc-midi → businessync
app → sc-midi-javamidi → sc-midi
app → rig → tuner
app → composition → { intonation, tuner }
app → format → { common, composition, rig, tuner }
```

with:

```text
app → ui → tuner → tuning → sc-midi → businessync
app → sc-midi-javamidi → sc-midi
app → rig → { tuner, tuning-changer }
app → composition → { intonation, tuning }
app → format → { common, composition, rig, tuner, tuning-changer-pedal }
```

`businessync`'s doc stays: the modules it lists still publish and subscribe as it says.

- [ ] **Step 7: Check the links and the size budget**

Stage the new docs first, since the checker reads `git ls-files`:

```bash
git add tuner/tuning tuner/tuning-changer tuner/tuning-changer-pedal
python3 "$SCRATCH/check_md_links.py" .
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py
```

Expected: `0 broken of 268 relative link(s) and import(s)`, 21 more than the baseline: the three `README.md` links and
three `CLAUDE.md` imports, the links of the new `ARCHITECTURE.md`s (2 in `tuning`'s, 5 in `tuning-changer`'s, 1 in
`tuning-changer-pedal`'s), 2 more in `tuner`'s, 1 each in `rig`'s and `composition`'s, and the three *Module index*
rows. The size script lists 24 docs, the three new ones among them, and exits 0.

- [ ] **Step 8: Check that the *Packages* table leads the Metals tools to the right module**

Run `mcp__metals__get-usages` with each pair below. Expected: a non-empty result for each.

| `fqcn` | `module` |
| ------ | -------- |
| `org.calinburloiu.music.microtonalist.tuner.Tuning` | `tuning` |
| `org.calinburloiu.music.microtonalist.tuner.TunerModule` | `tuner` |
| `org.calinburloiu.music.microtonalist.tuner.changer.TuningChangeTriggers` | `tuning-changer` |
| `org.calinburloiu.music.microtonalist.tuner.changer.pedal.PedalTuningChanger` | `tuning-changer-pedal` |

- [ ] **Step 9: Check the lines of the docs**

```bash
for f in tuner/*/docs/ARCHITECTURE.md docs/architecture/*.md; do
  awk -v f="$f" 'length($0) > 120 {print f ":" NR ": " length($0)}' "$f"
done
```

Expected: only table rows, which can't wrap, e.g. the `tuning-changer-pedal` row of the *Module index*. `awk` counts
bytes, so a line with `—` or `…` may show up to two or four more than its width in characters: count those by hand.

- [ ] **Step 10: Grep for statements that put the moved types in `tuner`**

Rerun Task 1, step 7. Expected: nothing. Fix any line it prints.

- [ ] **Step 11: Commit**

```bash
git add tuner docs app/app/docs base/common/docs format/format/docs rig/rig/docs theory/composition/docs \
  theory/intonation/docs ui/ui/docs
rtk proxy git status --short
git commit -m "[#366/#375] Document the tuning modules" -m "<attribution lines>"
```

Expected before the commit: only the files of this task's **Files** are staged.

---

### Task 5: Final checks

**Files:**
- Modify: whatever `sbtn fixLint` reformats (expected: the imports of Task 3, steps 2 and 3, and the ScalaDoc of Task 3,
  step 8)

**Interfaces:**
- Consumes: everything above; Task 1's baselines.

- [ ] **Step 1: Full test suite**

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 \
  | awk '/Total number of tests run/ {n += $NF; m++} /FAILED|\[error\]/ {print} END {print m " modules, " n " tests"}'
```

Expected: `14 modules, 1475 tests` and no other line.

- [ ] **Step 2: Lint**

Lint comes before coverage, so that a reformatted file doesn't leave a stale report behind.

```bash
sbtn fixLint
rtk proxy git status --short
```

Expected: `fixLint` passes. If it reformatted files, commit them:
`git commit -am "[#366/#375] Format the code" -m "<attribution lines>"`.

- [ ] **Step 3: Coverage**

Invoke the `scoverage-inspector` skill, and run the whole workflow, as CI does:

```bash
bin/mtlist-coverage-all
bin/mtlist-scoverage-inspector module-summary tuning --overall-only
bin/mtlist-scoverage-inspector module-summary tuning-changer --overall-only
bin/mtlist-scoverage-inspector module-summary tuning-changer-pedal --overall-only
bin/mtlist-scoverage-inspector module-summary tuner --overall-only
bin/mtlist-scoverage-inspector module-summary composition --overall-only
```

Expected: every module passes its floor and the aggregate passes its own; the four modules as Tasks 2 and 3 measured
them; `composition` at 80% or more for both, its tests unchanged.

- [ ] **Step 4: The *Done when* criterion**

Rerun Task 3, step 9. Expected: the same output.

- [ ] **Step 5: Docs and ScalaDoc links**

Rerun Task 4, steps 7, 9 and 10, and the loop and the two `comm`s of Task 3, step 8. Expected: the same results;
nothing from either `comm`.

- [ ] **Step 6: Metals**

Run `mcp__metals__list-modules` and `mcp__metals__get-usages` as in Task 3, steps 6 and 7. Expected: the same
results.

- [ ] **Step 7: Hand over**

Don't push and don't open a PR. Report to the user the results of the steps above and of the final review, with the
branch's commits (`git log --oneline 3f74046..HEAD`) and the coverage issue of Task 2, step 9. Remind them that an IDE
such as IntelliJ must re-import the sbt project to see the new modules.
