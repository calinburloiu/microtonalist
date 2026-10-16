# Tuner and Format Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-10-16
- **Based on commit:** `818e6e0` (`refactoring/tuning-modules`, step 3 implemented in the other clone, not merged)
- **Issue:** #380, steps 4 and 5 of #366, under one plan, one branch and, later, one PR

**Goal:** Move each tuner into a module of its own (`tuner-mpe`, `tuner-mts`, `tuner-mpb`), then split `format` into
`format-core`, `intonation-format`, `composition-format`, `rig-format`, `tuner-format`, `tuning-changer-format` and the
`format` aggregator, with `format-test-utils` for the shared test helpers.

**Architecture:** Each module is a `git mv` of its files, which change only their `package` clause and imports, in one
commit per step of the dependency order: the tuners, `format-core`, `intonation-format`, `composition-format`, the
plugin family formats, `rig-format`. Before the split, two red/green changes remove the couplings that would make the
format modules depend on each other: `JsonPreprocessorHttpRefLoader` stops throwing the scales'
`ScaleNotFoundException`, and `JsonRigFormat` takes the tuner and tuning changer family formats as constructor
parameters. Then one task measures the coverage floors, and two document the modules.

**Tech Stack:** sbt 1, Scala 3, Play JSON, ScalaTest, scoverage, Markdown.

**Spec:** [`2026-10-16-design.md`](2026-10-16-design.md): *Module conventions*, step *4. A module per tuner* and step
*5. Split `format`*.

## Global Constraints

`…` abbreviates `org.calinburloiu.music.microtonalist`, which the code spells out.

- The new modules, each with `name := "microtonalist-<id>"`, `commonSettings` and `disablePlugins(AssemblyPlugin)`, and
  aggregated by `root`; each `dependsOn` lists its modules in the alphabetical order of their `lazy val`, as
  `build.sbt` does:

  | Module | `lazy val` | Directory | Package | Holds | `dependsOn` | Libraries |
  | ------ | ---------- | --------- | ------- | ----- | ----------- | --------- |
  | `tuner-mpe` | `tunerMpeModule` | `tuner/tuner-mpe` | `…tuner.mpe` | the 7 `Mpe*.scala` | `commonTestUtilsModule % Test`, `scMidiModule`, `tunerModule`, `tuningModule` | none |
  | `tuner-mts` | `tunerMtsModule` | `tuner/tuner-mts` | `…tuner.mts` | `MtsTuner`, `MtsMessageGenerator` | `scMidiModule`, `tunerModule`, `tuningModule` | none |
  | `tuner-mpb` | `tunerMpbModule` | `tuner/tuner-mpb` | `…tuner.mpb` | `MonophonicPitchBendTuner` | `commonTestUtilsModule % Test`, `scMidiModule`, `tunerModule`, `tuningModule` | none |
  | `format-test-utils` | `formatTestUtilsModule` | `format/format-test-utils` | `…format` | `JsonFormatTestUtils` | none | `playJson`, `scalaTest`; `coverageEnabled := false` |
  | `format-core` | `formatCoreModule` | `format/format-core` | `…format` | the infrastructure and the MIDI value formats | `commonModule`, `formatTestUtilsModule % Test`, `scMidiModule` | `playJson` |
  | `intonation-format` | `intonationFormatModule` | `theory/intonation-format` | `org.calinburloiu.music.intonation.format` | the scale formats and repos | `businessyncModule`, `commonTestUtilsModule % Test`, `formatCoreModule`, `formatTestUtilsModule % Test`, `intonationModule` | `guava`, `playJson` |
  | `composition-format` | `compositionFormatModule` | `theory/composition-format` | `…composition.format` | the composition formats, repos and plugin formats | `businessyncModule % Test`, `commonTestUtilsModule % Test`, `compositionModule`, `formatCoreModule`, `formatTestUtilsModule % Test`, `intonationFormatModule`, `intonationModule`, `scMidiModule`, `tuningModule` | `playJson` |
  | `tuner-format` | `tunerFormatModule` | `tuner/tuner-format` | `…tuner.format`, `…tuner.{mpe,mts,mpb}.format` | `JsonTunerPluginFormat` and the tuner plugin formats | `formatCoreModule`, `formatTestUtilsModule % Test`, `scMidiModule`, `tunerModule`, `tunerMpbModule`, `tunerMpeModule`, `tunerMtsModule` | `playJson` |
  | `tuning-changer-format` | `tuningChangerFormatModule` | `tuner/tuning-changer-format` | `…tuner.changer.format`, `…tuner.changer.pedal.format` | `JsonTuningChangerPluginFormat` and the pedal changer's format | `formatCoreModule`, `formatTestUtilsModule % Test`, `scMidiModule`, `tuningChangerModule`, `tuningChangerPedalModule` | `playJson` |
  | `rig-format` | `rigFormatModule` | `rig/rig-format` | `…rig.format` | the rig formats, repos and track I/O plugin formats | `commonTestUtilsModule % Test`, `formatCoreModule`, `formatTestUtilsModule % Test`, `rigModule`, `scMidiModule`, `tunerFormatModule % Test`, `tunerModule`, `tunerMpbModule % Test`, `tunerMtsModule % Test`, `tuningChangerFormatModule % Test`, `tuningChangerModule`, `tuningChangerPedalModule % Test` | `playJson` |

  A module declares every module that its sources import from, its tests' in `Test` scope; the libraries are the ones
  its sources import. The infrastructure is `RepoSelector`, `DefaultRepoSelector`, `UriScheme`, `JsonPreprocessor`,
  `JsonPreprocessorFileRefLoader`, `JsonPreprocessorHttpRefLoader`, `DeferrableRead`, `JsonPluginFormat` and
  `JsonConstraints`; the MIDI value formats are `JsonCommonMidiFormat`, `MidiNoteFormat`, `PitchClassFormat` and
  `package.scala`.
- Their users: `rig` adds `tunerMtsModule % Test` (Task 2). `format` ends holding only `FormatModule` and depends on
  `businessyncModule`, `commonTestUtilsModule % Test`, `compositionFormatModule`, `formatCoreModule`,
  `intonationFormatModule`, `rigFormatModule`, `rigModule`, `tunerFormatModule`, `tunerMtsModule % Test`,
  `tuningChangerFormatModule` and `tuningChangerPedalModule % Test`, with no library (Task 9). `app` doesn't change: of
  the format modules it depends on `format` only, and of the tuner package it imports only `TunerModule`.
- Done when (step 4) `tuner` holds no tuner implementation; (step 5) `rig-format` has no `Compile` dependency on a
  plugin module, `tuner-format` or `tuning-changer-format`; `format-core` has none on another format module;
  `intonation-format` has none on `composition`, `rig` or `tuner`; `app` depends on no format module but `format`.
- A moved file changes only its `package` clause, its imports and the ScalaDoc links that its new package breaks. The
  other source edits are Task 3's 404 fix, Task 4's `JsonRigFormat` parameters and `FormatModuleIntegrationTest`, the
  split of `JsonTuningChangerPluginFormat` (Task 8), the split of `FormatTestUtils` (Tasks 6, 7, 9), the tuner-mts copy
  of `TestTunings` (Task 2) and the `MpeTuner` ScalaDoc paths (Task 2). The `private[tuner]` qualifiers of the MPE and
  MTS files stay: `…tuner` encloses the new subpackages. Every user of a `private[format]` member moves with it, so the
  qualifier, which names the nearest enclosing package called `format`, keeps working.
- Test resources keep their resource path, `format/…`, in every module, so no test changes the paths it reads: a test
  classpath holds its own module's test resources only. A resource that tests of several modules read is moved with
  `git mv` to one of them and copied to the others.
- The moves change no behaviour, so the existing suite is their test: it keeps its 1475 tests, plus the 4 that Tasks 3
  and 4 add, 1479 in all. Tasks 3 and 4 follow red/green.
- Coverage floors are measured with each module's own tests, in Task 10: 80/80 for a module that reaches it, otherwise
  its measured coverage minus 3, rounded down to a whole percent, with a `// TODO #<n>` citing its coverage issue: #179
  for the format modules, whose title and description Task 10 updates; a new issue for a tuner module or `tuner`, which
  have none. Until Task 10, a new module has `coverageSettings(stmt = 80, branch = 80)` and `format` keeps 66/59. No
  step writes tests to raise coverage.
- Every commit message starts with `[#366/#380]` and ends with the attribution lines that your session's instructions
  give.
- Don't edit `issues/` (other than checking off this plan) or `docs/agent-evals/`. `docs/release-notes.md` is out of
  scope.
- Docs follow `docs/development/doc-conventions.md` and the `architecture-docs` skill, with lines of at most 120
  columns.
- Work in this clone, on `refactoring/tuner-format-modules`, with `git switch`: no git worktree. The `microtonalist` and
  `microtonalist2` clones may each run a session and a development stack: never touch the other clone's processes.
  Don't push and don't open a PR.
- The plan deletes build output only with `sbtn "tuner/clean"` and `sbtn "format/clean"`, through the coverage scripts,
  which clean `target-scoverage/`, and through `$SCRATCH/doc_links.sh`, which empties `target-bsp/scala-*/api/`.
- Shell notes: the Bash tool runs zsh, which doesn't word-split an unquoted `$VAR`, so module lists are spelled out. A
  child `zsh` resets `PATH` from `~/.zshenv`, so the helper scripts run under `bash`. rtk rewrites `grep` to `rg` and
  `find` to a version without `-prune`, `-exec` or `-delete`, and filters `git status`, `git diff` and `ps`: use
  `grep -F` for literal text, and `rtk proxy <cmd>` for `find` and where the exact output matters.
- The link checker lists the files with `git ls-files`, so stage a new doc before counting links.
- `$SCRATCH` is your session's scratchpad directory.

**Routines** that the tasks cite:

- *Refresh*, after a `build.sbt` change: run `mcp__metals__import-build`, which reloads the build on the stack's sbt
  server too. Then `sbtn projects` and `sbtn 'show <a new module>/target'`: the task gives the count of projects, and
  the target ends in `/<group>/<id>/target-bsp`; if a project is missing, run `sbtn reload` and check again. Then
  `mcp__metals__list-modules`: the task gives the count of build targets; if they are missing, restart the stack with
  `bin/mtlist-dev-stack restart`, and if the Metals MCP tools then stop answering, ask the user to reconnect them with
  `/mcp`. Then `sbtn "<module>/clean"` for the module that lost sources, `tuner` or `format`: Zinc deletes the class
  files of a moved source but not always its SemanticDB files. Then `mcp__metals__compile-full`: success, with only the
  two deprecation warnings on `TuningService.tunings` (in `TuningServiceTest` and `TuningListFrame`). A `Not found`
  error names an import that the task's table missed; an error that a package doesn't exist, a missing `dependsOn`; an
  error that `format` is ambiguous, or is a package, a wildcard import such as `org.calinburloiu.music.intonation.*`
  that now brings a `format` subpackage into scope: import the names the file uses instead.
- *Classpath*, the modules a module's `Compile` classpath holds:

  ```bash
  for m in <modules>; do
    printf '%s: ' "$m"
    sbtn "show $m/Compile/internalDependencyClasspath" 2>&1 \
      | grep -oE '[a-z]+/[a-z-]+/target-bsp/' | sed 's#/target-bsp/##' | sort -u | tr '\n' ' '; echo
  done
  ```

- *Tests*: `sbtn "<module>/testOnly * -- -oNCXEHLOPQRMWS"` for each module the task names, then the full suite:

  ```bash
  sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 \
    | awk '/Total number of tests run/ {n += $NF; m++} /FAILED|\[error\]/ {print} END {print m " modules, " n " tests"}'
  ```

  `m` counts the modules that have tests: `ui`, `businessync`, `format-test-utils` and the like have none.
- *Doc links*, the ScalaDoc link warnings that the task added: run `$SCRATCH/doc_links.sh` (Task 1, step 4) with the
  task's modules into `$SCRATCH/doc-links-after.txt`, then compare the link queries, without their module, with
  Task 1's:

  ```bash
  cut -d' ' -f2- "$SCRATCH/doc-links-before.txt" | sort > "$SCRATCH/queries-before.txt"
  cut -d' ' -f2- "$SCRATCH/doc-links-after.txt" | sort > "$SCRATCH/queries-after.txt"
  comm -13 "$SCRATCH/queries-before.txt" "$SCRATCH/queries-after.txt"
  ```

  Expected: nothing. A line names a link that the new package of its file broke: write the link fully qualified, as
  the tasks do for the links they know, and rerun. Links that now resolve, which `comm -23` would list, are fine.
- *Commit*: `git add` the task's paths, then `rtk proxy git status --short` (only the task's **Files** are staged, and
  nothing is left unstaged), `git commit -m "<message>" -m "<attribution lines>"`, and
  `rtk proxy git show -M --format= --name-status HEAD | cut -f1 | sort | uniq -c`, which the task gives the expected
  counts of. A rename that `git mv` made shows as `R` with its similarity, e.g. `R100`, or `R0xx` for a file whose
  package and imports changed: each of those is 080 or more unless the task says otherwise.

## Review Focus

1. **Stale build output in `tuner/tuner/target-bsp/` and `format/format/target-bsp/`.** The SemanticDB files of a moved
   source can survive Zinc, and Metals then reports a type at its old location, or in its old package. Each moving task
   cleans the module that lost sources, checks that no class of a moved file is left there, and runs
   `mcp__metals__get-usages` on a moved type.
2. **A moved test that reads a resource its module lacks.** `CommonTestUtils.uriOfResource` then fails, and only when
   the test runs. Each task runs the tests of every module it touches, and Task 9 checks that `format` keeps only its
   own resource.
3. **`FormatModule`'s rig wiring.** No test reads a rig file through `FormatModule`, whose family formats decide which
   tuners and tuning changers a rig file may name once `JsonRigFormat` takes them as parameters. Task 4 adds
   `FormatModuleIntegrationTest`, which then guards Tasks 5–9.
4. **The packages that several modules share.** `…format` is in `format-core`, `format-test-utils` and `format`, and
   `tuner-format` and `tuning-changer-format` hold `format` subpackages of the plugins' packages. Agents derive a
   symbol's module from the *Packages* table of `module-overview.md` for the Metals symbol tools, which return nothing
   when given a module without the symbol. Task 12, step 2 says which module holds what, and step 6 runs `get-usages`
   with the modules that the table gives.
5. **References that no compiler checks:** six ScalaDoc links whose target ends in another package (Tasks 6, 8, 9),
   the docs that put the tuners in `tuner` or the format types in `format` (20 lines, Task 1, step 6), and the doc paths
   in `MpeTuner`'s ScalaDoc (Task 2). The *Doc links* routine in Tasks 2 and 9, and Task 12's grep, against Task 1's
   baselines.

---

### Task 1: Baseline

**Files:**
- Create (untracked): `$SCRATCH/check_md_links.py`, `$SCRATCH/doc_links.sh`, `$SCRATCH/doc-links-before.txt`

**Interfaces:**
- Produces: the link checker, run as `python3 "$SCRATCH/check_md_links.py" .` from the repository root; the ScalaDoc
  link helper, run as `bash "$SCRATCH/doc_links.sh" <module>=<dir>…`; the warnings in `$SCRATCH/doc-links-before.txt`;
  the baseline numbers that the other tasks compare against.

- [ ] **Step 1: Check the starting point**

```bash
git switch refactoring/tuner-format-modules
rtk proxy git status --short
git log --oneline -2
bin/mtlist-dev-stack status; echo "exit=$?"
```

Expected: no output from `git status`; the log shows this plan's commit on top of `818e6e0`; `exit=0`. If the stack
isn't running, follow `docs/agents/dev-stack.md`. Then run `mcp__metals__list-modules`, which lists 39 build targets,
and `mcp__metals__compile-full`: success, with only the two deprecation warnings on `TuningService.tunings`.

- [ ] **Step 2: Save the link checker**

Step 1's plan holds a link checker that checks every relative Markdown link, `#heading` link and `@import` outside
`issues/` and `docs/agent-evals/`. Extract it, then record the docs baseline:

```bash
awk '/^````python$/{f=1;next} /^````$/{if(f){exit}} f' \
  issues/00366-modularization/2026-10-16-367-grouping-folders-plan.md > "$SCRATCH/check_md_links.py"
wc -l < "$SCRATCH/check_md_links.py"
python3 "$SCRATCH/check_md_links.py" .
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py | wc -l
```

Expected: `90`; `0 broken of 269 relative link(s) and import(s)`; `24` docs.

- [ ] **Step 3: Record the red check of step 4**

```bash
rtk proxy git ls-files tuner/tuner/src/main | sed 's#.*/##' | tr '\n' ' '; echo
```

Expected: 15 files, among them the 7 `Mpe*.scala`, `MtsTuner.scala`, `MtsMessageGenerator.scala` and
`MonophonicPitchBendTuner.scala`.

- [ ] **Step 4: Save the ScalaDoc link helper and record the warnings**

`sbtn "<module>/doc"` is skipped, printing no warning, when its inputs and its `api/` file set match its last run. Save
`$SCRATCH/doc_links.sh`:

```bash
#!/usr/bin/env bash
# Prints the ScalaDoc link warnings of the given modules, one "<module>: link query: <query>" line each.
# Each argument is <module>=<module dir>. sbt skips a doc run whose inputs and api/ file set match its last run, so
# api/ is emptied and given a uniquely named marker first.
shopt -s nullglob
for arg in "$@"; do
  m=${arg%%=*}
  dir=${arg#*=}
  for api in "$dir"/target-bsp/scala-*/api; do
    rm -rf "$api"
    mkdir -p "$api"
    touch "$api/.rerun-$(date +%s)-$RANDOM"
  done
  sbtn "$m/doc" 2>&1 | grep -F 'link query: ' | sed -E "s/.*(link query: [^ ]+).*/$m: \1/"
done
```

Then:

```bash
bash "$SCRATCH/doc_links.sh" tuner=tuner/tuner format=format/format rig=rig/rig composition=theory/composition \
  | sort > "$SCRATCH/doc-links-before.txt"
cut -d: -f1 "$SCRATCH/doc-links-before.txt" | uniq -c
```

Expected: `4 composition`, `45 format` and `5 rig`, none from `tuner`. No step fixes these 54.

- [ ] **Step 5: Record the test counts**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
```

Then the full suite (*Tests* routine). Expected: `tuner` 500 tests in 10 suites, `format` 218 in 23, and
`14 modules, 1475 tests`. The tasks expect these parts of them, which moving the tests leaves unchanged:

| Tests | Suites | Count |
| ----- | ------ | ----- |
| MPE | `MpeChannelAllocatorTest`, `MpeMessageRoutingTest`, `MpeTunerTest`, `MpeZoneTest` | 386 in 4 |
| MTS | `MtsMessageGeneratorTest`, `MtsTunerTest` | 24 in 2 |
| Monophonic Pitch Bend | `MonophonicPitchBendTunerTest` | 60 in 1 |
| the rest of `tuner` | `TunerTest`, `TuningServiceTest`, `TuningSessionTest` | 30 in 3 |
| infrastructure | `DeferrableReadTest`, `FormatPackageObjectTest`, `JsonPluginFormatTest`, `JsonPreprocessorHttpRefLoaderTest`, `JsonPreprocessorTest` | 39 in 5 |
| scales | `DefaultScaleRepoTest`, `HuygensFokkerScalaScaleFormatTest`, `JsonIntervalFormatTest`, `JsonIntonationStandardPluginFormatTest`, `JsonScaleFormatTest`, `ScaleContextConverterTest`, `ScaleFormatRegistryTest` | 73 in 7 |
| compositions | `JsonCompositionFormatTest`, `JsonSoftChromaticGenusMappingPluginFormatTest`, `JsonTuningMapperPluginFormatTest`, `JsonTuningReducerPluginFormatTest`, `JsonTuningReferencePluginFormatTest`, `KeyboardMappingFormatTest` | 50 in 6 |
| rigs | `JsonRigFormatTest`, `JsonTrackInputSpecPluginFormatTest`, `JsonTrackOutputSpecPluginFormatTest` | 19 in 3 |
| tuners | `JsonTunerPluginFormatTest` | 33 in 1 |
| tuning changers | `JsonTuningChangerPluginFormatTest` | 4 in 1 |

- [ ] **Step 6: Record the statements that put the moved types in `format` or `tuner`**

```bash
git grep -nF -e 'which `format` (de)serializes' -e '(that is `format`)' -e '`format` uses to select' \
  -e 'Depended on by **`format`**' -e 'serialization in `format`' -e '`format` reaches it transitively' \
  -e 'serialization side lives in `format`' -e 'transitively `format`' -e '(format module)' \
  -e 'app → format → { common' -e "doesn't depend on \`format\`" -e "\`format\`'s tests exercise" \
  -e '`rig`, `format`,' -e '`format`, `sc-midi`,' -e 'its implementations in' -e '(de)serializes scales' \
  -e 'tuning protocols (MTS Octave' -e 'Everything lives in package' \
  -- ':!issues/' ':!docs/agent-evals/' ':!docs/release-notes.md'
```

Expected: 20 lines, in the docs of `app`, `businessync`, `common` (3), `format`, `rig` (2), `composition` (3),
`intonation` (3), `tuner`, `tuning-changer`, and in `data-flow.md`, `domain-concepts.md`, `coverage.md` and
`test-conventions.md`. Tasks 11 and 12 fix them all.

---

### Task 2: Move each tuner into a module of its own

**Files** (`$T` is `org/calinburloiu/music/microtonalist/tuner`; the sources leave
`tuner/tuner/src/{main,test}/scala/$T/`, and each name is a `.scala` file):
- Move (`git mv`), then edit the `package` clause and imports:
  - to `tuner/tuner-mpe/src/main/scala/$T/mpe/`: `MpeChannelAllocator`, `MpeChannelState`, `MpeExpression`,
    `MpeMessageRouting`, `MpeNoteIdentity`, `MpeTuner`, `MpeZone`; to `tuner/tuner-mpe/src/test/scala/$T/mpe/`:
    `MpeChannelAllocatorTest`, `MpeMessageRoutingTest`, `MpeTunerTest`, `MpeZoneTest`
  - to `tuner/tuner-mts/src/main/scala/$T/mts/`: `MtsTuner`, `MtsMessageGenerator`; to
    `tuner/tuner-mts/src/test/scala/$T/mts/`: `MtsTunerTest`, `MtsMessageGeneratorTest`
  - to `tuner/tuner-mpb/src/main/scala/$T/mpb/`: `MonophonicPitchBendTuner`; to
    `tuner/tuner-mpb/src/test/scala/$T/mpb/`: `MonophonicPitchBendTunerTest`
- Move (`git mv`): `tuner/tuner/docs/mpe-tuner.md` and `mpe-tuner-paper.md` to `tuner/tuner-mpe/docs/`
- Create: `tuner/tuner-mts/src/test/scala/$T/mts/TestTunings.scala`
- Modify (imports): `RigManagerTest`, `RigModuleIntegrationTest` in
  `rig/rig/src/test/scala/org/calinburloiu/music/microtonalist/rig/`; `JsonMpeTunerPluginFormating`,
  `JsonMtsTunerPluginFormating`, `JsonMonophonicPitchBendTunerPluginFormating`, `JsonTunerPluginFormatTest`,
  `JsonRigFormatTest` in `format/format/src/{main,test}/scala/org/calinburloiu/music/microtonalist/format/`
- Modify (doc paths): `tuner/tuner/docs/ARCHITECTURE.md`, `AGENTS.md`, `docs/architecture/README.md`,
  `docs/development/doc-conventions.md`
- Modify: `build.sbt` (`root`'s `aggregate`, `rigModule`, `formatModule`; new `tunerMpeModule`, `tunerMtsModule`,
  `tunerMpbModule`)

**Interfaces:**
- Consumes: Task 1's baselines.
- Produces: `…tuner.mpe.{MpeTuner, MpeZones, MpeZone, MpeZoneType, MpeInputMode, …}` in the sbt project `tuner-mpe`;
  `…tuner.mts.{MtsTuner, MtsOctave1ByteNonRealTimeTuner, MtsOctave2ByteNonRealTimeTuner, MtsOctave1ByteRealTimeTuner,
  MtsOctave2ByteRealTimeTuner, MtsMessageGenerator}` in `tuner-mts`; `…tuner.mpb.MonophonicPitchBendTuner` in
  `tuner-mpb`; their Metals build targets `tuner-mpe`, `tuner-mpe-test`, `tuner-mts`, `tuner-mts-test`, `tuner-mpb`,
  `tuner-mpb-test`.

- [ ] **Step 1: Move the files**

```bash
T=org/calinburloiu/music/microtonalist/tuner
M=tuner/tuner/src/main/scala/$T
U=tuner/tuner/src/test/scala/$T
for d in tuner-mpe/src/main/scala/$T/mpe tuner-mpe/src/test/scala/$T/mpe tuner-mts/src/main/scala/$T/mts \
  tuner-mts/src/test/scala/$T/mts tuner-mpb/src/main/scala/$T/mpb tuner-mpb/src/test/scala/$T/mpb tuner-mpe/docs; do
  mkdir -p "tuner/$d"
done
git mv $M/MpeChannelAllocator.scala $M/MpeChannelState.scala $M/MpeExpression.scala $M/MpeMessageRouting.scala \
  $M/MpeNoteIdentity.scala $M/MpeTuner.scala $M/MpeZone.scala tuner/tuner-mpe/src/main/scala/$T/mpe/
git mv $U/MpeChannelAllocatorTest.scala $U/MpeMessageRoutingTest.scala $U/MpeTunerTest.scala $U/MpeZoneTest.scala \
  tuner/tuner-mpe/src/test/scala/$T/mpe/
git mv $M/MtsTuner.scala $M/MtsMessageGenerator.scala tuner/tuner-mts/src/main/scala/$T/mts/
git mv $U/MtsTunerTest.scala $U/MtsMessageGeneratorTest.scala tuner/tuner-mts/src/test/scala/$T/mts/
git mv $M/MonophonicPitchBendTuner.scala tuner/tuner-mpb/src/main/scala/$T/mpb/
git mv $U/MonophonicPitchBendTunerTest.scala tuner/tuner-mpb/src/test/scala/$T/mpb/
git mv tuner/tuner/docs/mpe-tuner.md tuner/tuner/docs/mpe-tuner-paper.md tuner/tuner-mpe/docs/
rtk proxy git diff --cached -M --name-status | cut -f1 | sort | uniq -c
```

Expected: `19 R100`.

- [ ] **Step 2: Give the moved files their packages and imports**

| Files | `package` | Imports |
| ----- | --------- | ------- |
| the 7 `Mpe*.scala` and the 4 `Mpe*Test.scala` | `…tuner.mpe` | `…tuner.MpeChannelAllocator.ChannelGroup` becomes `…tuner.mpe.MpeChannelAllocator.ChannelGroup` (`MpeChannelAllocator`, `MpeChannelState`, `MpeChannelAllocatorTest`); `…tuner.MpeRoutingVerdict.*` becomes `…tuner.mpe.MpeRoutingVerdict.*` (`MpeMessageRoutingTest`); `MpeTuner` and `MpeTunerTest` add `…tuner.{Tuner, Tuning}` |
| `MtsTuner.scala` | `…tuner.mts` | add `…tuner.{Tuner, Tuning}` |
| `MtsMessageGenerator.scala`, `MtsMessageGeneratorTest.scala`, `MtsTunerTest.scala` | `…tuner.mts` | add `…tuner.Tuning` |
| `MonophonicPitchBendTuner.scala` | `…tuner.mpb` | add `…tuner.{Tuner, Tuning}` |
| `MonophonicPitchBendTunerTest.scala` | `…tuner.mpb` | add `…tuner.Tuning` |

`sbtn fixLint` (Task 13) orders the imports. Point `MpeTuner`'s ScalaDoc at the moved docs:

```bash
perl -pi -e 's#tuner/tuner/docs/mpe-tuner#tuner/tuner-mpe/docs/mpe-tuner#g' \
  tuner/tuner-mpe/src/main/scala/org/calinburloiu/music/microtonalist/tuner/mpe/MpeTuner.scala \
  AGENTS.md docs/architecture/README.md docs/development/doc-conventions.md
perl -pi -e 's#\]\(mpe-tuner\.md\)#](../../tuner-mpe/docs/mpe-tuner.md)#' tuner/tuner/docs/ARCHITECTURE.md
git grep -n 'tuner/tuner/docs/mpe\|](mpe-tuner.md)' -- ':!issues/' ':!docs/agent-evals/' ':!docs/release-notes.md'
```

Expected: nothing.

- [ ] **Step 3: Give `tuner-mts` its copy of `TestTunings`**

`MtsTunerTest` uses only `TestTunings.justCMaj`, and `tuner`'s `TestTunings` stays with `TunerTest`,
`TuningServiceTest` and `TuningSessionTest`. Create `tuner/tuner-mts/src/test/scala/$T/mts/TestTunings.scala` in
`…tuner.mts`, importing `…tuner.Tuning`, with the ScalaDoc
`/** Tunings for the tests of `tuner-mts`: a trimmed copy of the `tuner` tests' `TestTunings`. */` and only `justCMaj`,
copied from `tuner`'s, as `rig`'s copy does.

- [ ] **Step 4: Point the users at the new packages**

| File | Change |
| ---- | ------ |
| `RigManagerTest.scala`, `RigModuleIntegrationTest.scala` | drop `MtsMessageGenerator` and `MtsOctave1ByteNonRealTimeTuner` from the `…tuner.{…}` import; add `…tuner.mts.{MtsMessageGenerator, MtsOctave1ByteNonRealTimeTuner}` |
| `JsonMpeTunerPluginFormating.scala` | `…tuner.*` becomes `…tuner.mpe.*` |
| `JsonMtsTunerPluginFormating.scala` | `…tuner.*` becomes `…tuner.mts.*` |
| `JsonMonophonicPitchBendTunerPluginFormating.scala` | `…tuner.MonophonicPitchBendTuner` becomes `…tuner.mpb.MonophonicPitchBendTuner` |
| `JsonTunerPluginFormatTest.scala` | `…tuner.*` becomes `…tuner.Tuner`, `…tuner.mpb.MonophonicPitchBendTuner`, `…tuner.mpe.{MpeInputMode, MpeTuner, MpeZone, MpeZoneType, MpeZones}` and `…tuner.mts.{MtsOctave1ByteNonRealTimeTuner, MtsOctave1ByteRealTimeTuner, MtsOctave2ByteNonRealTimeTuner, MtsOctave2ByteRealTimeTuner}` |
| `JsonRigFormatTest.scala` | `…tuner.*` becomes `…tuner.mpb.MonophonicPitchBendTuner` and `…tuner.mts.MtsOctave1ByteNonRealTimeTuner` |

Explicit names rather than wildcards in the tests: Task 8 moves `JsonTunerPluginFormatTest` into `…tuner.format`, and a
wildcard import of `…tuner.mpe.*` would then bring its `format` subpackage into scope too.

- [ ] **Step 5: Declare the modules in `build.sbt`**

In `root`'s `aggregate`, add `tunerMpeModule,`, `tunerMtsModule,` and `tunerMpbModule,` after `tunerModule,`. In
`formatModule`'s `dependsOn`, add `tunerMpbModule,`, `tunerMpeModule,` and `tunerMtsModule,` after `tunerModule,`; in
`rigModule`'s, `tunerMtsModule % Test,`. After `tunerModule`'s definition, add the three modules of the Global
Constraints' table, e.g.:

```scala
lazy val tunerMpeModule = (project in file("tuner/tuner-mpe"))
  .withId("tuner-mpe")
  .dependsOn(
    commonTestUtilsModule % Test,
    scMidiModule,
    tunerModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuner-mpe",
    commonSettings,
    coverageSettings(stmt = 80, branch = 80),
  )
```

`tuner` keeps `commonTestUtilsModule % Test` for `TunerTest` and `jsr305` for `Tuner` and the session.

- [ ] **Step 6: Refresh and compile**

*Refresh* routine, for `tuner-mpe`, cleaning `tuner`. Expected: 22 projects, the 19 of before and the three new ones;
45 build targets, the 39 of before and the six new ones. Then:

```bash
rtk proxy find tuner/tuner/target-bsp -name 'Mpe*' -o -name 'Mts*' -o -name 'MonophonicPitchBend*'
```

Expected: nothing. Then run `mcp__metals__get-usages` with
`fqcn = "org.calinburloiu.music.microtonalist.tuner.mpe.MpeTuner"` and `module = "tuner-mpe"`. Expected: usages under
`tuner/tuner-mpe/src/` and in `format`'s `JsonMpeTunerPluginFormating` and `JsonTunerPluginFormatTest`; none under
`tuner/tuner/`.

- [ ] **Step 7: Run the red check (green) and the classpath check**

Rerun Task 1, step 3. Expected:
`Tuner.scala TunerModule.scala TuningEvent.scala TuningService.scala TuningSession.scala`. Then the *Classpath* routine for `tuner-mpe tuner-mts tuner-mpb`. Expected, for each:
`base/businessync base/common midi/sc-midi tuner/tuner tuner/tuning`.

- [ ] **Step 8: Check the ScalaDoc links**

*Doc links* routine, with `tuner=tuner/tuner tuner-mpe=tuner/tuner-mpe tuner-mts=tuner/tuner-mts
tuner-mpb=tuner/tuner-mpb format=format/format rig=rig/rig composition=theory/composition`. Expected: nothing from the
`comm`.

- [ ] **Step 9: Run the tests**

*Tests* routine, for `tuner-mpe`, `tuner-mts`, `tuner-mpb`, `tuner`, `rig` and `format`. Expected: 386 tests in 4
suites; 24 in 2; 60 in 1; 30 in 3; 70 in 7; 218 in 23; `17 modules, 1475 tests` and no other line.

- [ ] **Step 10: Commit**

*Commit* routine, with `git add build.sbt tuner rig format AGENTS.md docs` and the message
`[#366/#380] Move each tuner into a module of its own`. Expected: `12 M` (`build.sbt`, two `rig` tests, five `format`
files and four docs), `1 A` (`TestTunings.scala`), and 19 renames, the two docs `R100`.

---

### Task 3: Throw `JsonPreprocessorRefLoadException` when a referenced URL is not found

`JsonPreprocessorHttpRefLoader` throws the scales' `ScaleNotFoundException` on a 404, where the file loader throws its
own `JsonPreprocessorRefLoadException` for a missing file. The 4xx branch below the 404 case throws the right exception,
so the fix deletes the case, and `format-core` then needs no scale type.

**Files:**
- Modify: `format/format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonPreprocessorHttpRefLoader.scala`
- Test: `format/format/src/test/scala/org/calinburloiu/music/microtonalist/format/JsonPreprocessorHttpRefLoaderTest.scala`

**Interfaces:**
- Produces: `JsonPreprocessorHttpRefLoader.load` throws `JsonPreprocessorRefLoadException(uri, pathContext, …)` on a
  404, and references no type of the scales.

- [ ] **Step 1: Write the failing test**

In the `"load" should` group, after the two tests there, add a test against the JDK's own HTTP server, which answers
404 to every request:

```scala
    "throw a JsonPreprocessorRefLoadException when the URL is not found (404)" in {
      // Given
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/", exchange => {
        exchange.sendResponseHeaders(404, -1)
        exchange.close()
      })
      server.start()
      val uri = URI(s"http://127.0.0.1:${server.getAddress.getPort}/refs/missing.json")
      val pathContext = JsPath \ "tuningReference"
      val loader = JsonPreprocessorHttpRefLoader(HttpClient.newHttpClient())

      try {
        // When
        val exception = intercept[JsonPreprocessorRefLoadException] {
          loader.load(uri, pathContext)
        }

        // Then
        exception.uri shouldEqual uri
        exception.pathContext shouldEqual pathContext
      } finally {
        server.stop(0)
      }
    }
```

It imports `com.sun.net.httpserver.HttpServer` and `java.net.InetSocketAddress`.

- [ ] **Step 2: Run it to verify it fails**

```bash
sbtn "format/testOnly org.calinburloiu.music.microtonalist.format.JsonPreprocessorHttpRefLoaderTest -- -oNCXEHLOPQRMWS"
```

Expected: 1 of 3 tests fails, because a `ScaleNotFoundException` was thrown instead of a
`JsonPreprocessorRefLoadException`.

- [ ] **Step 3: Delete the 404 case**

In `JsonPreprocessorHttpRefLoader.load`, delete the line `case 404 => throw new ScaleNotFoundException(uri)`.

- [ ] **Step 4: Run the tests to verify they pass**

Rerun step 2: 3 tests pass. Then the *Tests* routine for `format`. Expected: 219 tests in 23 suites;
`17 modules, 1476 tests` and no other line.

- [ ] **Step 5: Commit**

*Commit* routine, with `git add format` and the message
`[#366/#380] Throw JsonPreprocessorRefLoadException when a referenced URL is not found`. Expected: `2 M`.

---

### Task 4: Give `JsonRigFormat` the tuner and tuning changer formats

A rig file holds each track's tuner and tuning changers. `JsonRigFormat` takes their family formats as constructor
parameters, so that `rig-format` depends on the plugin contracts and on no plugin.

**Files:**
- Modify: `format/format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonRigFormat.scala`,
  `FormatModule.scala`
- Test: `format/format/src/test/scala/org/calinburloiu/music/microtonalist/format/JsonRigFormatTest.scala`
- Create: `format/format/src/test/scala/org/calinburloiu/music/microtonalist/format/FormatModuleIntegrationTest.scala`,
  `format/format/src/test/resources/format/format-module.mtlist.tracks`

**Interfaces:**
- Consumes: Task 2's `…tuner.mpb.MonophonicPitchBendTuner` and `…tuner.mts.MtsOctave1ByteNonRealTimeTuner`.
- Produces: `class JsonRigFormat(jsonPreprocessor: JsonPreprocessor, tunerFormat: Format[Tuner],
  tuningChangerFormat: Format[TuningChanger], synchronousAwaitTimeout: FiniteDuration = 1 minute)`; `FormatModule`
  builds it with `JsonTunerPluginFormat.format` and `JsonTuningChangerPluginFormat.format`.

- [ ] **Step 1: Pin `FormatModule`'s rig wiring**

No test reads a rig file through `FormatModule`. This one passes today, and keeps the wiring in check through this
task and the split. Create `format/format/src/test/resources/format/format-module.mtlist.tracks`:

```json
{
    "tracks": [
        {
            "id": "piano",
            "name": "Piano",
            "tuningChangers": ["pedal"],
            "tuner": "mtsOctave1ByteNonRealTime"
        }
    ]
}
```

and `FormatModuleIntegrationTest`, in `…format`, extending `AnyWordSpec with Matchers`:

```scala
  private val formatModule = FormatModule(Businessync(EventBus()), CommonTestUtils.uriOfResource("format/"))

  "FormatModule" should {
    "read a rig file's tuners and tuning changers with their family formats" in {
      // When
      val rigSpec = formatModule.defaultRigRepo.readRig(
        CommonTestUtils.uriOfResource("format/format-module.mtlist.tracks"))

      // Then
      rigSpec.tracks.map(_.tuner) shouldEqual Seq(
        Some(MtsOctave1ByteNonRealTimeTuner(thru = false, altTuningOutput = None)))
      rigSpec.tracks.map(_.tuningChangers) shouldEqual Seq(Seq(PedalTuningChanger()))
    }
  }
```

```bash
sbtn "format/testOnly org.calinburloiu.music.microtonalist.format.FormatModuleIntegrationTest -- -oNCXEHLOPQRMWS"
```

Expected: 1 test passes.

- [ ] **Step 2: Add the parameters (stub)**

Add `tunerFormat: Format[Tuner]` and `tuningChangerFormat: Format[TuningChanger]` to `JsonRigFormat`'s constructor,
after `jsonPreprocessor`, unused for now (the companion still builds the formats; the unused-parameter warning is
allowed until green), with the ScalaDoc:

```scala
 * @param tunerFormat The format of a track's tuner, which knows every tuner type that a rig file may hold.
 * @param tuningChangerFormat The format of a track's tuning changers, which knows every tuning changer type that a rig
 *   file may hold.
```

In `FormatModule`, build `rigFormat` with
`new JsonRigFormat(jsonPreprocessor, JsonTunerPluginFormat.format, JsonTuningChangerPluginFormat.format,
synchronousAwaitTimeout)`, and in `JsonRigFormatTest`, `rigFormat` with
`new JsonRigFormat(NoJsonPreprocessor, JsonTunerPluginFormat.format, JsonTuningChangerPluginFormat.format)`. Run
`mcp__metals__compile-module` with `module = "format"`: success.

- [ ] **Step 3: Write the failing tests**

In `JsonRigFormatTest`, add class-level members, which ignore the plugin JSON to tell the given formats apart from the
family formats:

```scala
  private val givenTuner: Tuner = MonophonicPitchBendTuner(outputChannel = 5)
  private val givenTuningChanger: TuningChanger = PedalTuningChanger(threshold = 42)
  private val rigFormatWithGivenFormats: JsonRigFormat = JsonRigFormat(
    NoJsonPreprocessor,
    Format[Tuner](Reads.pure(givenTuner), Writes(_ => JsString("givenTuner"))),
    Format[TuningChanger](Reads.pure(givenTuningChanger), Writes(_ => JsString("givenTuningChanger")))
  )
```

and, at the end of the `"JsonRigFormat" should` group:

```scala
    "read a track's tuner and tuning changers with the formats it is given" in {
      // Given
      val json = Json.obj("id" -> "piano", "name" -> "Piano", "tuner" -> "unknownTuner",
        "tuningChangers" -> Json.arr("unknownTuningChanger"))

      // When
      val trackSpec = rigFormatWithGivenFormats.readTrack(json, None)

      // Then
      trackSpec.tuner shouldEqual Some(givenTuner)
      trackSpec.tuningChangers shouldEqual Seq(givenTuningChanger)
    }

    "write a track's tuner and tuning changers with the formats it is given" in {
      // Given
      val trackSpec = TrackSpec(id = "piano", name = "Piano", tuningChangers = Seq(PedalTuningChanger()),
        tuner = Some(MtsOctave1ByteNonRealTimeTuner()))

      // When
      val json = rigFormatWithGivenFormats.writeTrackAsJsValue(trackSpec)

      // Then
      (json \ "tuner").get shouldEqual JsString("givenTuner")
      (json \ "tuningChangers").get shouldEqual Json.arr("givenTuningChanger")
    }
```

- [ ] **Step 4: Run them to verify they fail**

```bash
sbtn "format/testOnly org.calinburloiu.music.microtonalist.format.JsonRigFormatTest -- -oNCXEHLOPQRMWS"
```

Expected: the two new tests fail, because `JsonRigFormat` ignores the given formats: the read with an
`InvalidRigFormatException` for the unknown types, the write with the family format's JSON instead of `"givenTuner"`.

- [ ] **Step 5: Use the parameters**

Move `trackSpecFormat` and `rigSpecFormat` from the companion into the class, built with the two parameters as their
implicit tuner and tuning changer formats. The companion drops its `tunerFormat` and `tuningChangerFormat`, whose names
the parameters now take, and keeps the two track I/O formats. The methods drop their `import JsonRigFormat.…` lines.

- [ ] **Step 6: Run the tests to verify they pass**

Rerun step 4: all tests pass. Then the *Tests* routine for `format`. Expected: 222 tests in 24 suites;
`17 modules, 1479 tests` and no other line.

- [ ] **Step 7: Commit**

*Commit* routine, with `git add format` and the message
`[#366/#380] Give JsonRigFormat the tuner and tuning changer formats`. Expected: `3 M` and `2 A`.

---

### Task 5: Move the infrastructure into `format-core` and `format-test-utils`

`format-core` keeps the `…format` package, so no import changes.

**Files** (`$F` is `org/calinburloiu/music/microtonalist/format`; the sources leave
`format/format/src/{main,test}/scala/$F/`, and each name is a `.scala` file):
- Move (`git mv`) to `format/format-core/src/main/scala/$F/`: `RepoSelector`, `DefaultRepoSelector`, `UriScheme`,
  `JsonPreprocessor`, `JsonPreprocessorFileRefLoader`, `JsonPreprocessorHttpRefLoader`, `DeferrableRead`,
  `JsonPluginFormat`, `JsonConstraints`, `JsonCommonMidiFormat`, `MidiNoteFormat`, `PitchClassFormat`, `package`
- Move (`git mv`) to `format/format-core/src/test/scala/$F/`: `DeferrableReadTest`, `FormatPackageObjectTest`,
  `JsonPluginFormatTest`, `JsonPreprocessorHttpRefLoaderTest`, `JsonPreprocessorTest`
- Move (`git mv`) to `format/format-test-utils/src/main/scala/$F/`: `JsonFormatTestUtils`
- Modify: `build.sbt` (`root`'s `aggregate`, `formatModule`; new `formatCoreModule`, `formatTestUtilsModule`)

**Interfaces:**
- Consumes: Task 3's `JsonPreprocessorHttpRefLoader`, which no longer references `ScaleNotFoundException`.
- Produces: the sbt projects `format-core` (`lazy val formatCoreModule`) and `format-test-utils`
  (`lazy val formatTestUtilsModule`), whose `…format` names the later tasks import: `RepoSelector`,
  `DefaultRepoSelector`, `UriScheme`, `JsonPreprocessor`, `NoJsonPreprocessor`, `DeferrableRead`, `AlreadyRead`,
  `JsonPluginFormat`, `JsonCommonMidiFormat`, `MidiNoteFormat`, `PitchClassFormat`, the package object's `filePathOf`,
  `resolveBaseUriWithOverride`, `resolveLibraryUrl`, `ccNumberFormat`, and `JsonFormatTestUtils`.

- [ ] **Step 1: Move the files**

Make the three directories, run the three `git mv`s of the **Files**, then
`rtk proxy git diff --cached -M --name-status | cut -f1 | sort | uniq -c`. Expected: `19 R100`.

- [ ] **Step 2: Declare the modules in `build.sbt`**

In `root`'s `aggregate`, add `formatCoreModule,` and `formatTestUtilsModule,` after `formatModule,`; in `formatModule`'s
`dependsOn`, `formatCoreModule,` and `formatTestUtilsModule % Test,`. After `formatModule`'s definition, add the two
modules of the Global Constraints' table, `formatTestUtilsModule` modelled on `commonTestUtilsModule`:

```scala
lazy val formatTestUtilsModule = (project in file("format/format-test-utils"))
  .withId("format-test-utils")
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-format-test-utils",
    commonSettings,
    libraryDependencies ++= Seq(
      playJson,
      scalaTest,
    ),
    // Test infrastructure — exclude from coverage measurement.
    coverageEnabled := false,
  )
```

`scalaTest` is in `Compile` scope there, since `JsonFormatTestUtils` extends `AnyWordSpec`.

- [ ] **Step 3: Refresh and compile**

*Refresh* routine, for `format-core`, cleaning `format`. Expected: 24 projects; 49 build targets, with `format-core`,
`format-core-test`, `format-test-utils` and `format-test-utils-test`. Then:

```bash
rtk proxy find format/format/target-bsp -name 'JsonPreprocessor*' -o -name 'DeferrableRead*' \
  -o -name 'JsonFormatTestUtils*'
```

Expected: nothing. Run `mcp__metals__get-usages` with
`fqcn = "org.calinburloiu.music.microtonalist.format.JsonPreprocessor"` and `module = "format-core"`. Expected: usages
under `format/format-core/src/` and `format/format/src/`.

- [ ] **Step 4: Run the classpath check and the tests**

*Classpath* routine for `format-core`. Expected: `base/businessync base/common midi/sc-midi`. *Tests* routine for
`format-core` and `format`. Expected: 40 tests in 5 suites; 182 in 19; `18 modules, 1479 tests`.

- [ ] **Step 5: Commit**

*Commit* routine, with `git add build.sbt format` and the message
`[#366/#380] Move the format infrastructure into format-core and format-test-utils`. Expected: `1 M` and `19 R100`.

---

### Task 6: Move the scale formats into `intonation-format`

**Files** (`$F` and the sources as in Task 5; `$I` is `org/calinburloiu/music/intonation/format`):
- Move (`git mv`), then edit:
  - to `theory/intonation-format/src/main/scala/$I/`: `ScaleFormat`, `JsonScaleFormat`,
    `HuygensFokkerScalaScaleFormat`, `ScaleRepo`, `DefaultScaleRepo`, `FileScaleRepo`, `HttpScaleRepo`,
    `LibraryScaleRepo`, `ScaleContextConverter`, `ScaleFormatContext`, `ScaleFormatMetadata`, `ScaleFormatRegistry`,
    `JsonIntervalFormat`, `JsonIntonationStandardPluginFormat`
  - to `theory/intonation-format/src/test/scala/$I/`: `DefaultScaleRepoTest`, `HuygensFokkerScalaScaleFormatTest`,
    `JsonIntervalFormatTest`, `JsonIntonationStandardPluginFormatTest`, `JsonScaleFormatTest`,
    `ScaleContextConverterTest`, `ScaleFormatRegistryTest`
- Create: `theory/intonation-format/src/test/scala/$I/FormatTestUtils.scala`; copies of
  `format/format/src/test/resources/format/scales/{minor-72edo,minor-cents,minor-just}.jscl` in
  `theory/intonation-format/src/test/resources/format/scales/`
- Modify (imports): under `format/format/src/{main,test}/scala/$F/`: `CompositionRepr.scala`,
  `JsonCompositionFormat.scala`, `JsonTuningReferencePluginFormat.scala`, `FormatModule.scala`,
  `JsonCompositionFormatTest.scala`, `FormatTestUtils.scala` (which loses `readScaleFromResources`)
- Modify (ScalaDoc): `format/format/src/main/scala/$F/DefaultCompositionRepo.scala`
- Modify: `build.sbt` (`root`'s `aggregate`, `formatModule`; new `intonationFormatModule`)

**Interfaces:**
- Consumes: Task 5's `format-core` names.
- Produces: `org.calinburloiu.music.intonation.format.{ScaleFormat, JsonScaleFormat, HuygensFokkerScalaScaleFormat,
  ScaleRepo, DefaultScaleRepo, FileScaleRepo, HttpScaleRepo, LibraryScaleRepo, ScaleNotFoundException,
  IncompatibleIntervalsScaleFormatException, ScaleContextConverter, ScaleFormatContext, ScaleFormatRegistry,
  JsonIntervalFormat, JsonIntonationStandardPluginFormat}` in the sbt project `intonation-format`
  (`lazy val intonationFormatModule`).

- [ ] **Step 1: Move the files and copy the resources**

Make the directories and run the `git mv`s of the **Files**, then:

```bash
mkdir -p theory/intonation-format/src/test/resources/format/scales
cp format/format/src/test/resources/format/scales/{minor-72edo,minor-cents,minor-just}.jscl \
  theory/intonation-format/src/test/resources/format/scales/
rtk proxy git diff --cached -M --name-status | cut -f1 | sort | uniq -c
```

Expected: `21 R100`. `DefaultScaleRepoTest` reads the three scales, which `JsonCompositionFormatTest` and
`JsonRigFormatTest` read too.

- [ ] **Step 2: Give the moved files their package and imports**

Each moved file gets `package org.calinburloiu.music.intonation.format` and these imports:

| File | Imports |
| ---- | ------- |
| `DefaultScaleRepo.scala` | `…format.{DefaultRepoSelector, RepoSelector}` |
| `FileScaleRepo.scala` | `…format.filePathOf` |
| `HttpScaleRepo.scala` | `…format.UriScheme` |
| `JsonIntonationStandardPluginFormat.scala` | `…format.JsonPluginFormat` |
| `JsonScaleFormat.scala` | `…format.JsonPreprocessor` |
| `LibraryScaleRepo.scala` | `…format.{DefaultRepoSelector, RepoSelector, resolveLibraryUrl}` |
| `DefaultScaleRepoTest.scala`, `ScaleFormatRegistryTest.scala` | `…format.NoJsonPreprocessor` |
| `JsonIntervalFormatTest.scala` | `…format.JsonFormatTestUtils`; `…format.JsonIntervalFormat.ErrorExpectedIntervalFor` becomes `org.calinburloiu.music.intonation.format.JsonIntervalFormat.ErrorExpectedIntervalFor` |
| `JsonIntonationStandardPluginFormatTest.scala` | `…format.{JsonFormatTestUtils, JsonPluginFormat}` |
| `JsonScaleFormatTest.scala` | `…format.{JsonFormatTestUtils, NoJsonPreprocessor}` |

The others change only their `package`. A test's `import JsonFormatTestUtils.*` resolves through the new import.

- [ ] **Step 3: Split `FormatTestUtils`**

Create `theory/intonation-format/src/test/scala/$I/FormatTestUtils.scala`, in the new package, with only
`readScaleFromResources`, moved from `format`'s `FormatTestUtils`, and the ScalaDoc
`Utilities used for testing the scale formats.` `format`'s `FormatTestUtils` keeps the other two readers and drops the
imports it no longer uses.

- [ ] **Step 4: Point the users at the new package**

| File | Add the import |
| ---- | -------------- |
| `CompositionRepr.scala` | `org.calinburloiu.music.intonation.format.{ScaleFormatContext, ScaleRepo}` |
| `JsonCompositionFormat.scala` | `org.calinburloiu.music.intonation.format.{JsonIntervalFormat, JsonIntonationStandardPluginFormat, JsonScaleFormat, ScaleContextConverter, ScaleFormatContext, ScaleRepo}` |
| `JsonTuningReferencePluginFormat.scala` | `org.calinburloiu.music.intonation.format.JsonIntervalFormat` |
| `FormatModule.scala` | `org.calinburloiu.music.intonation.format.{DefaultScaleRepo, FileScaleRepo, HttpScaleRepo, HuygensFokkerScalaScaleFormat, JsonScaleFormat, LibraryScaleRepo, ScaleContextConverter, ScaleFormat, ScaleFormatRegistry, ScaleRepo}` |
| `JsonCompositionFormatTest.scala` | `org.calinburloiu.music.intonation.format.{DefaultScaleRepo, FileScaleRepo, HuygensFokkerScalaScaleFormat, IncompatibleIntervalsScaleFormatException, JsonScaleFormat, ScaleContextConverter, ScaleFormatContext, ScaleFormatRegistry, ScaleNotFoundException}` |

These imports are absolute, so they stay right when Task 7 moves the composition files.

`DefaultCompositionRepo`'s ScalaDoc documents its parameters with `[[FileScaleRepo]]` and `[[HttpScaleRepo]]`, which
leave its package: they mean the composition repos, so replace them with `[[FileCompositionRepo]]` and
`[[HttpCompositionRepo]]`.

- [ ] **Step 5: Declare the module in `build.sbt`**

In `root`'s `aggregate`, add `intonationFormatModule,` after `intonationModule,`; in `formatModule`'s `dependsOn`,
`intonationFormatModule,`. After `intonationModule`'s definition, add the module of the Global Constraints' table, with
`name := "microtonalist-intonation-format"`: it depends on `format-core`, so it isn't part of the standalone
`intonation` library.

- [ ] **Step 6: Refresh and compile**

*Refresh* routine, for `intonation-format`, cleaning `format`. Expected: 25 projects; 51 build targets. Then:

```bash
rtk proxy find format/format/target-bsp -name '*Scale*' -o -name 'JsonInterval*' -o -name 'JsonIntonation*'
```

Expected: nothing. Run `mcp__metals__get-usages` with `fqcn = "org.calinburloiu.music.intonation.format.ScaleRepo"`
and `module = "intonation-format"`. Expected: usages under `theory/intonation-format/src/` and in `format`'s
`CompositionRepr`, `JsonCompositionFormat` and `FormatModule`.

- [ ] **Step 7: Run the classpath check and the tests**

*Classpath* routine for `intonation-format`. Expected:
`base/businessync base/common format/format-core midi/sc-midi theory/intonation`: no `composition`, `rig` or `tuner`.
*Tests* routine for `intonation-format` and `format`. Expected: 73 tests in 7 suites; 109 in 12;
`19 modules, 1479 tests`.

- [ ] **Step 8: Commit**

*Commit* routine, with `git add build.sbt format theory` and the message
`[#366/#380] Move the scale formats into intonation-format`. Expected: `8 M` (`build.sbt` and seven `format` files),
`4 A` (`FormatTestUtils.scala` and the three scales) and 21 renames.

---

### Task 7: Move the composition formats into `composition-format`

**Files** (`$F` and the sources as in Task 5; `$C` is `org/calinburloiu/music/microtonalist/composition/format`; `$R`
is `format/format/src/test/resources/format`):
- Move (`git mv`), then edit:
  - to `theory/composition-format/src/main/scala/$C/`: `CompositionFormat`, `CompositionRepo`, `CompositionRepr`,
    `DefaultCompositionRepo`, `FileCompositionRepo`, `HttpCompositionRepo`, `JsonCompositionFormat`,
    `JsonTuningMapperPluginFormat`, `JsonTuningReducerPluginFormat`, `JsonTuningReferencePluginFormat`,
    `JsonSoftChromaticGenusMappingPluginFormat`, `KeyboardMappingFormat`
  - to `theory/composition-format/src/test/scala/$C/`: `JsonCompositionFormatTest`,
    `JsonSoftChromaticGenusMappingPluginFormatTest`, `JsonTuningMapperPluginFormatTest`,
    `JsonTuningReducerPluginFormatTest`, `JsonTuningReferencePluginFormatTest`, `KeyboardMappingFormatTest`
- Move (`git mv`): the 16 `$R/*.mtlist`, `$R/file-io-test-01.mtlist.tracks`, and the scales of `$R/scales/` but
  `chromatic.scl` and `minor-just.jscl`, to `theory/composition-format/src/test/resources/format/`, keeping their paths
  below `format/`
- Create: `theory/composition-format/src/test/scala/$C/FormatTestUtils.scala`; copies of `$R/scales/chromatic.scl` and
  `$R/scales/minor-just.jscl`, which `JsonRigFormatTest` reads too, in
  `theory/composition-format/src/test/resources/format/scales/`
- Modify (imports): `FormatModule`, and `FormatTestUtils`, which loses `readCompositionFromResources`
- Modify: `build.sbt` (`root`'s `aggregate`, `formatModule`; new `compositionFormatModule`)

`file-io-test-01.mtlist` and the four `maj-4-*`/`min-4-*` scales that it alone refers to are read by no test; they move
with the compositions, as test data of the module.

**Interfaces:**
- Consumes: Task 5's `format-core` names; Task 6's `intonation-format`.
- Produces: `…composition.format.{CompositionFormat, CompositionRepo, DefaultCompositionRepo, FileCompositionRepo,
  HttpCompositionRepo, JsonCompositionFormat, …}` in the sbt project `composition-format`
  (`lazy val compositionFormatModule`).

- [ ] **Step 1: Move the files and copy the resources**

```bash
C=theory/composition-format/src/test/resources/format
R=format/format/src/test/resources/format
mkdir -p $C/scales
git mv $R/*.mtlist $R/file-io-test-01.mtlist.tracks $C/
git mv $R/scales/invalid.scl $R/scales/maj-4-cents.jscl $R/scales/maj-4-just.scl $R/scales/major.scl \
  $R/scales/min-4-53edo.jscl $R/scales/min-4-cents.scl $R/scales/minor-72edo.jscl $R/scales/minor-cents.jscl $C/scales/
cp $R/scales/chromatic.scl $R/scales/minor-just.jscl $C/scales/
rtk proxy git ls-files $R
```

Expected: the `ls-files` lists `scales/chromatic.scl`, `scales/minor-just.jscl`, the three `tracks/` files and
`format-module.mtlist.tracks`. Then make the Scala directories, run the `git mv`s of the Scala files and
`rtk proxy git diff --cached -M --name-status | cut -f1 | sort | uniq -c`. Expected: `43 R100`.

- [ ] **Step 2: Give the moved files their package and imports**

Each moved file gets `package org.calinburloiu.music.microtonalist.composition.format` and these imports:

| File | Imports |
| ---- | ------- |
| `CompositionRepr.scala` | `…format.DeferrableRead` |
| `DefaultCompositionRepo.scala` | `…format.{DefaultRepoSelector, RepoSelector}` |
| `FileCompositionRepo.scala` | `…format.filePathOf` |
| `HttpCompositionRepo.scala` | `…format.UriScheme` |
| `JsonCompositionFormat.scala` | `…format.{AlreadyRead, DeferrableRead, JsonPreprocessor, resolveBaseUriWithOverride}` |
| `JsonSoftChromaticGenusMappingPluginFormat.scala`, `JsonTuningMapperPluginFormat.scala`, `JsonTuningReducerPluginFormat.scala` | `…format.JsonPluginFormat` |
| `JsonTuningReferencePluginFormat.scala` | `…format.{JsonPluginFormat, MidiNoteFormat, PitchClassFormat}` |
| `JsonCompositionFormatTest.scala` | `…format.NoJsonPreprocessor` |
| `JsonSoftChromaticGenusMappingPluginFormatTest.scala`, `JsonTuningReducerPluginFormatTest.scala`, `JsonTuningReferencePluginFormatTest.scala`, `KeyboardMappingFormatTest.scala` | `…format.JsonFormatTestUtils` |
| `JsonTuningMapperPluginFormatTest.scala` | `…format.{JsonFormatTestUtils, JsonPluginFormat}` |

`CompositionFormat`, `CompositionRepo` and `KeyboardMappingFormat` change only their `package`; the explicit imports of
`…format.JsonConstraints.exclusiveMin` and `…format.PitchClassFormat.InvalidPitchClassError` stay right.

- [ ] **Step 3: Split `FormatTestUtils`**

Create `theory/composition-format/src/test/scala/$C/FormatTestUtils.scala`, in the new package, with only
`readCompositionFromResources`, moved from `format`'s `FormatTestUtils`, and the ScalaDoc
`Utilities used for testing the composition formats.` `JsonCompositionFormatTest`'s
`import FormatTestUtils.readCompositionFromResources` finds it in its package. `format`'s `FormatTestUtils` keeps
`readRigFromResources` and drops the imports it no longer uses.

- [ ] **Step 4: Point `FormatModule` at the new package**

Add `…composition.format.{CompositionFormat, CompositionRepo, DefaultCompositionRepo, FileCompositionRepo,
HttpCompositionRepo, JsonCompositionFormat}` to `FormatModule`'s imports.

- [ ] **Step 5: Declare the module in `build.sbt`**

In `root`'s `aggregate`, add `compositionFormatModule,` after `compositionModule,`; in `formatModule`'s `dependsOn`,
`compositionFormatModule,`. After `compositionModule`'s definition, add the module of the Global Constraints' table:
`JsonCompositionFormatTest` builds a `Businessync`, hence `businessyncModule % Test`.

- [ ] **Step 6: Refresh and compile**

*Refresh* routine, for `composition-format`, cleaning `format`. Expected: 26 projects; 53 build targets. Then:

```bash
rtk proxy find format/format/target-bsp -name '*Composition*' -o -name 'JsonTuningMapper*' \
  -o -name 'JsonTuningReducer*' -o -name 'JsonTuningReference*' -o -name 'JsonSoftChromatic*' \
  -o -name 'KeyboardMapping*'
```

Expected: nothing. Run `mcp__metals__get-usages` with
`fqcn = "org.calinburloiu.music.microtonalist.composition.format.CompositionRepo"` and `module = "composition-format"`.
Expected: usages under `theory/composition-format/src/` and in `format`'s `FormatModule`.

- [ ] **Step 7: Run the classpath check and the tests**

*Classpath* routine for `composition-format`. Expected: `base/businessync base/common format/format-core midi/sc-midi
theory/composition theory/intonation theory/intonation-format tuner/tuning`. *Tests* routine for `composition-format`
and `format`. Expected: 50 tests in 6 suites; 59 in 6; `20 modules, 1479 tests`.

- [ ] **Step 8: Commit**

*Commit* routine, with `git add build.sbt format theory` and the message
`[#366/#380] Move the composition formats into composition-format`. Expected: `3 M` (`build.sbt`, `FormatModule`,
`FormatTestUtils`), `3 A` (`FormatTestUtils.scala` and the two scales), 25 resource renames `R100` and 18 Scala
renames.

---

### Task 8: Move the plugin family formats into `tuner-format` and `tuning-changer-format`

Each plugin's format goes in a `format` subpackage of its plugin's package. `JsonTuningChangerPluginFormat` splits into
the family format and the pedal changer's format, as `JsonTunerPluginFormat` gathers the tuners' formats.

**Files** (`$F` and the sources as in Task 5; `$T` as in Task 2; `$U` is `tuner/tuner-format/src/main/scala/$T`):
- Move (`git mv`), then edit: `JsonTunerPluginFormat` to `$U/format/`, `JsonMpeTunerPluginFormating` to
  `$U/mpe/format/`, `JsonMtsTunerPluginFormating` to `$U/mts/format/`, `JsonMonophonicPitchBendTunerPluginFormating` to
  `$U/mpb/format/`, and `JsonTunerPluginFormatTest` to `tuner/tuner-format/src/test/scala/$T/format/`
- Move (`git mv`), then split: `JsonTuningChangerPluginFormat` to
  `tuner/tuning-changer-format/src/main/scala/$T/changer/format/`
- Move (`git mv`), then edit: `JsonTuningChangerPluginFormatTest` to
  `tuner/tuning-changer-format/src/test/scala/$T/changer/format/`
- Create: `JsonPedalTuningChangerPluginFormating.scala` in
  `tuner/tuning-changer-format/src/main/scala/$T/changer/pedal/format/`
- Modify (imports): `FormatModule`, `JsonRigFormatTest`
- Modify: `build.sbt` (`root`'s `aggregate`, `formatModule`; new `tunerFormatModule`, `tuningChangerFormatModule`)

**Interfaces:**
- Consumes: Task 2's tuner modules; Task 4's `JsonRigFormat` parameters; Task 5's `format-core` names.
- Produces: `…tuner.format.JsonTunerPluginFormat` in the sbt project `tuner-format` (`lazy val tunerFormatModule`);
  `…tuner.changer.format.JsonTuningChangerPluginFormat` and
  `…tuner.changer.pedal.format.JsonPedalTuningChangerPluginFormating` (`ccTriggersFormat`, `pedalTuningChangerFormat`,
  `spec: TypeSpec[PedalTuningChanger]`) in `tuning-changer-format` (`lazy val tuningChangerFormatModule`).

- [ ] **Step 1: Move the files**

Make the directories, run the `git mv`s of the **Files**, then
`rtk proxy git diff --cached -M --name-status | cut -f1 | sort | uniq -c`. Expected: `7 R100`.

- [ ] **Step 2: Give the tuner formats their packages and imports**

| File | `package` | Imports and ScalaDoc |
| ---- | --------- | -------------------- |
| `JsonTunerPluginFormat.scala` | `…tuner.format` | add `…format.JsonPluginFormat`, `…tuner.mpb.format.JsonMonophonicPitchBendTunerPluginFormating`, `…tuner.mpe.format.JsonMpeTunerPluginFormating`, `…tuner.mts.format.JsonMtsTunerPluginFormating` |
| `JsonMpeTunerPluginFormating.scala` | `…tuner.mpe.format` | add `…format.JsonCommonMidiFormat` |
| `JsonMtsTunerPluginFormating.scala` | `…tuner.mts.format` | none: its nested import of `…format.JsonCommonMidiFormat.*` is absolute |
| `JsonMonophonicPitchBendTunerPluginFormating.scala` | `…tuner.mpb.format` | add `…format.JsonCommonMidiFormat` |
| `JsonTunerPluginFormatTest.scala` | `…tuner.format` | add `…format.JsonFormatTestUtils` |

In the three `*Formating.scala`, `@see [[JsonTunerPluginFormat]]` becomes
`@see [[org.calinburloiu.music.microtonalist.tuner.format.JsonTunerPluginFormat]]`, whose package is no longer theirs.

- [ ] **Step 3: Split `JsonTuningChangerPluginFormat`**

`JsonPedalTuningChangerPluginFormating`, in `…tuner.changer.pedal.format`, takes from `JsonTuningChangerPluginFormat`,
unchanged, the index triggers' reads and writes (private), `ccTriggersFormat`, the implicit
`pedalTuningChangerFormat`, and the `TypeSpec.withSettings[PedalTuningChanger](…)` with its `defaultSettings`, as
`val spec: TypeSpec[PedalTuningChanger]`. Its ScalaDocs, like the tuners' formats':

```scala
/**
 * JSON format utilities for [[org.calinburloiu.music.microtonalist.tuner.changer.pedal.PedalTuningChanger]].
 */
```

```scala
  /**
   * JSON format specification for [[org.calinburloiu.music.microtonalist.tuner.changer.pedal.PedalTuningChanger]].
   *
   * @see [[org.calinburloiu.music.microtonalist.tuner.changer.format.JsonTuningChangerPluginFormat]] where it is used.
   */
```

It imports `…format.ccNumberFormat` and `…format.JsonPluginFormat.TypeSpec`. `JsonTuningChangerPluginFormat`, in
`…tuner.changer.format`, keeps `familyName` and lists
`override val specs: TypeSpecs[TuningChanger] = Seq(JsonPedalTuningChangerPluginFormating.spec)`, with the ScalaDoc
`JSON format for [[org.calinburloiu.music.microtonalist.tuner.changer.TuningChanger]] plugins, supporting the pedal
tuning changer.` `JsonTuningChangerPluginFormatTest`, in `…tuner.changer.format`, adds `…format.JsonFormatTestUtils`
and still tests the pedal changer through the family format.

- [ ] **Step 4: Point the users at the new packages**

Add `…tuner.format.JsonTunerPluginFormat` and `…tuner.changer.format.JsonTuningChangerPluginFormat` to the imports of
`FormatModule.scala` and `JsonRigFormatTest.scala`.

- [ ] **Step 5: Declare the modules in `build.sbt`**

In `root`'s `aggregate`, add `tunerFormatModule,` after `tunerMpbModule,` and `tuningChangerFormatModule,` after
`tuningChangerPedalModule,`; in `formatModule`'s `dependsOn`, `tunerFormatModule,` and `tuningChangerFormatModule,`.
After `tunerMpbModule`'s and `tuningChangerPedalModule`'s definitions, add the modules of the Global Constraints' table.

- [ ] **Step 6: Refresh and compile**

*Refresh* routine, for `tuner-format`, cleaning `format`. Expected: 28 projects; 57 build targets. Then:

```bash
rtk proxy find format/format/target-bsp -name 'JsonTuner*' -o -name 'Json*TunerPluginFormating*' \
  -o -name 'JsonTuningChanger*'
```

Expected: nothing. Run `mcp__metals__get-usages` with
`fqcn = "org.calinburloiu.music.microtonalist.tuner.format.JsonTunerPluginFormat"` and `module = "tuner-format"`.
Expected: usages under `tuner/tuner-format/src/` and in `format`'s `FormatModule` and `JsonRigFormatTest`.

- [ ] **Step 7: Run the classpath check and the tests**

*Classpath* routine for `tuner-format tuning-changer-format`. Expected:

```text
tuner-format: base/businessync base/common format/format-core midi/sc-midi tuner/tuner tuner/tuner-mpb tuner/tuner-mpe tuner/tuner-mts tuner/tuning
tuning-changer-format: base/businessync base/common format/format-core midi/sc-midi tuner/tuning tuner/tuning-changer tuner/tuning-changer-pedal
```

*Tests* routine for `tuner-format`, `tuning-changer-format` and `format`. Expected: 33 tests in 1 suite; 4 in 1; 22 in
4; `22 modules, 1479 tests`.

- [ ] **Step 8: Commit**

*Commit* routine, with `git add build.sbt format tuner` and the message
`[#366/#380] Move the plugin family formats into tuner-format and tuning-changer-format`. Expected: `3 M`, `1 A` and 7
renames; the one of `JsonTuningChangerPluginFormat.scala` may land on either file of the split, below 080.

---

### Task 9: Move the rig formats into `rig-format`

After this task, `format` holds only `FormatModule` and its integration test.

**Files** (`$F` and the sources as in Task 5; `$G` is `org/calinburloiu/music/microtonalist/rig/format`; `$R` as in
Task 7):
- Move (`git mv`), then edit:
  - to `rig/rig-format/src/main/scala/$G/`: `RigFormat`, `JsonRigFormat`, `DefaultRigRepo`, `FileRigRepo`,
    `HttpRigRepo`, `LibraryRigRepo`, `JsonTrackIOPluginFormat`
  - to `rig/rig-format/src/test/scala/$G/`: `JsonRigFormatTest`, `JsonTrackIOPluginFormatTest`, `FormatTestUtils`
- Move (`git mv`): `$R/tracks/` and `$R/scales/{chromatic.scl,minor-just.jscl}` to
  `rig/rig-format/src/test/resources/format/`, keeping their paths below `format/`
- Modify (imports): `format/format/src/main/scala/$F/FormatModule.scala`
- Modify: `build.sbt` (`root`'s `aggregate`, `formatModule`; new `rigFormatModule`)

**Interfaces:**
- Consumes: Tasks 4, 5 and 8.
- Produces: `…rig.format.{RigFormat, JsonRigFormat, DefaultRigRepo, FileRigRepo, HttpRigRepo, LibraryRigRepo,
  JsonTrackInputSpecPluginFormat, JsonTrackOutputSpecPluginFormat}` in the sbt project `rig-format`
  (`lazy val rigFormatModule`); `format`'s final `dependsOn`.

- [ ] **Step 1: Move the files**

Make the directories and run the `git mv`s of the **Files**, then:

```bash
rtk proxy git ls-files format/format/src
rtk proxy git diff --cached -M --name-status | cut -f1 | sort | uniq -c
```

Expected: `FormatModule.scala`, `FormatModuleIntegrationTest.scala` and `format-module.mtlist.tracks`; `15 R100`.

- [ ] **Step 2: Give the moved files their package and imports**

Each moved file gets `package org.calinburloiu.music.microtonalist.rig.format` and these imports:

| File | Imports and ScalaDoc |
| ---- | -------------------- |
| `DefaultRigRepo.scala` | `…format.{DefaultRepoSelector, RepoSelector}` |
| `FileRigRepo.scala` | `…format.filePathOf` |
| `HttpRigRepo.scala` | `…format.UriScheme` |
| `JsonRigFormat.scala` | `…format.JsonPreprocessor` |
| `JsonTrackIOPluginFormat.scala` | `…format.JsonPluginFormat`; its ScalaDoc's `[[JsonPluginFormat]]` becomes `[[org.calinburloiu.music.microtonalist.format.JsonPluginFormat]]` |
| `LibraryRigRepo.scala` | `…format.{DefaultRepoSelector, RepoSelector, resolveLibraryUrl}` |
| `JsonRigFormatTest.scala` | `…format.NoJsonPreprocessor`; `…format.FormatTestUtils.readRigFromResources` becomes `…rig.format.FormatTestUtils.readRigFromResources` |
| `JsonTrackIOPluginFormatTest.scala` | `…format.JsonFormatTestUtils` |
| `FormatTestUtils.scala` | none; its ScalaDoc becomes `Utilities used for testing the rig formats.` |

`RigFormat` changes only its `package`.

- [ ] **Step 3: Point `FormatModule` at the new package**

Add `…rig.format.{DefaultRigRepo, FileRigRepo, HttpRigRepo, JsonRigFormat, LibraryRigRepo, RigFormat}` to
`FormatModule`'s imports.

- [ ] **Step 4: Declare the module and `format`'s final dependencies in `build.sbt`**

In `root`'s `aggregate`, add `rigFormatModule,` after `rigModule,`. After `rigModule`'s definition, add the module of
the Global Constraints' table. Give `formatModule` the `dependsOn` of the Global Constraints, without
`libraryDependencies`: `FormatModule` imports no Play JSON, and its integration test imports
`MtsOctave1ByteNonRealTimeTuner`, `PedalTuningChanger` and `CommonTestUtils`, hence the three `Test` dependencies. Its
`coverageSettings` stay until Task 10.

- [ ] **Step 5: Refresh and compile**

*Refresh* routine, for `rig-format`, cleaning `format`. Expected: 29 projects; 59 build targets. Then:

```bash
rtk proxy find format/format/target-bsp -name '*.class' | sed 's#.*/##' | sort -u
```

Expected: only `FormatModule*.class` and `FormatModuleIntegrationTest*.class`. Run `mcp__metals__get-usages` with
`fqcn = "org.calinburloiu.music.microtonalist.rig.format.RigFormat"` and `module = "rig-format"`. Expected: usages
under `rig/rig-format/src/` and in `format`'s `FormatModule`.

- [ ] **Step 6: Run the *Done when* criteria of step 5**

*Classpath* routine for `rig-format format-core intonation-format`, then:

```bash
sbtn "show app/projectDependencies" 2>&1 | grep -oE 'microtonalist-[a-z-]*format[a-z-]*' | sort -u
```

Expected:

```text
rig-format: base/businessync base/common format/format-core midi/sc-midi rig/rig tuner/tuner tuner/tuning tuner/tuning-changer
format-core: base/businessync base/common midi/sc-midi
intonation-format: base/businessync base/common format/format-core midi/sc-midi theory/intonation
microtonalist-format
```

- [ ] **Step 7: Check the ScalaDoc links**

*Doc links* routine, with `tuner=tuner/tuner tuner-mpe=tuner/tuner-mpe tuner-mts=tuner/tuner-mts
tuner-mpb=tuner/tuner-mpb format=format/format format-core=format/format-core
intonation-format=theory/intonation-format composition-format=theory/composition-format rig-format=rig/rig-format
tuner-format=tuner/tuner-format tuning-changer-format=tuner/tuning-changer-format rig=rig/rig
composition=theory/composition`. Expected: nothing from the `comm`.

- [ ] **Step 8: Run the tests**

*Tests* routine for `rig-format` and `format`. Expected: 21 tests in 3 suites; 1 in 1; `23 modules, 1479 tests`.

- [ ] **Step 9: Commit**

*Commit* routine, with `git add build.sbt format rig` and the message
`[#366/#380] Move the rig formats into rig-format`. Expected: `2 M` (`build.sbt`, `FormatModule`), 5 resource renames
`R100` and 10 Scala renames.

---

### Task 10: Set the coverage floors

**Files:**
- Modify: `build.sbt` (the `coverageSettings` of the new modules, `tuner` and `format`)

**Interfaces:**
- Consumes: the modules of Tasks 2–9.
- Produces: each module's floor; #179 retitled to the format modules; a coverage issue for each tuner module below
  80/80.

- [ ] **Step 1: Measure**

Invoke the `scoverage-inspector` skill. The coverage scripts don't work through `sbtn`, and take a few minutes:

```bash
bin/mtlist-coverage-modules tuner tuner-mpe tuner-mts tuner-mpb format-core intonation-format composition-format \
  rig-format tuner-format tuning-changer-format format
for m in tuner tuner-mpe tuner-mts tuner-mpb format-core intonation-format composition-format rig-format tuner-format \
  tuning-changer-format format; do
  bin/mtlist-scoverage-inspector module-summary "$m" --overall-only
done
```

What to expect, from the per-class coverage of `tuner` and `format` at `818e6e0`: the tuner modules and `tuner` at 80%
or more for both, the MPE and Monophonic Pitch Bend classes being near 98%; `tuner-format` near 100%. The other format
modules can't be predicted: their classes lose the coverage that the tests of the other format modules gave them, e.g.
`DefaultRepoSelector`, which the repos' tests cover, and the `Http*Repo`s are at 0%.

- [ ] **Step 2: Set the floors**

For each module, as the Global Constraints say: `coverageSettings(stmt = 80, branch = 80)` if it reaches 80% for both,
otherwise its measured coverage minus 3, rounded down, for each number, under the line
`    // TODO #179 Raise toward 80% statement and branch coverage.` for a format module, or a new issue's for a tuner
module or `tuner` (step 4). `format`'s line stays. Then rerun step 1's first command. Expected: every module passes its
floor.

- [ ] **Step 3: Update #179**

With the GitHub MCP's `issue_write` (method `update`), retitle #179 `Improve test coverage for the format modules to
80%` and give it the body, listing with step 1's numbers each format module below 80% for either:

```markdown
The format modules below are under the 80% coverage target. #380 split them out of `format`, and each kept only the tests of its own code.

**Current coverage:**
- `<module>`: statement <stmt>%, branch <branch>%

**Target:** 80% statement and branch coverage.

The configured threshold of each module is set to its current coverage minus a 3% buffer. As coverage improves, the thresholds in `build.sbt` should be raised toward 80%.
```

- [ ] **Step 4: Open the tuner modules' coverage issues, if any**

Only for a tuner module or `tuner` below 80% for either, modelled on #377, with step 1's numbers:

```bash
.claude/skills/contributing/scripts/microtonalist-gh issue 'Improve test coverage for `<module>` module to 80%' \
  "$(cat <<'EOF'
The `<module>` module, split out of `tuner` by #380, is below the 80% coverage target: its own tests cover less of its code than the tests of `tuner` did.

**Current coverage:**
- Statement: <stmt>%
- Branch: <branch>%

**Target:** 80% statement and branch coverage.

The configured threshold is set to current coverage minus a 3% buffer. As coverage improves, the threshold in `build.sbt` should be raised toward 80%.
EOF
)" --label refactoring
```

Its number goes in the module's `// TODO #<n>` line of step 2.

- [ ] **Step 5: Commit**

*Commit* routine, with `git add build.sbt` and the message `[#366/#380] Set the coverage floors of the new modules`.
Expected: `1 M`.

---

### Task 11: Document the new modules

**Files:**
- Create: `README.md`, `CLAUDE.md` and `docs/ARCHITECTURE.md` in each of `tuner/tuner-mpe/`, `tuner/tuner-mts/`,
  `tuner/tuner-mpb/`, `format/format-core/`, `theory/intonation-format/`, `theory/composition-format/`,
  `rig/rig-format/`, `tuner/tuner-format/` and `tuner/tuning-changer-format/` (`tuner-mpe/docs/` already holds the two
  MPE docs); not in `format/format-test-utils/`, a test-utility module
- Modify: `format/format/docs/ARCHITECTURE.md` (rewritten), `tuner/tuner/docs/ARCHITECTURE.md`,
  `tuner/tuning-changer/docs/ARCHITECTURE.md`

**Interfaces:**
- Consumes: the modules of Tasks 2–9; Task 1's link checker.
- Produces: the docs that Task 12's links and index rows point at.

- [ ] **Step 1: Invoke the `architecture-docs` skill**

`AGENTS.md` asks for it when a change adds a module. Its maintenance checklist applies to `tuner`'s and `format`'s
docs, which split. `format`'s doc, which carries a `TODO #360` for not following the conventions, is rewritten to them.

- [ ] **Step 2: Create the README and CLAUDE files**

For each `<group>/<id>` of the **Files**, `<group>/<id>/README.md`:

```markdown
# `<id>` module

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module's architecture.
```

and `<group>/<id>/CLAUDE.md`:

```markdown
# `<id>` module

@docs/ARCHITECTURE.md
```

- [ ] **Step 3: Write the tuner modules' docs**

`tuner/tuner-mpe/docs/ARCHITECTURE.md`:

```markdown
# `tuner-mpe` module architecture

## Responsibility

`tuner-mpe` holds `MpeTuner`, the [tuner](../../tuner/docs/ARCHITECTURE.md) of type `mpe`, which tunes polyphonically
with MIDI Polyphonic Expression (MPE): it gives each note a Member Channel of its own and bends that channel's pitch by
the offset of the note's pitch class.

## Key types

- **`MpeTuner`** — the tuner. Its `MpeInputMode` says whether the input is conventional MIDI, which it converts to MPE,
  or already MPE.
- **`MpeZones`** — the Lower and Upper `MpeZone`s of the output, which the MPE Configuration Messages of the input
  change.
- **`MpeMessageRouting`** — routes each input message by its channel's role in the Zones.
- **`MpeChannelAllocator`** — allocates the notes to Member Channels, whose state `MpeChannelState` tracks.

The message flow and the allocation are in [`mpe-tuner.md`](mpe-tuner.md), and the design, with where it departs from
the MPE Specification, in the [MPE Tuner paper](mpe-tuner-paper.md).
```

`tuner/tuner-mts/docs/ARCHITECTURE.md`:

```markdown
# `tuner-mts` module architecture

## Responsibility

`tuner-mts` holds the [tuners](../../tuner/docs/ARCHITECTURE.md) of the MIDI Tuning Standard (MTS): they retune the
output instrument's pitch table in advance, with an MTS Octave SysEx message, and pass the notes through.

## Key types

- **`MtsTuner`** — the base class of the four tuners, one per MTS Octave form: `MtsOctave1ByteNonRealTimeTuner`,
  `MtsOctave2ByteNonRealTimeTuner`, `MtsOctave1ByteRealTimeTuner` and `MtsOctave2ByteRealTimeTuner`. Its `thru` says
  whether the input messages reach the output too, which an instrument with local control on doesn't need.
- **`MtsMessageGenerator`** — encodes a `Tuning` as the SysEx message of one form: whole cents from -64 to +63 in the
  1-byte form, cents from -100 to +100 in the 2-byte form. Its `canEncode` tells whether a tuning fits.
```

`tuner/tuner-mpb/docs/ARCHITECTURE.md`:

```markdown
# `tuner-mpb` module architecture

## Responsibility

`tuner-mpb` holds `MonophonicPitchBendTuner`, the [tuner](../../tuner/docs/ARCHITECTURE.md) of type
`monophonicPitchBend`, which tunes each note with Pitch Bend. Pitch Bend applies to a whole channel, so the tuner folds
all input onto one output channel and plays one note at a time.

## Key types

- **`MonophonicPitchBendTuner`** — adds the offset of the note's pitch class to the performer's Pitch Bend. It tunes
  exactly only the offsets within its Pitch Bend Sensitivity, which it configures on reset and follows from the input,
  and clamps the others, with a warning.
```

- [ ] **Step 4: Update `tuner`'s and `tuning-changer`'s docs**

In `tuner/tuner/docs/ARCHITECTURE.md`, replace:

```markdown
`Tuner` is a plugin, which `format` (de)serializes by its `familyName` and `typeName`.
```

with:

```markdown
`Tuner` is a plugin, which [`tuner-format`](../../tuner-format/docs/ARCHITECTURE.md) (de)serializes by its
`familyName` and `typeName`.
```

replace the table and the paragraph under it:

```markdown
| Protocol | Tuner | How it tunes | Polyphony |
| -------- | ----- | ------------ | --------- |
| MTS (MIDI Tuning Standard) Octave, 1-byte/2-byte, real/non-real-time | `MtsOctave*Tuner` | An MTS SysEx retunes the instrument's pitch table in advance; notes pass through. | Polyphonic |
| Monophonic Pitch Bend | `MonophonicPitchBendTuner` | Pitch Bend, all input folded onto one output channel. | Monophonic |
| MPE | `MpeTuner` | Pitch Bend per note, on the MPE Member Channel it allocates to the note. | Polyphonic |

`MpeTuner` routes each message by its channel's role in the MPE Zones and allocates notes to Member Channels; see
[`mpe-tuner.md`](../../tuner-mpe/docs/mpe-tuner.md).
```

with:

```markdown
Each tuner has a module of its own, in a subpackage of `org.calinburloiu.music.microtonalist.tuner`:

| Protocol | Module | How it tunes | Polyphony |
| -------- | ------ | ------------ | --------- |
| MTS (MIDI Tuning Standard) Octave, 1-byte/2-byte, real/non-real-time | [`tuner-mts`](../../tuner-mts/docs/ARCHITECTURE.md) | An MTS SysEx retunes the instrument's pitch table in advance; notes pass through. | Polyphonic |
| Monophonic Pitch Bend | [`tuner-mpb`](../../tuner-mpb/docs/ARCHITECTURE.md) | Pitch Bend, all input folded onto one output channel. | Monophonic |
| MPE | [`tuner-mpe`](../../tuner-mpe/docs/ARCHITECTURE.md) | Pitch Bend per note, on the MPE Member Channel it allocates to the note. | Polyphonic |
```

and delete the *Subject to change* line `- The tuners will move to modules of their own: …` (#366).

In `tuner/tuning-changer/docs/ARCHITECTURE.md`, replace ``which `format` (de)serializes by its `typeName`.`` with
``which [`tuning-changer-format`](../../tuning-changer-format/docs/ARCHITECTURE.md) (de)serializes by its `typeName`.``
and rewrap the bullet at 120 columns.

- [ ] **Step 5: Write the format modules' docs**

`format/format/docs/ARCHITECTURE.md`, replacing the whole file:

```markdown
# `format` module architecture

## Responsibility

The format modules read the objects that Microtonalist persists, compositions, scales and rigs, from files, over HTTP
or from the user's library; most of their `write` methods are `???` stubs. `format` holds `FormatModule`, which wires
them for `app`.

Each of them but `format-core` is named after the module whose types it (de)serializes:

| Module | (De)serializes |
| ------ | -------------- |
| [`format-core`](../../format-core/docs/ARCHITECTURE.md) | nothing of its own: the infrastructure that the others share |
| [`intonation-format`](../../../theory/intonation-format/docs/ARCHITECTURE.md) | scales (`.jscl`, Scala's `.scl`) |
| [`composition-format`](../../../theory/composition-format/docs/ARCHITECTURE.md) | compositions (`.mtlist`) and their plugins |
| [`rig-format`](../../../rig/rig-format/docs/ARCHITECTURE.md) | rigs (`.mtlist.tracks`) and their track I/O plugins |
| [`tuner-format`](../../../tuner/tuner-format/docs/ARCHITECTURE.md) | the tuners of a rig's tracks |
| [`tuning-changer-format`](../../../tuner/tuning-changer-format/docs/ARCHITECTURE.md) | the tuning changers of a rig's tracks |

Each persisted object has a `*Format`, which (de)serializes it from a stream and knows nothing of the stream's source,
and a `*Repo`, which reads it from a `URI`. Its `Default*Repo` picks a `File*Repo`, an `Http*Repo` or, for scales and
rigs, a `Library*Repo` by the URI's scheme.

## Key types

- **`FormatModule`** — builds the formats and repositories, and exposes `defaultCompositionRepo`, `defaultScaleRepo`
  and `defaultRigRepo`. It gives `JsonRigFormat` the family formats of `tuner-format` and `tuning-changer-format`, so
  that `rig-format` depends on no tuner or tuning changer.
```

`format/format-core/docs/ARCHITECTURE.md`:

```markdown
# `format-core` module architecture

## Responsibility

`format-core` holds what the [format modules](../../format/docs/ARCHITECTURE.md) share: picking a repository by URI
scheme, resolving `$ref` references, (de)serializing plugins, deferring reads, and the JSON formats of MIDI values. It
depends on no other format module, and of the application on `common` and `sc-midi` only.

## Key types

- **`RepoSelector`** — picks a repository for a `URI`. `DefaultRepoSelector` keys on its scheme: none or `file`,
  `http` or `https`, and `microtonalist` for the user's library. The package object resolves relative URIs and library
  URLs (`resolveBaseUriWithOverride`, `resolveLibraryUrl`).
- **`JsonPreprocessor`** — replaces, before validation, each JSON object holding a `$ref` with the JSON it refers to,
  which its `JsonPreprocessorRefLoader`s load from a file or over HTTP. The loaded properties win over those beside the
  `$ref`. It guards against cycles and deep nesting; `NoJsonPreprocessor` resolves nothing.
- **`JsonPluginFormat`** — (de)serializes a plugin family by the `typeName` in the JSON: a `TypeSpec` per type gives
  the format of its settings, or a singleton for a type without settings. A family may have a default type, and a type
  without settings may be written as a bare string. A composition's global `settings` override a type's defaults, and
  a plugin's own settings override both.
- **`DeferrableRead`** — a value already read (`AlreadyRead`) or read later (`DeferredRead`), e.g. a scale that a
  composition refers to by URI. It caches the `Future` of its load.
- **MIDI value formats** — `JsonCommonMidiFormat`, `MidiNoteFormat`, `PitchClassFormat`, and the package object's
  `uint7Format` (0–127) and `ccNumberFormat` (0–119, MIDI 1.0 reserving 120–127 for the Channel Mode messages). The JSON
  Schemas under `json-schemas/v1/` mirror the last two as `uint7.schema.json` and `ccNumber.schema.json`; no test checks
  them, so they change with the formats.
```

`theory/intonation-format/docs/ARCHITECTURE.md`:

```markdown
# `intonation-format` module architecture

## Responsibility

`intonation-format` reads and writes the `Scale`s of [`intonation`](../../intonation/docs/ARCHITECTURE.md), in
Microtonalist's JSON (`.jscl`) and in the Huygens-Fokker Scala format (`.scl`), which it only reads. It depends on
neither `composition`, `rig` nor `tuner`, so that a tool such as `experiments` can load scales without them.

## Key types

- **`ScaleFormat`** — (de)serializes a scale: `JsonScaleFormat` and `HuygensFokkerScalaScaleFormat`.
  `ScaleFormatRegistry` picks one by media type, falling back to the file extension.
- **`ScaleRepo`** — reads a scale from a `URI`; `DefaultScaleRepo` picks the `FileScaleRepo`, `HttpScaleRepo` or
  `LibraryScaleRepo` by its scheme.
- **`ScaleFormatContext`** — what a scale read for a composition takes from it: a name and the composition's
  intonation standard. `ScaleContextConverter` applies it, and publishes a `ScaleLossyConversionEvent` on the
  `Businessync` when converting the intervals loses precision.
- **`JsonIntervalFormat`** and **`JsonIntonationStandardPluginFormat`** — the JSON formats of an `Interval` and of an
  `IntonationStandard`, which `composition-format` uses too.
```

`theory/composition-format/docs/ARCHITECTURE.md`:

```markdown
# `composition-format` module architecture

## Responsibility

`composition-format` reads and writes the `Composition`s of [`composition`](../../composition/docs/ARCHITECTURE.md), in
Microtonalist's JSON (`.mtlist`), with the formats of the composition plugins. It reads the scales that a composition
holds or refers to through [`intonation-format`](../../intonation-format/docs/ARCHITECTURE.md).

## Key types

- **`CompositionFormat`** — (de)serializes a composition. `JsonCompositionFormat` reads the JSON into a
  `CompositionRepr`, which mirrors its shape, loads the scales it refers to, concurrently and each once, and then builds
  the `Composition`.
- **`CompositionRepo`** — reads a composition from a `URI`; `DefaultCompositionRepo` picks the `FileCompositionRepo` or
  the `HttpCompositionRepo` by its scheme. Compositions have no library repository.
- **Plugin formats** — `JsonTuningMapperPluginFormat`, `JsonTuningReducerPluginFormat`,
  `JsonTuningReferencePluginFormat` and `JsonSoftChromaticGenusMappingPluginFormat`, with `KeyboardMappingFormat` for a
  manual tuning mapper's keys.
```

`rig/rig-format/docs/ARCHITECTURE.md`:

```markdown
# `rig-format` module architecture

## Responsibility

`rig-format` reads and writes the `RigSpec`s of [`rig`](../../rig/docs/ARCHITECTURE.md), in Microtonalist's JSON
tracks files (`.mtlist.tracks`), with the formats of the track I/O plugins. It implements `rig`'s `RigRepo`.

## Key types

- **`RigFormat`** — (de)serializes a rig. `JsonRigFormat` takes the formats of the tuner and tuning changer families as
  parameters, which `FormatModule` fills from [`tuner-format`](../../../tuner/tuner-format/docs/ARCHITECTURE.md) and
  [`tuning-changer-format`](../../../tuner/tuning-changer-format/docs/ARCHITECTURE.md): `rig-format` depends on the
  plugin contracts, `tuner` and `tuning-changer`, and on no plugin.
- **`RigRepo` implementations** — `DefaultRigRepo` picks the `FileRigRepo`, `HttpRigRepo` or `LibraryRigRepo` by the
  URI's scheme.
- **`JsonTrackInputSpecPluginFormat`** and **`JsonTrackOutputSpecPluginFormat`** — the formats of a track's input and
  output, a device or another track.

## Subject to change

- The tracks file keeps its JSON schema and `.mtlist.tracks` extension until they are renamed after the rig (#365).
```

`tuner/tuner-format/docs/ARCHITECTURE.md`:

```markdown
# `tuner-format` module architecture

## Responsibility

`tuner-format` (de)serializes the [tuners](../../tuner/docs/ARCHITECTURE.md) of a rig's tracks.
`JsonTunerPluginFormat`, the format of the `tuner` plugin family, gathers the type specs of the tuners, whose formats
are in a `format` subpackage of their tuner's package (`…` abbreviates `org.calinburloiu.music.microtonalist`):

| Tuner module | Its format |
| ------------ | ---------- |
| [`tuner-mpe`](../../tuner-mpe/docs/ARCHITECTURE.md) | `…tuner.mpe.format.JsonMpeTunerPluginFormating` |
| [`tuner-mts`](../../tuner-mts/docs/ARCHITECTURE.md) | `…tuner.mts.format.JsonMtsTunerPluginFormating`, a spec per MTS Octave form |
| [`tuner-mpb`](../../tuner-mpb/docs/ARCHITECTURE.md) | `…tuner.mpb.format.JsonMonophonicPitchBendTunerPluginFormating` |

Adding a tuner adds its format here.
```

`tuner/tuning-changer-format/docs/ARCHITECTURE.md`:

```markdown
# `tuning-changer-format` module architecture

## Responsibility

`tuning-changer-format` (de)serializes the [tuning changers](../../tuning-changer/docs/ARCHITECTURE.md) of a rig's
tracks. `JsonTuningChangerPluginFormat`, the format of the `tuningChanger` plugin family, gathers the type specs of the
tuning changers, whose formats are in a `format` subpackage of their tuning changer's package. So far there is
`org.calinburloiu.music.microtonalist.tuner.changer.pedal.format.JsonPedalTuningChangerPluginFormating`, for
[`tuning-changer-pedal`](../../tuning-changer-pedal/docs/ARCHITECTURE.md), whose triggers are Control Change numbers
(`format-core`'s `ccNumberFormat`).

Adding a tuning changer adds its format here.
```

Then, as the skill's checklist asks, run `mcp__metals__glob-search` for each type that these docs name and check that
it exists, e.g. `FormatModule`, `RepoSelector`, `DefaultRepoSelector`, `JsonPreprocessorRefLoader`,
`NoJsonPreprocessor`, `TypeSpec`, `AlreadyRead`, `DeferredRead`, `ScaleLossyConversionEvent`, `CompositionRepr`,
`JsonTrackInputSpecPluginFormat`, `JsonPedalTuningChangerPluginFormating`, `MpeChannelState`, `MtsMessageGenerator`.

- [ ] **Step 6: Check the links, the size budget and the lines**

```bash
git add tuner format theory rig
python3 "$SCRATCH/check_md_links.py" .
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py
for f in tuner/*/docs/*.md format/*/docs/*.md theory/*/docs/*.md rig/*/docs/*.md; do
  awk -v f="$f" 'length($0) > 120 {print f ":" NR ": " length($0)}' "$f"
done
```

Expected: `0 broken of 314 relative link(s) and import(s)`, 45 more than the baseline: 9 `README.md` links, 9
`CLAUDE.md` imports, 3 + 1 + 1 links in the tuner modules' docs, 3 more in `tuner`'s, 1 more in `tuning-changer`'s, 5
more in `format`'s (7 for 2 heading links), 1 in `format-core`'s, 1 in `intonation-format`'s, 2 in
`composition-format`'s, 3 in `rig-format`'s, 4 in `tuner-format`'s and 2 in `tuning-changer-format`'s. The size script
lists 33 docs and exits 0. The `awk` prints only table rows, which can't wrap, and the paper's lines if any; `awk`
counts bytes, so a line with `—` or `…` may show up to two or four more than its width in characters.

- [ ] **Step 7: Commit**

*Commit* routine, with `git add tuner format theory rig` and the message
`[#366/#380] Document the tuner and format modules`. Expected: `3 M` and `27 A`.

---

### Task 12: Point the other docs at the new modules

**Files:**
- Modify: `docs/architecture/module-overview.md`, `docs/architecture/README.md`, `docs/architecture/domain-concepts.md`,
  `docs/architecture/data-flow.md`
- Modify: `app/app/docs/ARCHITECTURE.md`, `base/common/docs/ARCHITECTURE.md`, `base/businessync/docs/ARCHITECTURE.md`,
  `rig/rig/docs/ARCHITECTURE.md`, `theory/composition/docs/ARCHITECTURE.md`, `theory/intonation/docs/ARCHITECTURE.md`
- Modify: `docs/development/test-conventions.md`, `docs/development/coverage.md`

**Interfaces:**
- Consumes: Task 11's docs; Task 1's link checker and grep.

- [ ] **Step 1: Replace `module-overview.md`'s tree**

```text
app
├── ui                    (GUI, depends on tuner + tuning)
├── format                (wiring of the format modules, depends on them + rig + businessync)
├── composition-format    (composition formats, depends on composition + intonation-format)
├── rig-format            (rig formats, depends on rig + tuner + tuning-changer + format-core)
├── tuner-format          (tuner plugin formats, depends on the tuner modules + format-core)
├── tuning-changer-format (tuning changer plugin formats, depends on tuning-changer-pedal + format-core)
├── intonation-format     (scale formats, depends on intonation + format-core)
├── format-core           (format infrastructure and MIDI value formats, depends on sc-midi)
├── composition           (domain model, depends on intonation + tuning)
├── rig                   (setup of per-instrument MIDI tracks, depends on tuner + tuning-changer)
├── tuner-mpe             (MPE tuner, depends on tuner)
├── tuner-mts             (MTS tuners, depends on tuner)
├── tuner-mpb             (Monophonic Pitch Bend tuner, depends on tuner)
├── tuning-changer-pedal  (pedal tuning changer, depends on tuning-changer)
├── tuning-changer        (contract of the tuning changers, depends on tuning + sc-midi)
├── tuner                 (MIDI tuning, depends on tuning + sc-midi + businessync)
├── tuning                (Tuning and TuningChange values, depends on sc-midi)
├── sc-midi-javamidi      (Java Sound implementation of sc-midi, with CoreMIDI4J, depends on sc-midi + businessync)
├── sc-midi               (Scala-idiomatic MIDI API, depends on businessync)
├── intonation            (interval math, no application deps)
├── businessync           (event bus + threading, no application deps)
├── common                (shared utilities)
└── config                (HOCON config, depends on common)
```

- [ ] **Step 2: Add the modules to the *Packages* table and say who holds the shared packages**

In `module-overview.md`'s *Packages* table, add after the `composition` row:

```text
| `composition-format` | `theory/composition-format` | `org.calinburloiu.music.microtonalist.composition.format` |
```

after the `tuning-changer-pedal` row:

```text
| `tuner-mpe` | `tuner/tuner-mpe` | `org.calinburloiu.music.microtonalist.tuner.mpe` |
| `tuner-mts` | `tuner/tuner-mts` | `org.calinburloiu.music.microtonalist.tuner.mts` |
| `tuner-mpb` | `tuner/tuner-mpb` | `org.calinburloiu.music.microtonalist.tuner.mpb` |
| `tuner-format` | `tuner/tuner-format` | `org.calinburloiu.music.microtonalist.tuner.format` |
| `tuning-changer-format` | `tuner/tuning-changer-format` | `org.calinburloiu.music.microtonalist.tuner.changer.format` |
```

after the `rig` row, after the `format` row and after the `intonation` row, respectively:

```text
| `rig-format` | `rig/rig-format` | `org.calinburloiu.music.microtonalist.rig.format` |
| `format-core` | `format/format-core` | `org.calinburloiu.music.microtonalist.format` |
| `intonation-format` | `theory/intonation-format` | `org.calinburloiu.music.intonation.format` |
```

and, after the paragraph on `tuning` and `tuner` below the table:

```markdown

`format-core`, `format-test-utils` and `format` share the `org.calinburloiu.music.microtonalist.format` package:
`format` holds `FormatModule`, `format-test-utils` the `JsonFormatTestUtils` test helpers, and `format-core` the rest of
the package. A plugin's format is in a `format` subpackage of the plugin's package, e.g.
`org.calinburloiu.music.microtonalist.tuner.mpe.format`, which `tuner-format` holds for the tuners and
`tuning-changer-format` for the tuning changers.
```

- [ ] **Step 3: Update the index and the other cross-cutting docs**

In `docs/architecture/README.md`'s *Module index*, add rows of the form
``| `<id>` | [<group>/<id>/docs/ARCHITECTURE.md](../../<group>/<id>/docs/ARCHITECTURE.md) |``:
`composition-format` after `composition`; `tuner-mpe`, `tuner-mts`, `tuner-mpb`, `tuner-format` and
`tuning-changer-format` after `tuning-changer-pedal`; `rig-format` after `rig`; `format-core` after `format`;
`intonation-format` after `intonation`.

In `docs/architecture/domain-concepts.md`, replace:

```markdown
  limits, which it clamps any other tuning to; implementations cover the supported tuning protocols (MTS Octave, MPE,
  monophonic Pitch Bend)
```

with:

```markdown
  limits, which it clamps any other tuning to; each implementation covers a tuning protocol in a module of its own:
  MTS Octave (`tuner-mts`), MPE (`tuner-mpe`) and monophonic Pitch Bend (`tuner-mpb`)
```

In `docs/architecture/data-flow.md`, `→ DefaultCompositionRepo (format module)` becomes
`→ DefaultCompositionRepo (composition-format module)`: the stage changed module.

- [ ] **Step 4: Fix the module docs that put the moved types in `format`**

In `app/app/docs/ARCHITECTURE.md`'s *Dependencies* block, `app → format → { common, composition, rig, tuner,
tuning-changer-pedal }` becomes:

```text
app → format → { format-core, intonation-format, composition-format, rig-format, tuner-format, tuning-changer-format }
```

In `base/common/docs/ARCHITECTURE.md`, ``(`tuner`, `rig`, `format`,`` becomes ``(`tuner`, `rig`, `format-core`,``;
replace:

```markdown
`TrackInputSpec` / `TrackOutputSpec`). The serialization side lives in `format` (`JsonPluginFormat[P]` keys off these
names); the `Plugin` trait itself carries only the two `val`s and no serialization logic.
```

with:

```markdown
`TrackInputSpec` / `TrackOutputSpec`). The serialization side lives in the format modules (`format-core`'s
`JsonPluginFormat[P]` keys off these names); the `Plugin` trait itself carries only the two `val`s and no
serialization logic.
```

and replace:

```markdown
`format`, `sc-midi`, `sc-midi-javamidi`, and `app`, and transitively by `ui` (via `tuner`) and by `cli`, `tuning` and
`tuning-changer-pedal` (via `sc-midi`); `experiments` does not depend on it.
```

with:

```markdown
`format-core`, `sc-midi`, `sc-midi-javamidi`, and `app`, and transitively by every other module but `intonation`,
`businessync`, `experiments` and the test-utility modules.
```

In `base/businessync/docs/ARCHITECTURE.md`, replace the paragraph that starts `Modules that depend on it:` with:

```markdown
Modules that depend on it: `sc-midi` (defines `MidiEvent`), `sc-midi-javamidi` (publishes `MidiEvent`s), `tuner`
(publishes `TuningEvent`), `rig` (publishes `RigEvent`, subscribes to `TuningEvent` and `MidiEvent`),
`intonation-format` (publishes `ScaleLossyConversionEvent`), `format` (hands the instance to `intonation-format`),
`app` (constructs the instance and wires it in), `cli` (constructs one for `JavaMidiManager`), and transitively `ui`
(subscribes to `TuningIndexUpdatedEvent`). The instance is created once in `MicrotonalistApp.run` and injected
everywhere, so a single bus/threading context is shared across the running app.
```

In `rig/rig/docs/ARCHITECTURE.md`, replace:

```markdown
It doesn't read or write the tracks files that store rigs: the `RigRepo` trait lives here, its implementations in
`format`.
```

with:

```markdown
It doesn't read or write the tracks files that store rigs: the `RigRepo` trait lives here, and
[`rig-format`](../../rig-format/docs/ARCHITECTURE.md) implements it.
```

and replace:

```markdown
`TrackInputSpec` and `TrackOutputSpec` are plugins, which `format` (de)serializes by their `familyName` and `typeName`.
```

with:

```markdown
`TrackInputSpec` and `TrackOutputSpec` are plugins, which `rig-format` (de)serializes by their `familyName` and
`typeName`.
```

In `theory/composition/docs/ARCHITECTURE.md`, replace:

```markdown
The module is pure domain logic: it knows nothing about file formats (that is `format`), MIDI transport, or the GUI. It
manipulates intervals and produces `Tuning` values; deciding when/how those reach an instrument is the job of `tuner`
and `rig`.
```

with:

```markdown
The module is pure domain logic: it knows nothing about file formats (that is
[`composition-format`](../../composition-format/docs/ARCHITECTURE.md)), MIDI transport, or the GUI. It manipulates
intervals and produces `Tuning` values; deciding when/how those reach an instrument is the job of `tuner` and `rig`.
```

replace:

```markdown
`format` uses to select the right Play-JSON (de)serializer. The `*.Default` value in each companion is the variant
chosen when a composition does not specify one.
```

with:

```markdown
`composition-format` uses to select the right Play-JSON (de)serializer. The `*.Default` value in each companion is the
variant chosen when a composition does not specify one.
```

and replace:

```markdown
Depended on by **`format`** (which serializes every type here) and **`app`** (which loads a `Composition` and calls
`TuningList.fromComposition`). Its coverage floor is set high (statement and branch both 80%), reflecting its status as
a core, logic-heavy domain module.
```

with:

```markdown
Depended on by **`composition-format`** (which serializes every type here) and **`app`** (which loads a `Composition`
and calls `TuningList.fromComposition`). Its coverage floor is set high (statement and branch both 80%), reflecting its
status as a core, logic-heavy domain module.
```

In `theory/intonation/docs/ARCHITECTURE.md`, replace:

```markdown
- Higher modules build on it: `composition` models a piece in terms of `Scale`s and `IntonationStandard`s, `format`
  (de)serializes scales, and `tuner` ultimately consumes the resulting cent offsets.
```

with:

```markdown
- Higher modules build on it: `composition` models a piece in terms of `Scale`s and `IntonationStandard`s,
  [`intonation-format`](../../intonation-format/docs/ARCHITECTURE.md) reads and writes scales, and `tuner` ultimately
  consumes the resulting cent offsets.
```

replace ``serialization in `format`.`` with ``serialization in `intonation-format`.`` and rewrap that paragraph at 120
columns, and replace:

```markdown
It is depended on by `composition` (models compositions in terms of `Scale`/`IntonationStandard`), `experiments`
(research executables over scales), and `app` directly; `format` reaches it transitively through `composition`.
```

with:

```markdown
It is depended on by `composition` (models compositions in terms of `Scale`/`IntonationStandard`), `intonation-format`
and `composition-format` (their JSON formats), `experiments` (research executables over scales), and `app`.
```

- [ ] **Step 5: Fix the development docs**

In `docs/development/test-conventions.md`, ``because `rig` doesn't depend on `format`.`` becomes
``because `rig` doesn't depend on `rig-format`.``. In `docs/development/coverage.md`, ``although `format`'s tests
exercise `common`:`` becomes ``although `format-core`'s tests exercise `common`:``, and
`bin/mtlist-coverage-modules common format` becomes `bin/mtlist-coverage-modules common format-core`:
`format-core`'s `DeferrableReadTest` exercises `common`'s `Locking`.

- [ ] **Step 6: Check that the *Packages* table leads the Metals tools to the right module**

Run `mcp__metals__get-usages` with each pair below. Expected: a non-empty result for each.

| `fqcn` | `module` |
| ------ | -------- |
| `org.calinburloiu.music.microtonalist.format.JsonPluginFormat` | `format-core` |
| `org.calinburloiu.music.microtonalist.format.FormatModule` | `format` |
| `org.calinburloiu.music.microtonalist.format.JsonFormatTestUtils` | `format-test-utils` |
| `org.calinburloiu.music.intonation.format.ScaleRepo` | `intonation-format` |
| `org.calinburloiu.music.microtonalist.tuner.mpe.format.JsonMpeTunerPluginFormating` | `tuner-format` |
| `org.calinburloiu.music.microtonalist.tuner.changer.pedal.format.JsonPedalTuningChangerPluginFormating` | `tuning-changer-format` |
| `org.calinburloiu.music.microtonalist.tuner.mts.MtsTuner` | `tuner-mts` |

- [ ] **Step 7: Check the links, the lines and the statements**

```bash
python3 "$SCRATCH/check_md_links.py" .
for f in docs/architecture/*.md docs/development/coverage.md docs/development/test-conventions.md \
  app/app/docs/ARCHITECTURE.md base/*/docs/ARCHITECTURE.md rig/rig/docs/ARCHITECTURE.md \
  theory/*/docs/ARCHITECTURE.md; do
  awk -v f="$f" 'length($0) > 120 {print f ":" NR ": " length($0)}' "$f"
done
```

Expected: `0 broken of 326 relative link(s) and import(s)`, 12 more than Task 11's: the 9 *Module index* rows and the
links in `rig`'s, `composition`'s and `intonation`'s docs; the `awk` prints only table rows. Then rerun Task 1, step 6.
Expected: nothing. Fix any line it prints.

- [ ] **Step 8: Update #360**

#360 lists `format`'s doc among those that don't follow the conventions; Task 11 rewrote it into conforming docs. With
the GitHub MCP's `issue_write` (method `update`), drop from #360's body the bullet on
`docs/architecture/format/README.md`, keeping the rest of the body as it is.

- [ ] **Step 9: Commit**

*Commit* routine, with `git add docs app base rig theory` and the message
`[#366/#380] Point the docs at the tuner and format modules`. Expected: `12 M`.

---

### Task 13: Final checks

**Files:**
- Modify: whatever `sbtn fixLint` reformats (expected: the imports that Tasks 2 and 5–9 added)

**Interfaces:**
- Consumes: everything above; Task 1's baselines.

- [ ] **Step 1: Full test suite**

*Tests* routine, the full suite only. Expected: `23 modules, 1479 tests` and no other line.

- [ ] **Step 2: Lint**

Lint comes before coverage, so that a reformatted file doesn't leave a stale report behind.

```bash
sbtn fixLint
rtk proxy git status --short
```

Expected: `fixLint` passes. If it reformatted files, commit them:
`git commit -am "[#366/#380] Format the code" -m "<attribution lines>"`.

- [ ] **Step 3: Coverage**

Invoke the `scoverage-inspector` skill, and run the whole workflow, as CI does:

```bash
bin/mtlist-coverage-all
```

Then `bin/mtlist-scoverage-inspector module-summary <m> --overall-only` for each module of Task 10, step 1. Expected:
every module passes its floor and the aggregate passes its own; each module as Task 10 measured it.

- [ ] **Step 4: The *Done when* criteria**

Rerun Task 2, step 7 and Task 9, step 6. Expected: the same output.

- [ ] **Step 5: Docs and ScalaDoc links**

Rerun Task 11, step 6, Task 12, step 7 and Task 9, step 7. Expected: the same results.

- [ ] **Step 6: Metals**

Run `mcp__metals__list-modules` (59 build targets) and the `get-usages` of Task 12, step 6. Expected: the same results.

- [ ] **Step 7: Hand over**

Don't push and don't open a PR. Report to the user the results of the steps above and of the final review, with the
branch's commits (`git log --oneline 818e6e0..HEAD`), the floors of Task 10 and the issues it updated or opened, and
the #360 update. Remind them that an IDE such as IntelliJ must re-import the sbt project to see the new modules.
