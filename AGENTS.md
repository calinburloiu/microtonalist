# AGENTS.md / CLAUDE.md

This file provides guidance to coding agents (e.g. Claude Code) when working with code in this repository.

Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing
music with microtones. It supports various protocols for tuning output instruments like MIDI Tuning Standard (MTS),
Monophonic Pitch Bend and MIDI Polyphonic Expression (MPE). It is built as a stand-alone multi-platform desktop
application that runs on JVM. The code is written in Scala 3 and is built by using sbt 1.

# Coding workflow

- Use Metals MCP for compiling and code intelligence (see [Code Intelligence](#code-intelligence)); fall back to
  sbt only when it is unavailable (see [`docs/development/build.md`](docs/development/build.md)).
- Use strict Test Driven Development (TDD) by following the red/green/refactor cycle:
    - **Red**. Write failing tests first. If the compiler requires it, create the thinnest possible stub (`???` bodies,
      no logic) to get them to compile, then confirm the tests fail for the right reason. The tests failure reason
      **shall not** be due to compile errors, iterate until the code compiles.
    - **Green**. Write only enough production code to make it pass, no more.
    - **Refactor**. Once green, refactor the structure and naming freely, keeping the suite green throughout.
    - Never write logic without a preceding failing test, never commit red production code, and never mix refactoring
      with behavioral changes.
- When the implementation is done, perform final checks by creating a task for each:
    - Make sure tests pass for each modified module.
    - Make sure modified files and modules meet the coverage conventions and iterate
      until the target coverage is met. See [Coverage](#coverage) section for details.
    - Make sure the full test suite for the whole project passes.
    - Update architecture docs and current agent artifacts.
- If the user did not mention an issue for the work ask if creating a new issue is necessary.
- If the user requested opening a PR, go ahead and open one with the assigned issue (given by the user or previously
  created). If the user did not request opening a PR, ask them if creating one is necessary.

# Code Intelligence

At the start of every conversation, check whether the Metals MCP is available by attempting to call
`mcp__metals__list-modules`. If it is available, prefer its `mcp__metals__*` tools (symbol inspection, search,
find-usages, source/docs retrieval, compilation, Coursier dependency lookup) — see each tool's own description for
parameters. If it is not available, fall back to the usual CLI tools (`sbt`/`sbtn`, `rg`, `find`, `WebFetch`, etc.).

Prefer the Metals MCP over calling `sbt` processes for compiling code as detailed in the Build section below.

Prefer the Metals MCP over textual tools (`rg`, `grep`, `git grep`, `fd`, `find`) for symbol inspection, symbol search,
finding usages, and understanding class/trait hierarchy — the textual alternatives can't distinguish a class from a
same-named variable, follow overrides, or resolve imports. Use symbol search to reduce duplicated code by finding
already implemented functionality. Use the read docs functionality to understand external code.

## Symbol search file focus

`mcp__metals__glob-search` and `mcp__metals__typed-glob-search` require a `fileInFocus` parameter — they cannot infer
the build target without it. The search scope is limited to the classpath of the inferred build target, so always use a
file from a module with the broadest classpath.

The top-level modules and the representative files to use as `fileInFocus` for project-wide symbol searches are:

- `app` — covers `appConfig`, `businessync`, `common`, `composition`, `intonation`, `format`, `scMidi`, `tuner`, `ui`:
  `app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala`
- `cli` — separate executable covering `scMidi`; may contain symbols not in `app`:
  `cli/src/main/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolApp.scala`
- `experiments` — separate executable covering `intonation`; may contain symbols not in `app`:
  `experiments/src/main/scala/org/calinburloiu/music/microtonalist/experiments/SoftChromaticGenusStudy.scala`

For a full project-wide search, query all three in parallel. Only use a lower-level module file when intentionally
scoping the search to that module's classpath.

# Build

The repository is built using SBT 1, Scala 3, and Java 23. It is split into multiple SBT projects that act as modules,
libraries, or separate executable applications. We will simply call each of those SBT projects modules. Each one is
located in the repository root. Check `build.sbt` for details. The `root` SBT project aggregates all the other projects.
The executable application is in `app` SBT project.

## sbt invocations: prefer the BSP server via `sbtn`

The development stack started by `bin/microtonalist-dev-stack start` runs a single long-lived sbt JVM that serves two
clients at once: Metals (via BSP) and the `sbtn` thin client (via the sbt server protocol). Run all sbt commands through
`sbtn` so they execute in that one JVM rather than spawning a fresh `sbt` JVM each time — spawning duplicates compilation
work and runs the second JVM with no awareness of the BSP server's incremental state. The per-project `target` isolation
described below is belt-and-braces protection: it keeps a stray second `sbt` from racing the BSP server on the same
`classes/` tree (which is what produced the TASTy load errors in issue #186), but routing through `sbtn` is the primary
fix.

Once per session — together with the Metals MCP warm-up below, and not before every sbt call — detect the running stack
with `bin/microtonalist-dev-stack status` (exit 0 if running, 1 if not). If it is running, you are set: every subsequent
sbt command should just use `sbtn …`, and you can trust the stack is up unless a command unexpectedly fails (e.g. with a
connect error), in which case re-run this check. If it is not running — or you need to auto-start it, confirm `sbtn`
routing, fall back to `sbt`, or stop the stack — follow the steps in
[`docs/agents/dev-stack.md`](docs/agents/dev-stack.md).

The BSP-server sbt is launched with `-Dmicrotonalist.build.targetSuffix=-bsp` (see `targetSuffixOverride` in
`build.sbt`), so its compiled outputs live under `<project>/target-bsp/` rather than `<project>/target/`. CLI sbt
invocations without that property continue to use `<project>/target/`. The two trees never collide; `sbt clean` and
`sbtn clean` each clean the active tree.

## Metals MCP warm-up

At the start of every conversation, if the Metals MCP is available, run a full compile via `mcp__metals__compile-full`
to warm up the Metals index. This ensures SemanticDB is populated so that symbol resolution, find-usages, and other
semantic tools work correctly from the first query. Combine this with the "sbt invocations" check above.

## Compiling

Prefer the Metals MCP for compiling when it is available:

- Compile the whole project: `mcp__metals__compile-full`
- Compile a single module `${MODULE}`: `mcp__metals__compile-module` with `module = "${MODULE}"`

Fall back to `sbt`/`sbtn` only when the Metals MCP is not available, or for a final full build or fat JAR assembly. The
sbt-based compile, single-module, and `assembly` commands are documented in
[`docs/development/build.md`](docs/development/build.md). Use `sbtn` rather than spawning a fresh `sbt` JVM whenever the
BSP server is up (see "sbt invocations: prefer the BSP server via `sbtn`" above).

# Test

Metals MCP cannot run tests with this BSP setup, so run all tests through `sbtn`. The commands are imported below.
Conventions for *writing* tests (directory layout, naming, BDD style, fixtures, shared test utilities) live in
`docs/development/test-conventions.md`, imported from the Coding Conventions section.

@docs/agents/test.md

# Coverage

The coverage policy you must apply whenever you change code — per-module thresholds, the "never decrease the floor"
rule, the 80% target for new files, and the stop-and-wait behaviour on the known scoverage TASTy bug — is imported
below. Mechanical inspection is delegated to the `scoverage-inspector` skill.

@docs/agents/coverage.md

# Architecture

## Module Overview

The project uses a layered module structure. Dependency direction flows from `app` downward:

```
app
├── ui            (GUI, depends on tuner)
├── composition   (domain model, depends on intonation + tuner)
├── format        (JSON/file I/O, depends on composition + tuner)
├── tuner         (MIDI tuning, depends on sc-midi + businessync)
├── sc-midi       (Java MIDI wrappers, depends on businessync)
├── intonation    (interval math, no application deps)
├── businessync   (event bus + threading, no application deps)
├── common        (shared utilities)
└── config        (HOCON config, depends on common)
```

`cli` is a separate executable (utility tool) that depends only on `sc-midi`.

## Key Domain Concepts

**Intonation (`intonation` module, `org.calinburloiu.music.intonation`):**

- `Interval` (sealed trait) — base for `RatioInterval`, `CentsInterval`, `EdoInterval`, `RealInterval`; all support
  arithmetic in logarithmic space and conversion to cents
- `Scale[I <: Interval]` — ordered sequence of intervals with direction, helper methods for relative intervals and
  softness (entropy)
- `IntonationStandard` — enum-like sealed trait describing how intervals are expressed (`CentsIntonationStandard`,
  `JustIntonationStandard`, `EdoIntonationStandard`)

**Composition (`composition` module, `org.calinburloiu.music.microtonalist.composition`):**

- `Composition` — top-level container: holds an `IntonationStandard`, a `TuningReference`, a sequence of `TuningSpec`, a
  `TuningReducer`, and fill configuration
- `TuningSpec` — pairs a `Scale` with a `TuningMapper` and an optional transposition
- `TuningList` — the resolved sequence of `Tuning` objects built from a `Composition`

**Tuner (`tuner` module, `org.calinburloiu.music.microtonalist.tuner`):**

- `Tuning` — 12 optional cent offsets for pitch classes; `Tuning.Standard` is 12-EDO
- `Tuner` (trait, Plugin) — processes MIDI messages: `reset()`, `tune(tuning)`, `process(message)`; implementations
  cover all MTS Octave variants, MPE, and monophonic tuning via Pitch Bend
- `TuningChanger` (trait, Plugin) — decides when to change tuning by inspecting MIDI messages (e.g.,
  `PedalTuningChanger`)
- `Track` — one instrument pipeline: input device → `TuningChangeProcessor` → `TunerProcessor` → output device
- `TuningSession` / `TuningService` — holds current tuning index and exposes thread-safe API for changing it
- `TrackManager` — lifecycle manager for tracks; reacts to MIDI device events

**Plugin pattern:** `Tuner`, `TuningMapper`, `TuningReducer`, `TuningReference`, and `TuningChanger` all extend
`Plugin` (from `common`). Each plugin has a `familyName` and `typeName` used for JSON (de)serialization via Play JSON.

## Threading Model (Businessync)

`Businessync` (`businessync` module) manages two threads:

- **Business thread** — all domain/MIDI logic; annotate handlers with `@Subscribe` and call `businessync.run {}`
- **UI thread** — all GUI updates; use `businessync.runOnUi {}`

Cross-thread calls use `businessync.callAsync`. Never mutate domain state from the UI thread directly.

## Data Flow

```
JSON composition file
  → DefaultCompositionRepo (format module)
  → Composition
  → TuningList.fromComposition()   (applies TuningMapper, TuningReducer, fill)
  → TuningSession.tunings
  → TuningIndexUpdatedEvent (via Businessync)
  → TrackManager → Track.tune()
  → TunerProcessor → Tuner
  → MTS/MPE MIDI messages
  → MIDI output device
```

## Format / Serialization

All file I/O is in the `format` module (`org.calinburloiu.music.microtonalist.format`). Key types:

- `CompositionFormat` / `CompositionRepo` — reads JSON `.mtlist` composition files
- `ScaleFormat` / `ScaleRepo` — reads embedded JSON `.jscl` scales or Huygens-Fokker `.scl` files; scales can be inlined
  or referenced by URI
- `TrackFormat` / `TrackRepo` — reads `.mtlist.tracks` JSON files
- `JsonPreprocessor` — resolves `$ref`-style URIs (file or HTTP) before parsing
- `FormatModule` — lazy-initializes all repos/formats; inject this rather than constructing individual components

## Application Entry Point

`MicrotonalistApp` (`app` module) wires everything:

1. Parses CLI args (composition URI, optional config file)
2. Creates `Businessync` and starts business thread
3. Builds `FormatModule`, loads `Composition`, builds `TuningList`
4. Builds `TunerModule`, loads tracks
5. Opens `TuningListFrame` (Swing GUI)
6. Registers JVM shutdown hook for cleanup

Application config (HOCON) lives at `~/.microtonalist/microtonalist.conf` on macOS.

# Repository

Use the **GitHub MCP plugin** (`mcp__plugin_github_github__*`) for all GitHub operations (issues, PRs, labels,
milestones, etc.). Fall back to the `gh` CLI (`/usr/local/bin/gh`) only for features not available in the MCP, such as
managing GitHub Projects (v2).

## Labels

The following labels are used for issues, PRs, and as branch name prefixes:

- `feature` — a capability or component
- `bugfix` — fix for a defect
- `refactoring` — restructuring existing code without changing behavior
- `doc` — documentation-only changes
- `poc` — proof of concept or experimental work

## Branches

Branch names use the format `<label>/<kebab-case-description>`, where `<label>` is one of the labels above. Examples:
`feature/mpe-tuner`, `bugfix/pitch-bend-overflow`, `refactoring/program-change-midi-msg-wrapper`.

The label in the branch name determines the label to apply to the corresponding issue and PR.

## Issues

When creating a new issue:

- **Always** add the issue to the **microtonalist** GitHub project (Projects v2). The GitHub MCP does not support
  Projects v2, so use the `gh` CLI:
  ```bash
  gh project item-add 1 --owner calinburloiu --url https://github.com/calinburloiu/microtonalist/issues/<issue_number>
  ```
- Add the appropriate label (inferred from the branch name if available).
- Check existing milestones (`mcp__plugin_github_github__list_releases` or similar). If a milestone name matches the
  scope of the new work, suggest adding it to the user before assigning.

## Pull Requests

When creating a new pull request:

- **Title format:** `[#<issue_number>] <Short description>` (e.g. `[#151] Add ScProgramChangeMidiMessage`).
- **Body:** Include `Resolves #<issue_number>` to auto-close the linked issue on merge.
- **Draft state:** Always open new PRs as **draft**.
- **Project:** Assign the **microtonalist** GitHub project.
- **Label:** Use the same label as the linked issue.
- **Milestone:** Use the same milestone as the linked issue, if one is set.

# Coding Conventions

Follow these conventions whenever you write code. They are imported here so they are always in context:

- Production / general Scala conventions: @docs/development/coding-conventions.md
- Test conventions (directory layout, naming, BDD style, Given/When/Then, fixtures, shared test utilities):
  @docs/development/test-conventions.md
