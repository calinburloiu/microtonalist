# AGENTS.md / CLAUDE.md

This file provides guidance to coding agents (e.g. Claude Code) when working with code in this repository.

Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing
music with microtones. It supports various protocols for tuning output instruments like MIDI Tuning Standard (MTS),
Monophonic Pitch Bend and MIDI Polyphonic Expression (MPE). It is built as a stand-alone multi-platform desktop
application that runs on JVM. The code is written in Scala 3.

# Build

The repository is built using SBT 1, Scala 3 and Java 23. It is split in multiple SBT projects that act as modules,
libraries or separate executable applications. Each project is in the repository root. Check `build.sbt` for details.
The `root` SBT project aggregates all the other projects. The executable application is in `app` SBT project.

Compiling the whole `root` project:

```bash
sbt compile
```

Building the fat JAR for the executable application:

```bash
sbt assembly
```

It is recommended to compile, build or test the whole project before committing changes.

For small changes, it is recommended to only compile individual SBT projects.

Compiling a single SBT project `${PROJECT}`:

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
  cover all MTS Octave variants, MPE and monophonic tuning via Pitch Bend
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
