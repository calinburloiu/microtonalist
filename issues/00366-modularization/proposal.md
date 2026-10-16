# Modularization proposal (draft)

- **Date:** 2026-10-16
- **Based on commit:** `c8ada5c6` (`main`)
- **Issue:** #366
- **Status:** draft, revised after the first review. [`outlook.md`](outlook.md) keeps the longer-term ideas it leaves
  out.

Packages abbreviate `org.calinburloiu.music.microtonalist` as `…`.

## Goals

- Keep the domain modules free of the MIDI engine, the format layer and the UI.
- Give each tuner, each tuning changer and each family of formats a module of its own.
- Prepare `sc-midi` for publishing as a standalone library.
- Recompile and retest less on each change.

This is a best-effort step: adding a tuner or tuning changer still touches the format module that lists its family's
plugins. The outlook's feature module groups and app-owned registration remove that.

## Findings at `c8ada5c6`

- `composition` imports only `Tuning` and `DefaultCentsTolerance` from `tuner`, so the domain model compiles against
  the whole tuning engine (MPE, MTS, `businessync`, CoreMIDI4J). It also imports `PitchClass` and `MidiNote` from
  `sc-midi`, which `build.sbt` doesn't declare: they arrive through `tuner`.
- From `tuner`, `TuningChanger` and `TuningChangeTriggers` use only the `TuningChange` ADT, which the rest of the engine
  shares: the changers return it, `TuningService.changeTuning` takes its effective cases, `rig`'s
  `TuningChangeProcessor` passes it from one to the other, and `ui` builds an `IndexTuningChange`.
- `format` has 52 files (4.4k lines, 3.8k test lines) and depends on `composition`, `rig` and `tuner`. Its imports
  fall into clean groups:

  | Group | Files | Depends on |
  | ----- | ----- | ---------- |
  | Infrastructure | `RepoSelector`, `DefaultRepoSelector`, `UriScheme`, `JsonPreprocessor` and its 2 ref loaders, `DeferrableRead`, `JsonPluginFormat`, `JsonConstraints` | `common` |
  | MIDI values | `JsonCommonMidiFormat`, `MidiNoteFormat`, `PitchClassFormat`, the package object (`uint7Format`, `ccNumberFormat`) | `sc-midi` |
  | Scales | `ScaleFormat`, `JsonScaleFormat`, `HuygensFokkerScalaScaleFormat`, `ScaleRepo` and its 4 implementations, `ScaleContextConverter`, `ScaleFormat{Context,Metadata,Registry}`, `JsonIntervalFormat`, `JsonIntonationStandardPluginFormat` | `intonation`, `businessync` |
  | Compositions | `Composition{Format,Repo,Repr}`, `{Default,File,Http}CompositionRepo`, `JsonCompositionFormat`, the mapper, reducer, reference and soft-chromatic plugin formats, `KeyboardMappingFormat` | `composition` |
  | Rigs | `RigFormat`, `JsonRigFormat`, `{Default,File,Http,Library}RigRepo`, `JsonTrackIOPluginFormat` | `rig` |
  | Tuners | `JsonTunerPluginFormat`, `Json{Mpe,MonophonicPitchBend,Mts}TunerPluginFormating` | `tuner` |
  | Tuning changers | `JsonTuningChangerPluginFormat`, which holds the pedal changer's format too | `tuner` |

  `FormatModule` wires all of them.
- The `tuner` implementations don't reference each other: a tuner uses only the `Tuner` trait and `Tuning`, and the
  pedal changer only the `TuningChanger` trait, `TuningChange` and `TuningChangeTriggers` (98 lines):

  | Implementation | Main files | Main lines | Its format (lines) |
  | -------------- | ---------- | ---------- | ------------------ |
  | MTS | `MtsTuner`, `MtsMessageGenerator` | 282 | 91 |
  | Monophonic Pitch Bend | `MonophonicPitchBendTuner` | 357 | 57 |
  | MPE | the 7 `Mpe*` files | 2,529 (6,853 test) | 125 |
  | Pedal tuning changer | `PedalTuningChanger` | 158 | 76 |

- `JsonTunerPluginFormat` is an `object` with a hardcoded list of specs, and `JsonRigFormat` uses it and
  `JsonTuningChangerPluginFormat` as private implicits.
- Test helpers cross the future module boundaries. `format`'s tests use `JsonFormatTestUtils`, which needs only
  ScalaTest and Play JSON, and `FormatTestUtils`, whose three readers each serve the tests of one future module.
  `MtsTunerTest` uses `tuner`'s `TestTunings`, which `rig` already copies in trimmed form. `JsonRigFormatTest` reads
  real tuners and tuning changers, and `RigModuleIntegrationTest` uses `PedalTuningChanger`.
- `ui` uses three `tuner` symbols: `TuningService`, `IndexTuningChange` and `TuningIndexUpdatedEvent`.
- Outside `sc-midi`'s `javamidi` package (1.5k lines), `javax.sound` appears only in ScalaDoc links, and only `app` and
  `cli` import `javamidi`.
- What ties `sc-midi` to the rest of the repo:
  - Its only use of `common` is `Locking`, in 5 files (2 in the core, 3 in `javamidi`).
  - `MidiEvent extends BusinessyncEvent`, and `JavaMidiManager` takes a `Businessync` to publish its events.
    `BusinessyncEvent`'s only member, `name`, is unused, and `cli` builds a `Businessync` only to construct
    `JavaMidiManager`.
- `MidiNote.pitchClass` returns a `PitchClass`, so `PitchClass` can't leave `sc-midi`.
- `intonation.ConcertPitchFreq` and `scmidi.DefaultConcertPitchFreq` are both 440.0.

## Proposal

### 1. `tuning` and `tuning-changer` modules

Split the contracts of `tuner` into three modules, `tuner` and `tuning-changer` being siblings; the implementations
move out too (3):

| Module | Package | Holds | Depends on |
| ------ | ------- | ----- | ---------- |
| `tuning` | `…tuner` | `Tuning`, `DefaultCentsTolerance`, the `TuningChange` ADT | `sc-midi` |
| `tuner` | `…tuner` | `Tuner`, `TuningSession`, `TuningService`, `TuningEvent`, `TunerModule` | `tuning`, `sc-midi`, `businessync`, `common` |
| `tuning-changer` | `…tuner.changer` | `TuningChanger`, `TuningChangeTriggers` | `tuning`, `sc-midi`, `common` |

- `composition` depends on `tuning` instead of `tuner`, and declares its `sc-midi` dependency. `rig` depends on `tuner`
  and `tuning-changer`.
- `TuningChange` goes to `tuning` because both siblings need it. Leaving it in `tuning-changer` would take a
  `TuningService` method per effective change, which changes the service's API and `ui`'s call for little gain.
- `tuning` and `tuner` keep the `…tuner` package, so their imports don't change. `tuning-changer` gets a package of
  its own, so that the tuning changers have a namespace (3); `rig` and the format code change their `TuningChanger`
  imports.
- For a package that several modules share, `…tuner` and `…format`, the Packages table in `module-overview.md` says
  which module holds what, since the Metals symbol tools need the owning module.
- Related open work: the `composition` refactor (#87) and scale lineage in tunings (#34), which would make `tuning`
  depend on `intonation`.

### 2. Split `format`

A format module is named after the module whose types it formats, with `format` last as in its package, e.g.
`rig-format` in `…rig.format`. It sits in that module's group (6), a step toward the outlook's feature groups; only
`format-core`, the `format` aggregator and `format-test-utils` stay in the `format` group.

| Module | Package | Holds | Depends on |
| ------ | ------- | ----- | ---------- |
| `format-core` | `…format` | the infrastructure and the MIDI value formats | `common`, `sc-midi` |
| `intonation-format` | `org.calinburloiu.music.intonation.format` | the scale formats and repos | `format-core`, `intonation`, `businessync` |
| `composition-format` | `…composition.format` | the composition formats and repos, with the composition plugin formats | `intonation-format`, `composition` |
| `rig-format` | `…rig.format` | the rig formats and repos, with the track I/O plugin formats | `format-core`, `rig` |
| `tuner-format` | `…tuner.format` | `JsonTunerPluginFormat` and the tuner plugin formats | `format-core`, `tuner-mpe`, `tuner-mts`, `tuner-mpb` |
| `tuning-changer-format` | `…tuner.changer.format` | `JsonTuningChangerPluginFormat` and the tuning changer plugin formats | `format-core`, `tuning-changer-pedal` |
| `format` | `…format` | `FormatModule`, which wires the modules above | all of the above |

- Each plugin's format goes in a `format` subpackage of its plugin's package (3), e.g. `…tuner.mpe.format` for
  `MpeTuner`'s. `JsonTuningChangerPluginFormat` splits into the family format and the pedal changer's format.
- `JsonRigFormat` takes the tuner and tuning changer formats as constructor parameters, which `FormatModule` fills, so
  that `rig-format` doesn't depend on every plugin. Its tests depend on `tuner-format` and `tuning-changer-format` in
  `Test` scope.
- Of the format modules, `app` depends only on `format`, until the outlook's app-owned registration replaces it.
- `intonation-format` depends on neither `composition`, `rig` nor `tuner`, so `experiments` can load scales without
  them.
- `JsonFormatTestUtils` moves to a new `format-test-utils` module, which the tests of every format module depend on,
  and each `FormatTestUtils` reader moves to the tests of the module it serves.

### 3. A module per tuner and tuning changer

Each implementation moves to a module of its own, in a subpackage of its family's package:

| Module | Package | Holds | Depends on |
| ------ | ------- | ----- | ---------- |
| `tuner-mpe` | `…tuner.mpe` | the 7 `Mpe*` files | `tuner` |
| `tuner-mts` | `…tuner.mts` | `MtsTuner`, `MtsMessageGenerator` | `tuner` |
| `tuner-mpb` | `…tuner.mpb` | `MonophonicPitchBendTuner` | `tuner` |
| `tuning-changer-pedal` | `…tuner.changer.pedal` | `PedalTuningChanger` | `tuning-changer` |

- Only `tuner-format` and `tuning-changer-format` depend on them in `Compile` scope, and `rig` and `rig-format` in
  `Test` scope.
- A plugin gets no format or UI module of its own: its format goes to `tuner-format` or `tuning-changer-format` (2),
  and its UI comes with #99. The outlook groups each plugin's modules into a feature.
- `tuner-mts` gets a trimmed copy of `TestTunings`, as `rig` has.
- **Scope.** Only tuners and tuning changers. The composition plugins (40–340 lines, no protocol or UI of their own)
  stay in `composition`, and the track I/O specs stay in `rig`.
- **Saved files are unaffected:** JSON identifies plugins by `typeName`, not by class name.

### 4. `sc-midi-javamidi`

Move the `javamidi` package into its own module. A use of Java Sound outside it becomes a compile error, and
CoreMIDI4J leaves the classpath of every module except `sc-midi-javamidi`, `app` and `cli`.

### 5. Prepare `sc-midi` for publishing

- Replace the `Businessync` dependency with a SAM trait next to `MidiEvent`, e.g. `MidiEventPublisher` with a
  `publish(event: MidiEvent)` method, which `JavaMidiManager` takes. `app` passes a lambda calling
  `businessync.publish`, and a client that doesn't need the events, such as `cli`, passes a no-op instead of building
  a `Businessync`. A trait rather than a function type, so that its ScalaDoc can say on which thread the events come;
  a lambda still converts to it.
- `MidiEvent` stops extending `BusinessyncEvent`, so `Businessync.publish` accepts any `AnyRef`, as Guava's
  `EventBus.post` does. Wrapping `MidiEvent` instead would make `RigManager` subscribe to the wrapper.
- `businessync` stays unpublished: its API is incomplete and will change (#90).
- Publishing comes later, as two artifacts, `sc-midi` and `sc-midi-javamidi`. So does removing the `common`
  dependency, which only `Locking` needs (see the open questions).

### 6. Grouping folders

Every module moves into a grouping folder, which holds the modules of one area and never contains a `src`; only `root`
stays at the repository root. A module's leaf folder name is its ID, so the `.withId(<dir>)` convention and the
scoverage inspector, which finds sources by `/<module_id>/src/`, keep working:

```
app/     app/  cli/  config/  experiments/
base/    businessync/  common/  common-test-utils/
format/  format/  format-core/  format-test-utils/
midi/    sc-midi/  sc-midi-javamidi/
rig/     rig/  rig-format/
theory/  composition/  composition-format/  intonation/  intonation-format/
tuner/   tuner/  tuner-format/  tuning/  tuning-changer/  tuning-changer-format/
         tuner-mpb/  tuner-mpe/  tuner-mts/  tuning-changer-pedal/
ui/      ui/
```

- `app` holds the executables, and `config`, which only the `app` module uses. `base` holds the modules that know
  nothing about music.
- `theory` holds the music theory and its formats: intervals and scales, and how a composition maps them to tunings.
  `music` would fit every module of the repository, and `composition` would make the standalone `intonation` library
  look like a part of `composition`.
- `tuning` sits in `tuner`, the area of its package.
- The move also updates the JAR paths in `bin/mtlist` and `bin/mtlist-tool`, and the docs that name module paths.

Architecture docs stay one per module, as today: every new module but `format-test-utils` gets a
`docs/architecture/<module>/README.md` and a `CLAUDE.md` importing it, and a split module's doc splits with it.

## Suggested order

1. The grouping folders (6), moving the modules without changing their code, so that each later step creates its
   modules in place.
2. `sc-midi-javamidi` (4): the cheapest, and it only touches `sc-midi`, `app` and `cli`.
3. `tuning` and `tuning-changer` (1).
4. The plugin modules (3), with their new packages.
5. The `format` split (2). It comes after 4, so that each plugin format moves to its final package once.
6. Decoupling `sc-midi` from `businessync` (5), independent of 3–5.

Each new module needs its own coverage floor.

## Left as they are

- The small modules (`ui`, `businessync`, `common`, `cli`): their boundaries are clear and #99 and #90 will grow them.
- The `FakeTuner` test doubles in `tuner` and `rig`: an sbt test-scope cycle forces the duplication, and they differ.

## Open questions

- When `sc-midi` is published, should `Locking` (99 lines) be copied into it as package-private code, or extracted into
  a small published utility library? `config` and `format-core` use it too.
