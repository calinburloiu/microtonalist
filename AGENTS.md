# AGENTS.md / CLAUDE.md

This file provides guidance to coding agents (e.g. Claude Code) when working with code in this repository.

Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing
music with microtones. It supports various protocols for tuning output instruments like MIDI Tuning Standard (_MTS_),
_Monophonic Pitch Bend_ and MIDI Polyphonic Expression (_MPE_). Users work with a sequence of _scales_, as a high-level
concept, and the application maps them to octave-based _tunings_, as a low-level concept, which assign a tuning value to
each pitch class of the keyboard.

Microtonalist is built as a stand-alone multi-platform desktop application that runs on JVM. The code is written in
Scala 3 and is built by using sbt 1.

# Coding Workflow

- Use Metals MCP for compiling and code intelligence (see [Code Intelligence](#code-intelligence) section); fall back to
  sbt only when it is unavailable (see [`docs/development/build.md`](docs/development/build.md)).
- Before writing code, create a task to explore the architecture: read the always-loaded overview imported in the
  [Architecture](#architecture) section (from [`docs/agents/architecture.md`](docs/agents/architecture.md)) plus the
  architecture documents strictly relevant to the prompt — typically the
  [`docs/architecture/$MODULE/README.md`](docs/architecture/) of each module you will touch and its immediate
  collaborators. That overview explains how the architecture documents are organized.
- Use strict Test Driven Development (_TDD_) by following the _red/green/refactor_ cycle:
    - **Red**. Write failing tests first. If the compiler requires it, create the thinnest possible stub (`???` bodies,
      no logic) to get them to compile, then confirm the tests fail for the right reason. The tests failure reason
      **shall not** be due to compile errors, iterate until the code compiles.
    - **Green**. Write only enough production code to make it pass, no more.
    - **Refactor**. Once green, refactor the structure and naming freely, keeping the suite green throughout.
    - Never write logic without a preceding failing test, never commit red production code, and never mix refactoring
      with behavioral changes.
- When the implementation is done, perform final checks by creating a task for each of the following checks:
    - **Module tests**. Make sure tests pass for each modified module.
    - **Coverage**. Make sure modified files and modules meet the coverage conventions and iterate
      until the target coverage is met. See [Coverage](#coverage) section for details.
    - **Full tests suite**. Make sure the full test suite for the whole project passes.
    - **Documentation**. Update documentation (ScalaDocs in code for all public identifiers, architecture docs, READMEs,
      guides etc.) and agent artifacts.
- If the user did not mention an issue for the work, ask if creating a new issue is necessary (use the `contributing`
  skill).
- If the user requested opening a PR, go ahead and open one with the assigned issue (given by the user or previously
  created). If the user did not request opening a PR, ask them if creating one is necessary (use the `contributing`
  skill).

# Code Intelligence

At the start of every conversation, check whether the Metals MCP is available by attempting to call
`mcp__metals__list-modules`. If it is available, prefer its `mcp__metals__*` tools (symbol inspection, search,
find-usages, source/docs retrieval, compilation, Coursier dependency lookup). See each tool's own description for
parameters. If Metals MCP is not available, fall back to the usual CLI tools (`sbt`/`sbtn`, `rg`, `find`, `WebFetch`,
etc.).

Prefer the Metals MCP over calling `sbt` processes for compiling code as detailed in the Build section below (TODO).

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
The executable application is in `app` SBT project. `cli` is CLI tool application for various utilities, like listing
the MIDI devices connected to the computer.

## sbt invocations: prefer the BSP server via `sbtn`

The development stack started by `bin/microtonalist-dev-stack start` runs a single long-lived sbt JVM that serves two
clients at once: Metals (via BSP) and the `sbtn` thin client (via the sbt server protocol). Run all sbt commands through
`sbtn` so they execute in that one JVM rather than spawning a fresh `sbt` JVM each time — spawning duplicates
compilation work and runs the second JVM with no awareness of the BSP server's incremental state. The per-project
`target` isolation described below is belt-and-braces protection: it keeps a stray second `sbt` from racing the BSP
server on the same `classes/` tree (which is what produced the TASTy load concurrency errors in issue #186), but routing
through `sbtn` is the primary fix.

The BSP-server sbt is launched with `-Dmicrotonalist.build.targetSuffix=-bsp` (see `targetSuffixOverride` in
`build.sbt`), so its compiled outputs live under `<project>/target-bsp/` rather than `<project>/target/`. CLI sbt
invocations without that property continue to use `<project>/target/`. The two trees never collide; `sbt clean` and
`sbtn clean` each clean the active tree.

## Warm-up

At the start of every conversation, **once** per session, do the following warm-up actions:

1. Detect the running stack with `bin/microtonalist-dev-stack status` (exit 0 if running, 1 if not). If it is not
running, follow [`docs/agents/dev-stack.md`](docs/agents/dev-stack.md) before continuing. Once the stack is up, every
subsequent sbt command should just use `sbtn …`. Trust it is up unless a command unexpectedly fails (e.g. with a connect
error), in which case re-run this check.
2. If the Metals MCP is available, run a full compile via `mcp__metals__compile-full`
to warm up the Metals index. This ensures SemanticDB is populated so that symbol resolution, find-usages, and other
semantic tools work correctly from the first query.

## Compiling

Prefer the Metals MCP for compiling when it is available:

- Compile the whole project: `mcp__metals__compile-full`
- Compile a single module `${MODULE}`: `mcp__metals__compile-module` with `module = "${MODULE}"`

Fall back to `sbt`/`sbtn` only when the Metals MCP is not available, or for a final full build or fat JAR assembly. The
sbt-based compile, single-module, and `assembly` commands are documented in
[`docs/development/build.md`](docs/development/build.md). Use `sbtn` rather than spawning a fresh `sbt` JVM whenever the
BSP server is up (see "sbt invocations: prefer the BSP server via `sbtn`" section above).

# Test

Metals MCP cannot run tests with this BSP setup, so run all tests through `sbtn` as instructed in @docs/agents/test.md .

For test conventions, see the "Coding conventions" section.

# Coverage

During the **Coverage** workflow step — and any time you verify coverage after changing code — invoke the
`scoverage-inspector` skill. It carries the coverage policy you must apply (per-module thresholds, the "never decrease
the floor" rule, the 80% target for new files, and the stop-and-wait behavior on the known scoverage TASTy concurrency
issue) and delegates mechanical XML inspection to its custom subagent. The policy lives in the skill — loaded on demand
when you invoke it — precisely so it does not clutter context up front, since coverage work only happens after the
implementation is finished.

# Architecture

Crucial architecture context — the module dependency graph, the key domain concepts, and the end-to-end data flow — is
imported below and loads in every session. Deeper, context-specific detail lives in the per-module and per-topic
documents under [`docs/architecture/`](docs/architecture/), each loaded on demand via its module's `CLAUDE.md`. The
imported overview also explains how those documents are organized, so you can find the ones relevant to a task.

@docs/agents/architecture.md

# Contributing (issues, PRs, branches)

GitHub conventions — issues, branches, pull requests, labels, milestones, and the Projects-v2 `gh` fallback — live in
the `contributing` skill (`.claude/skills/contributing/`). Invoke it when creating an issue or opening a PR.

# Coding Conventions

Follow these conventions whenever you write code. They are imported here so they are always in context:

- Production / general Scala conventions: @docs/development/coding-conventions.md
- Test conventions (directory layout, naming, BDD style, Given/When/Then, fixtures, shared test utilities):
  @docs/development/test-conventions.md
