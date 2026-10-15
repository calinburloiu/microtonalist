# Modularization proposal (draft)

- **Date:** 2026-10-16
- **Based on commit:** `27d7624e` (`main`)
- **Issue:** #366
- **Status:** draft, not reviewed.

## Goals

- Keep the domain modules free of the MIDI engine, the format layer and the UI.
- Make each tuner and tuning changer a feature that is added without touching the generic modules.
- Prepare `sc-midi` for publishing as a standalone library.
- Recompile and retest less on each change.

## Findings at `27d7624e`

- `composition` imports only `Tuning` and `DefaultCentsTolerance` from `tuner`, so the domain model compiles against
  the whole tuning engine (MPE, MTS, `businessync`, CoreMIDI4J). It also imports `PitchClass` and `MidiNote` from
  `sc-midi`, which `build.sbt` doesn't declare: they arrive through `tuner`.
- `format` has 52 files (4.4k lines, 3.8k test lines) and depends on `composition`, `rig` and `tuner`. Its imports
  fall into clean groups:

  | Group | Files | Depends on |
  | ----- | ----- | ---------- |
  | Infrastructure | `RepoSelector`, `DefaultRepoSelector`, `UriScheme`, `JsonPreprocessor` and its 2 ref loaders, `DeferrableRead`, `JsonPluginFormat`, `JsonConstraints` | `common` |
  | MIDI values | `JsonCommonMidiFormat`, `MidiNoteFormat`, `PitchClassFormat`, the package object (`uint7Format`, `ccNumberFormat`) | `sc-midi` |
  | Scales | `ScaleFormat`, `JsonScaleFormat`, `HuygensFokkerScalaScaleFormat`, `ScaleRepo` and its 4 implementations, `ScaleContextConverter`, `ScaleFormat{Context,Metadata,Registry}`, `JsonIntervalFormat`, `JsonIntonationStandardPluginFormat` | `intonation`, `businessync` |
  | Compositions | `Composition{Format,Repo,Repr}`, `{Default,File,Http}CompositionRepo`, `JsonCompositionFormat`, the mapper, reducer, reference and soft-chromatic plugin formats, `KeyboardMappingFormat` | `composition` |
  | Rigs | `RigFormat`, `JsonRigFormat`, `{Default,File,Http,Library}RigRepo`, `JsonTrackIOPluginFormat` | `rig` |
  | Tuner plugins | `JsonTunerPluginFormat`, `Json{Mpe,MonophonicPitchBend,Mts}TunerPluginFormating`, `JsonTuningChangerPluginFormat` | `tuner` |

  `FormatModule` wires all of them.
- The `tuner` implementations don't reference each other; each uses only the `Tuner` or `TuningChanger` trait and
  `Tuning`:

  | Implementation | Main files | Main lines | Its format (lines) |
  | -------------- | ---------- | ---------- | ------------------ |
  | MTS | `MtsTuner`, `MtsMessageGenerator` | 282 | 91 |
  | Monophonic Pitch Bend | `MonophonicPitchBendTuner` | 357 | 57 |
  | MPE | the 7 `Mpe*` files | 2,529 (6,853 test) | 125 |
  | Pedal tuning changer | `PedalTuningChanger`, `TuningChangeTriggers` | 256 | 76 |

- `JsonTunerPluginFormat` is an `object` with a hardcoded list of specs, and `JsonRigFormat` uses it and
  `JsonTuningChangerPluginFormat` as private implicits.
- `ui` uses three `tuner` symbols: `TuningService`, `IndexTuningChange` and `TuningIndexUpdatedEvent`.
- Outside `sc-midi`'s `javamidi` package (1.5k lines), `javax.sound` appears only in ScalaDoc links, and only `app` and
  `cli` import `javamidi`.
- What ties `sc-midi` to the rest of the repo:
  - Its only use of `common` is `Locking`, in 5 files (2 in the core, 3 in `javamidi`).
  - `MidiEvent extends BusinessyncEvent`, and `JavaMidiManager` takes a `Businessync` to publish its events.
- `MidiNote.pitchClass` returns a `PitchClass`, so `PitchClass` can't leave `sc-midi`.
- `intonation.ConcertPitchFreq` and `scmidi.DefaultConcertPitchFreq` are both 440.0.

## Proposal

### 1. A `tuning` module

Move `Tuning` and `DefaultCentsTolerance` from `tuner` into a new `tuning` module, depending on `sc-midi` for
`PitchClass`. `composition` and `tuner` depend on it; `composition` stops depending on `tuner`. Related open work:
the `composition` refactor (#87) and scale lineage in tunings (#34), which would make `tuning` depend on `intonation`.

### 2. Split `format`

| Module | Holds |
| ------ | ----- |
| `format-core` | the infrastructure and the MIDI value formats |
| `format-scale` | the scale formats and repos |
| `format-composition` | the composition formats and repos, with the composition plugin formats |
| `format-rig` | the rig formats and repos, with the track I/O plugin formats |

The tuner plugin formats move to the feature modules (3). `FormatModule`'s wiring moves to `app` with the module
wiring work (#100). `format-scale` depends only on `format-core`, `intonation` and `businessync`, so `experiments` can
load scales without `composition`, `rig` or `tuner`.

### 3. Feature modules for tuners and tuning changers

`tuner` keeps the `Tuner` and `TuningChanger` traits, the tuning session and service. Each implementation becomes a
feature of up to three modules, with its own subpackage (e.g. `…tuner.mpe`):

| Module | Depends on | Never on |
| ------ | ---------- | -------- |
| `tuner-mpe` (domain) | `tuner`, `sc-midi` | format, UI |
| `tuner-mpe-format` | `tuner-mpe`, `format-core` | UI |
| `tuner-mpe-ui` | `tuner-mpe`, `ui-core` | format |
| `format-rig`, `ui` | the contract modules (`tuner`, `format-core`, `ui-core`) | any feature module |
| `app` | every module | |

- **Registration.** `app` owns the list of plugins. Each plugin family's format becomes an instance built from the
  `TypeSpec`s that `app` registers, passed to `JsonRigFormat`; `ui` gets each feature's view and view model the same
  way. The feature format modules already expose their specs (`JsonMpeTunerPluginFormating.spec`,
  `JsonMtsTunerPluginFormating.specs`).
- **UI.** `ui-core` and the `-ui` modules come with the JavaFX GUI (#99), and only for features with a UI.
- **Scope.** Only tuners and tuning changers. The composition plugins (40–340 lines, no protocol or UI of their own)
  stay in `composition`, and the track I/O specs stay in `rig`.
- **Saved files are unaffected:** JSON identifies plugins by `typeName`, not by class name.

### 4. `sc-midi-javamidi`

Move the `javamidi` package into its own module. A use of Java Sound outside it becomes a compile error, and
CoreMIDI4J leaves the classpath of every module except `sc-midi-javamidi`, `app` and `cli`.

### 5. Prepare `sc-midi` for publishing

- Replace the `Businessync` dependency with a callback (`MidiEvent => Unit`) that `app` connects to the bus.
  `businessync` stays unpublished: its API is incomplete and will change (#90). Because `Businessync.publish` accepts
  only a `BusinessyncEvent`, either `app` wraps `MidiEvent`, or `publish` accepts a wider type.
- Copy `Locking` (99 lines) into `sc-midi` as package-private code; `config` and `format` keep `common`'s.
- Publish two artifacts: `sc-midi` and `sc-midi-javamidi`.

### Folder layout

A grouping folder holds the modules of one area or feature and never contains a `src`. A module's leaf folder name is
its ID, so the `.withId(<dir>)` convention and the scoverage inspector, which finds sources by `/<module_id>/src/`,
keep working. Feature folders aren't modules, so their names are short:

```
format/
  format-core/  format-scale/  format-composition/  format-rig/
sc-midi/
  sc-midi/  sc-midi-javamidi/
tuner/
  tuning/
  tuner/
  mpe/                      CLAUDE.md and architecture doc for the whole feature
    tuner-mpe/  tuner-mpe-format/  tuner-mpe-ui/
  mts/
    tuner-mts/  tuner-mts-format/
  monophonic-pitch-bend/
    tuner-monophonic-pitch-bend/  tuner-monophonic-pitch-bend-format/
  pedal-changer/
    tuning-changer-pedal/  tuning-changer-pedal-format/
ui/
  ui-core/  ui/
```

The other modules stay at the root. A feature folder's `CLAUDE.md` replaces the per-module ones of its modules, which
changes a convention of the `architecture-docs` skill.

## Suggested order

1. `sc-midi-javamidi` (4): the cheapest, and it only touches `sc-midi`, `app` and `cli`.
2. `tuning` (1).
3. The `format` split (2), moving to app-owned registration of the plugin formats.
4. The MPE feature (3) as the pilot: it's the largest and nothing else in `tuner` references it.
5. The MTS, Monophonic Pitch Bend and pedal features.
6. `sc-midi` publishing (5), independent of 2–5.

Each new module needs its own coverage floor and architecture doc.

## Left as they are

- The small modules (`ui`, `businessync`, `common`, `cli`): their boundaries are clear and #99 and #90 will grow them.
- The `FakeTuner` test doubles in `tuner` and `rig`: an sbt test-scope cycle forces the duplication, and they differ.

## Open questions

- Should the `Businessync` adapter wrap `MidiEvent`, or should `publish` accept a wider type?
- Should `Locking` be copied into `sc-midi`, or extracted into a small published utility library?
- Does the per-feature `CLAUDE.md` load for every module under it in all the agent tools used here?
