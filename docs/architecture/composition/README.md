# `composition` module architecture

The `composition` module (directory `composition/`, package `org.calinburloiu.music.microtonalist.composition`) is the
domain model that connects the two central abstractions of Microtonalist: high-level musical **scales** and the
low-level **tunings** that a keyboard/synthesizer is actually configured with. A user composes with a sequence of
scales; this module turns that sequence into the concrete `Tuning`s the `tuner` module sends to instruments.

> **Note:** this module is the subject of the planned refactor tracked by
> [issue #87 "Refactor composition module"](https://github.com/calinburloiu/microtonalist/issues/87) (Architecture
> milestone). Some of the structure described below is expected to change as part of that work.

## Responsibility

- Hold the in-memory representation of a microtonal composition: a `Composition` aggregates an `IntonationStandard`,
  a `TuningReference`, an ordered sequence of `TuningSpec`s (each a scale + how to map it), a `TuningReducer`, and a
  `FillSpec` (fill configuration).
- Map each scale to a 12-pitch-class `Tuning` (deciding which keyboard key every scale pitch occupies), via the
  `TuningMapper` plugin family.
- Reduce/merge the per-spec tunings into as few final tunings as possible — so a player makes the fewest tuning
  switches — via the `TuningReducer` plugin family, producing a `TuningList`.
- Resolve a `Composition` into a final `TuningList` through `TuningList.fromComposition`.

The module is pure domain logic: it knows nothing about file formats (that is `format`), MIDI transport, or the GUI. It
manipulates intervals and produces `Tuning` values; deciding when/how those tunings reach an instrument is the `tuner`
module's job.

## Key types

### Aggregate / value types

- **`Composition`** — `Composition.scala:40`. Top-level container modelling a composition: optional source `url`,
  `intonationStandard`, `tuningReference`, `tuningSpecs: Seq[TuningSpec]`, `tuningReducer`, `fill: FillSpec`, optional
  `metadata`, and an optional `tracksUrlOverride`. `tracksUrl` derives the tracks-file URI (appends `.tracks`) when not
  overridden. A plain `case class`.
- **`TuningSpec`** — `Composition.scala:68`. Pairs a `Scale[Interval]` with a `TuningMapper` and a `transposition`
  interval. `tuningFor(ref)` transposes+maps the scale into one `Tuning`.
- **`FillSpec`** / **`LocalFillSpec`** — `Composition.scala:115` / `Composition.scala:86`. Fill configuration. `global`
  is an optional `TuningSpec` used as the final fallback for unused keys across all tunings; `local` toggles back-fill,
  fore-fill, and memory-fill. The ScalaDoc on `FillSpec` documents the local vs. global fill taxonomy in detail.
- **`CompositionMetadata`** — `Composition.scala:129`. Name / composer / author.
- **`TuningPitch`** — `TuningPitch.scala:32`. A single pitch class plus its cents `offset` from 12-EDO. Helpers:
  `cents`, `interval`, `isOverflowing` (|offset| ≥ 100), `isQuarterTone`, `almostEquals`. Implicit conversions to/from
  `PitchClass` and `Int` live in its companion.
- **`KeyboardMapping`** — `KeyboardMapping.scala:26`. A 12-slot mapping from pitch class → optional scale pitch index
  (some keys unmapped). Backs `ManualTuningMapper` and the override mechanism of `AutoTuningMapper`. Offers
  `apply`/`updated`/`removed`/`toMap`/`iterator` plus a named-argument `apply` factory (`c =`, `cSharpOrDFlat =`, …)
  and `KeyboardMapping.empty`.
- **`TuningList`** — `TuningList.scala:23`. The resolved output: a `Seq[Tuning]` exposed as an `Iterable[Tuning]` with
  indexed `apply`. Built by `TuningList.fromComposition` (`TuningList.scala:38`).

### Plugin families

`TuningMapper`, `TuningReducer`, `TuningReference` (and the `SoftChromaticGenusMapping` helper) all extend the
`Plugin` trait from `common` (`common/.../Plugin.scala`). A `Plugin` carries a `familyName` (identifying the extension
point) and a `typeName` (identifying the concrete variant). The `format` module uses these names to select the right
Play JSON (de)serializer, so each implementation here corresponds to a JSON `type` discriminator. The `*.Default`
values in the companions define the variant chosen when a composition does not specify one.

**`TuningMapper`** (family `"tuningMapper"`) — `TuningMapper.scala:31`. Maps a `Scale` to a `Tuning`, leaving unused
keys as `None`. A *conflict* (two scale pitches landing on the same key) throws `TuningMapperConflictException`
(`TuningMapper.scala:75`); an out-of-range offset throws `TuningMapperOverflowException` (`TuningMapper.scala:83`).

- **`AutoTuningMapper`** (`typeName = "auto"`) — `AutoTuningMapper.scala:96`. Places pitches automatically. Key
  behaviours:
  - `shouldMapQuarterTonesLow` chooses whether a quarter tone snaps to the lower key (+50¢) or the higher key (−50¢);
    `quarterToneTolerance` absorbs floating-point slack around ±50¢.
  - Detects and resolves key collisions by re-mapping the quarter tone in the opposite direction
    (`mapScaleToPitchesInfo`, `AutoTuningMapper.scala:181`).
  - `softChromaticGenusMapping` (`SoftChromaticGenusMapping`, `AutoTuningMapper.scala:48`) — `Off` / `Strict` /
    `PseudoChromatic`, itself a `Plugin` (family `"softChromaticGenusMapping"`). Lets a soft-chromatic trichord
    (e.g. an Easter Hijaz tetrachord) keep its characteristic augmented second instead of being flattened by the
    quarter-tone rule.
  - `overrideKeyboardMapping` lets the user pin specific scale pitches to chosen keys; those are delegated to an
    internal `ManualTuningMapper` and merged with the auto result.
  - `keyboardMappingOf` reports the mapping the auto algorithm derived for a scale.
  - `TuningMapper.Default` and `AutoTuningMapper.Default` are `AutoTuningMapper`s with
    `shouldMapQuarterTonesLow = false`.
- **`ManualTuningMapper`** (`typeName = "manual"`) — `ManualTuningMapper.scala:29`. Maps strictly from a
  user-provided `KeyboardMapping`, computing each key's offset and throwing `TuningMapperOverflowException` if an
  offset leaves the open interval (−100, 100).

**`TuningReducer`** (family `"tuningReducer"`) — `TuningReducer.scala:26`. Merges the per-`TuningSpec` tunings into
ideally fewer final tunings and returns a `TuningList`. `TuningReducer.Default` is a `MergeTuningReducer`.

- **`MergeTuningReducer`** (`typeName = "merge"`, the default) — `MergeTuningReducer.scala:43`. Merges *consecutive*
  non-conflicting tunings (two tunings conflict when any corresponding key has differing offsets beyond
  `equalityTolerance`), then applies local **back-fill** (offsets from preceding merged tunings) and **fore-fill**
  (offsets from succeeding ones) to minimise notes retuned on a tuning switch, and finally fills remaining gaps from
  the global fill tuning.
- **`DirectTuningReducer`** (`typeName = "direct"`, a singleton `object`) — `DirectTuningReducer.scala:26`. Performs
  no reduction; only applies the global fill to each tuning. Use when no merging is wanted.

**`TuningReference`** (family `"tuningReference"`) — `TuningReference.scala:27`. Defines the composition's base pitch:
which keyboard pitch class it occupies (`basePitchClass`), its cents offset from 12-EDO (`baseOffset`), and the two
combined as a `baseTuningPitch: TuningPitch`.

- **`StandardTuningReference`** (`typeName = "standard"`) — `TuningReference.scala:57`. Base pitch given directly as a
  pitch class + cents offset relative to 12-EDO.
- **`ConcertPitchTuningReference`** (`typeName = "concertPitch"`) — `TuningReference.scala:77`. Base pitch derived from
  a concert-pitch frequency (default A4 = 440 Hz), an interval from concert pitch to the base, and the base MIDI note;
  `baseOffset` is computed from these.

### Module-level helpers

`package.scala` defines `DefaultQuarterToneTolerance = 13.0` and `roundWithTolerance` (round-half toward a chosen
direction within a tolerance), used by the auto mapper's quarter-tone logic.

## How a `Composition` resolves into a `TuningList`

`TuningList.fromComposition(composition)` (`TuningList.scala:38`) drives the pipeline:

```scala
def fromComposition(composition: Composition): TuningList = {
  val globalFillTuning = composition.fill.global
    .map(_.tuningFor(composition.tuningReference))
    .getOrElse(Tuning.Standard)
  val tunings = composition.tuningSpecs.map(_.tuningFor(composition.tuningReference))
  composition.tuningReducer.reduceTunings(tunings, globalFillTuning)
}
```

1. For each `TuningSpec`, `tuningFor(ref)` transposes the scale and runs its `TuningMapper` against the
   `TuningReference`, yielding one `Tuning` per spec.
2. The optional global-fill `TuningSpec` is mapped the same way (or `Tuning.Standard` if absent).
3. The `TuningReducer` merges those tunings, applies local fill (Merge only) and the global fill, and emits the final
   `TuningList`.

This is the only public way the rest of the app turns composition data into tunings; `MicrotonalistApp` calls it right
after loading the `Composition` (`app/.../MicrotonalistApp.scala:85`), and the resulting `TuningList` feeds the
`TuningSession` in the `tuner` module.

## Dependencies

Declared in `build.sbt` (`lazy val composition`):

- **Depends on `intonation`** — `Interval`, `Scale`, `IntonationStandard`, `CentsInterval`, `RealInterval`,
  `ConcertPitchFreq`, etc. (the interval math this module builds on).
- **Depends on `tuner`** — `Tuning` (the output value type), `Tuning.Standard`, and the `DefaultCentsTolerance`
  constant. Note the arrow points *into* `tuner`: this module produces `Tuning`s but does not perform MIDI tuning.
- **Depends on `common`** (transitively, for `Plugin`) and uses `scMidi` types such as `PitchClass` and `MidiNote`
  (pulled in via `tuner`'s dependency on `sc-midi`).

Depended on by:

- **`format`** — serializes/deserializes every type here (`JsonCompositionFormat`, the `Json*PluginFormat`s for the
  mapper/reducer/reference/soft-chromatic families, `KeyboardMappingFormat`, etc.).
- **`app`** — loads a `Composition` and calls `TuningList.fromComposition`.

Module coverage floor is set high (statement and branch both 80% in `build.sbt`), reflecting its status as a
core, logic-heavy domain module.
