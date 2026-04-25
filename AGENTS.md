# AGENTS.md / CLAUDE.md

This file provides guidance to coding agents (e.g. Claude Code) when working with code in this repository.

Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing
music with microtones. It supports various protocols for tuning output instruments like MIDI Tuning Standard (MTS),
Monophonic Pitch Bend and MIDI Polyphonic Expression (MPE). It is built as a stand-alone multi-platform desktop
application that runs on JVM. The code is written in Scala 3.

# Code Intelligence

At the start of every conversation, check whether the Metals MCP is available by attempting to call
`mcp__metals__list-modules`. If it is available, you have the following capabilities through it:

- **Symbol inspection** — inspect classes, traits, objects, and methods by fully qualified name (`mcp__metals__inspect`)
- **Symbol search** — search for symbols by name glob (`mcp__metals__glob-search`) or by type (
  `mcp__metals__typed-glob-search`)
- **Find usages** — find all references to a symbol across the project (`mcp__metals__get-usages`)
- **Read source** — retrieve source of any symbol on the classpath, including JDK and library classes (
  `mcp__metals__get-source`)
- **Read docs** — retrieve ScalaDoc/JavaDoc for any symbol (`mcp__metals__get-docs`)
- **Compile** — compile the full project or a single module (`mcp__metals__compile-full`, `mcp__metals__compile-module`)
- **Dependency lookup** — find available versions of Maven dependencies via Coursier (`mcp__metals__find-dep`)

Prefer the Metals MCP over calling `sbt` processes for compiling code as detailed in the Build section below.

Prefer the Metals MCP over `grep` for symbol inspection, symbol search, finding usages, and understanding class/trait
hierachy. Use symbol search to reduce duplicated code by finding already implemented functionality. Use the read docs
functionality to understand external code.

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
libraries, or separate executable applications. Each project is in the repository root. Check `build.sbt` for details.
The `root` SBT project aggregates all the other projects. The executable application is in `app` SBT project.

## Metals MCP warm-up

At the start of every conversation, if the Metals MCP is available, run a full compile via `mcp__metals__compile-full`
to warm up the Metals index. This ensures SemanticDB is populated so that symbol resolution, find-usages, and other
semantic tools work correctly from the first query. Require sbt to be running as a BSP server beforehand (the user is
responsible for that).

## Compiling

Prefer the Metals MCP for compiling when it is available:

- Compile the whole project: `mcp__metals__compile-full`
- Compile a single module `${MODULE}`: `mcp__metals__compile-module` with `module = "${MODULE}"`

Fall back to `sbt` only when the Metals MCP is not available, or for a final full build or fat JAR assembly:

Compiling the whole `root` project:

```bash
sbt compile
```

Building the fat JAR for the executable application:

```bash
sbt assembly
```

It is recommended to compile, build, or test the whole project before committing changes.

For small changes, it is recommended to only compile individual SBT projects.

Compiling a single SBT project `${PROJECT}` via sbt:

```bash
sbt "${PROJECT}/compile"
```

# Test

Tests and production code follow the default Scala and SBT directory structure. For each SBT project, production code is
in `src/main/scala` and tests are in `src/test/scala`. Tests are written using ScalaTest 3. Use `src/test/resources` for
test data if necessary.

Conventionally, the tests for a given production class use the same package and class name is suffixed with `Test`. For
example, the test class for `org.calinburloiu.music.intonation.RatioInterval` is
`org.calinburloiu.music.intonation.RatioIntervalTest`.

The project uses `scalatest` library for testing and `scalamock` for mocking / stubbing. The “behavior-driven” style of
development (BDD) is preferred for writting tests by making tests classes extend `org.scalatest.flatspec.AnyFlatSpec`
and `org.scalatest.matchers.should.Matchers`. When using this style of tests, test cases are grouped in behavior
sections by using `behavior of`. When adding a new test case to a behavior-driven suite consider the following:

* Analyze the current behavior section in the test file.
* Determine if there is an existing behavior section that is appropriate for the new test and if not create a new
  behavior section.
* Add the test in the determined section near a similar test. If there isn't a similar one, add it at the end of the
  behavior section.

Currently, Metals MCP cannot run tests with this setup (with SBT and BSP). So run all tests by starting `sbt` processes.

Running all tests:

```bash
sbt test
```

For small changes, it is recommended to only test individual files or SBT projects.

Testing a single SBT project `${PROJECT}`:

```bash
sbt "${PROJECT}/test"
```

Testing a single test class `${CLASS}` (declared with fully qualified name) in an SBT project `${PROJECT}`:

```bash
sbt "${PROJECT}/testOnly ${CLASS}"
```

For example:

```bash
sbt "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest"
```

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
`feature/mpe-tuner`, `bugfix/pitch-bend-overflow`, `enhancement/program-change-midi-msg-wrapper`.

The label in the branch name determines the label to apply to the corresponding issue and PR.

## Issues

When creating a new issue:

- Assign the **microtonalist** GitHub project.
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

* Indentation is done with 2 spaces.
* Lines have a maximum length of 120 characters.
* Currently, we use IntelliJ IDEA for formatting code with the default settings.
* All public identifiers (classes, methods, fields, etc.) are properly documented via ScalaDocs.

## Follow the TDD cycle: red/green/refactor

Use strict Test Driven Development (TDD) by following the red/green/refactor cycle.

Write a failing test first — if the compiler requires it, create the thinnest possible stub (`???` bodies, no logic) to
get it to compile, then confirm the test fails for the right reason. Write only enough production code to make it pass,
no more. Once green, refactor the structure and naming freely, keeping the suite green throughout. Never write logic
without a preceding failing test, never commit red production code, and never mix refactoring with behavioral changes.

## Use Given / When / Then comments in tests

Tests cases should be written using the `Given` / `When` / `Then` format. Some tests may not have a `Given` section,
while others may have multiple instances if the three. It's acceptable to combine two, like `When / Then` in some cases.

Wrong:

```scala
  it should "map just Cireșar scale" in {
  val ciresar = RatiosScale("Cireșar", 1 /: 1, 9 /: 8, 6 /: 5, 9 /: 7, 3 /: 2, 8 /: 5, 9 /: 5, 27 /: 14, 9 /: 4)
  val mapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, quarterToneTolerance = 13)
  val tuning = mapper.mapScale(ciresar, cTuningReference)

  tuning.completedCount shouldEqual 8
  tuning.eFlat shouldEqual 15.64
}
```

Correct:

```scala
  it should "map just Cireșar scale" in {
  // Given
  val ciresar = RatiosScale("Cireșar", 1 /: 1, 9 /: 8, 6 /: 5, 9 /: 7, 3 /: 2, 8 /: 5, 9 /: 5, 27 /: 14, 9 /: 4)
  val mapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, quarterToneTolerance = 13)

  // When
  val tuning = mapper.mapScale(ciresar, cTuningReference)

  // Then
  tuning.completedCount shouldEqual 8
  tuning.eFlat shouldEqual 15.64
}
```

## Use fixtures to reduce duplication in test cases setup

Simplify the setup of test cases, typically the `Given` section, by using `trait` or `abstract class` fixtures. They
should contain code that repeats in many test cases. But be mindful not to sacrifice readability.

## No `if`s in tests

Do not use `if` statements in test code. Tests should always assert the condition rather than conditionally executing
assertions. An `if` silently skips assertions when the condition is false, hiding potential failures.

Wrong:

```scala
val output = tuner.tune(tuning)
if (output.nonEmpty) {
  output.head.value shouldBe expected
}
```

Correct:

```scala
val output = tuner.tune(tuning)
output should not be empty
output.head.value shouldBe expected
```

## Use brace syntax

Use the old classic brace Scala syntax, not the new indentation Scala 3 syntax.

Wrong:

```scala
case class Person(name: String, age: Int):
  def greet: String = s"Hi, I'm $name"
```

Correct:

```scala
case class Person(name: String, age: Int) {
  def greet: String = s"Hi, I'm $name"
}
```

## Use `enum`

Use Scala 3 `enum` instead of `sealed trait` for simple enumerations.

Wrong:

```scala
sealed trait MpeInputMode

case object NonMpe extends MpeInputMode

case object Mpe extends MpeInputMode
```

Correct:

```scala
enum MpeInputMode {
  case NonMpe
  case Mpe
}
```

## Avoid `case class` for mutable data structures

Avoid using case classes for data structures that expose mutable fields.

Wrong:

```scala
case class ActiveNote(midiNote: MidiNote,
                      var expressivePitchBend: Int = 0)
```

Correct:

```scala
class ActiveNote(val midiNote: MidiNote,
                 var expressivePitchBend: Int = 0)
```

## TODOs have issue numbers

All TODOs in the code use `// TODO #<issue_number>`, where `<issue_number>` is the issue number on the project's GitHub
repository: https://github.com/calinburloiu/microtonalist

Wrong:

```scala
// TODO Add support for Windows
```

Correct:

```scala
// TODO #149 Add support for Windows
```

## Prefer for-comprehensions for nested monads

Using a deep chain of `flatMap`, `map`, ..., `map` for nested monads can be hard to follow. Prefer for-comprehensions
for them.

Wrong:

```scala
val optionsList: List[Option[Int]] = List(Some(1), None, Some(2))
optionsList.flatMap(list => list.map(item => item * 2))
```

Correct:

```scala
val optionsList: List[Option[Int]] = List(Some(1), None, Some(2))
for {
  itemOption <- optionsList
  item <- itemOption
} yield item * 2
```

## Avoid `new` when instantiating a class

Wrong:

```scala
val c = new MyClass(x, y)
```

Correct:

```scala
val c = MyClass(x, y)
```

## No `return`

The `return` statement is deprecated in Scala 3. When a Scala idiomatic approach is cumbersome, use a `boundary.break`
instead. A typical case is an early return, an initial condition in a method, where a Scala idiomatic `if` would cause
most of the code to be overindented.

Wrong:

```scala
class C {
  def method(): Int = {
    if (notValid) {
      return -1
    }

    // Large block of code
    // ...
  }
}
```

Correct:

```scala
class C {
  def method(): Int = boundary {
    if (notValid) {
      boundary.break(-1)
    }

    // Large block of code
    // ...
  }
}
```

## Class private internal backing variables for public getter / setter

If a class has a public getter or setter that is backed by an internal `private` or `protected` variable, use the same
name for the interval variable with the getter / setter but prefixed by an underscore.

```scala
class C {
  private var _value: Int

  def value: Int = _value

  def value_=(newValue: Int): Unit = {
    _value = newValue
  }
}
```
