# `composition` module architecture

The `composition` module (directory `composition/`, package `org.calinburloiu.music.microtonalist.composition`) is the
domain model that connects the two central abstractions of Microtonalist: high-level musical **scales** and the
low-level **tunings** a keyboard/synthesizer is actually configured with. A user composes with a sequence of scales;
this module turns that sequence into the concrete `Tuning`s the `tuner` module sends to instruments.

> **Note:** this module is the subject of the planned refactor tracked by
> [issue #87 "Refactor composition module"](https://github.com/calinburloiu/microtonalist/issues/87) (Architecture
> milestone). Some of the structure below is expected to change as part of that work.

## Responsibility

- Hold the in-memory representation of a microtonal composition: a `Composition` aggregates an `IntonationStandard`, a
  `TuningReference`, an ordered sequence of `TuningSpec`s (each a scale + how to map it), a `TuningReducer`, and a
  `FillSpec`.
- Map each scale to a 12-pitch-class `Tuning` via the `TuningMapper` plugin family.
- Reduce/merge the per-spec tunings into as few final tunings as possible — so a player makes the fewest tuning switches
  — via the `TuningReducer` plugin family, producing a `TuningList`.
- Resolve a `Composition` into a final `TuningList` through `TuningList.fromComposition`.

The module is pure domain logic: it knows nothing about file formats (that is `format`), MIDI transport, or the GUI. It
manipulates intervals and produces `Tuning` values; deciding when/how those reach an instrument is the `tuner` module's
job.

## Key types

### Aggregate / value types

- **`Composition`** — the top-level container: an `intonationStandard`, a `tuningReference`, `tuningSpecs`, a
  `tuningReducer`, a `fill: FillSpec`, optional `metadata`, and the source/tracks URLs. A plain `case class`.
- **`TuningSpec`** — pairs a `Scale[Interval]` with a `TuningMapper` and a `transposition` interval; `tuningFor(ref)`
  transposes and maps the scale into one `Tuning`.
- **`FillSpec`** / **`LocalFillSpec`** — fill configuration. `global` is an optional `TuningSpec` used as the final
  fallback for unused keys across all tunings; `local` toggles back-fill, fore-fill, and memory-fill. The `FillSpec`
  ScalaDoc documents the local-vs-global fill taxonomy in detail.
- **`TuningPitch`** — a single pitch class plus its cents `offset` from 12-EDO, with helpers such as `isQuarterTone` and
  `isOverflowing`. Implicit conversions to/from `PitchClass` and `Int` live in its companion.
- **`KeyboardMapping`** — a 12-slot mapping from pitch class → optional scale-pitch index (some keys unmapped). Backs
  `ManualTuningMapper` and the override mechanism of `AutoTuningMapper`.
- **`TuningList`** — the resolved output: a `Seq[Tuning]` exposed as an indexed `Iterable[Tuning]`, built by
  `TuningList.fromComposition`.

### Plugin families

`TuningMapper`, `TuningReducer`, `TuningReference` (and the `SoftChromaticGenusMapping` helper) all extend the `Plugin`
trait from `common`: each carries a `familyName` (the extension point) and a `typeName` (the concrete variant), which
`format` uses to select the right Play-JSON (de)serializer. The `*.Default` value in each companion is the variant
chosen when a composition does not specify one.

**`TuningMapper`** (family `"tuningMapper"`) maps a `Scale` to a `Tuning`, leaving unused keys `None`. A *conflict* (two
scale pitches on the same key) throws `TuningMapperConflictException`; an out-of-range offset throws
`TuningMapperOverflowException`.

- **`AutoTuningMapper`** (`"auto"`, the default) places pitches automatically. `shouldMapQuarterTonesLow` chooses
  whether a quarter tone snaps to the lower (+50¢) or higher (−50¢) key, and it resolves key collisions by re-mapping
  the quarter tone in the opposite direction. Its `softChromaticGenusMapping` (`Off`/`Strict`/`PseudoChromatic`, itself
  a `Plugin`) lets a soft-chromatic trichord keep its characteristic augmented second instead of being flattened by the
  quarter-tone rule. An optional `overrideKeyboardMapping` pins specific pitches to chosen keys (delegated to an
  internal `ManualTuningMapper` and merged with the auto result).
- **`ManualTuningMapper`** (`"manual"`) maps strictly from a user-provided `KeyboardMapping`.

**`TuningReducer`** (family `"tuningReducer"`) merges the per-`TuningSpec` tunings into ideally fewer final tunings and
returns a `TuningList`.

- **`MergeTuningReducer`** (`"merge"`, the default) merges *consecutive* non-conflicting tunings, then applies local
  **back-fill** (offsets from preceding merged tunings) and **fore-fill** (from succeeding ones) to minimise notes
  retuned on a switch, and finally fills remaining gaps from the global fill tuning.
- **`DirectTuningReducer`** (`"direct"`) performs no reduction; it only applies the global fill to each tuning.

**`TuningReference`** (family `"tuningReference"`) defines the composition's base pitch: which keyboard pitch class it
occupies (`basePitchClass`), its cents offset from 12-EDO (`baseOffset`), and the two combined as a `baseTuningPitch`.
`StandardTuningReference` (`"standard"`) gives the base pitch directly relative to 12-EDO; `ConcertPitchTuningReference`
(`"concertPitch"`) derives it from a concert-pitch frequency, an interval, and the base MIDI note.

## How a `Composition` resolves into a `TuningList`

`TuningList.fromComposition(composition)` drives the pipeline:

1. For each `TuningSpec`, `tuningFor(ref)` transposes the scale and runs its `TuningMapper` against the
   `TuningReference`, yielding one `Tuning` per spec.
2. The optional global-fill `TuningSpec` is mapped the same way (or `Tuning.Standard` if absent).
3. The `TuningReducer` merges those tunings, applies local fill (Merge only) and the global fill, and emits the final
   `TuningList`.

This is the only public way the rest of the app turns composition data into tunings; `MicrotonalistApp` calls it right
after loading the `Composition`, and the resulting `TuningList` feeds the `TuningSession` in the `tuner` module.

## Dependencies

- **Depends on `intonation`** for the interval math it builds on (`Interval`, `Scale`, `IntonationStandard`, …).
- **Depends on `tuner`** for the output value type `Tuning` (and `Tuning.Standard`). The arrow points *into* `tuner`:
  this module produces `Tuning`s but does not perform MIDI tuning. It also uses `sc-midi` types such as `PitchClass` and
  `MidiNote` (reached via `tuner`) and `common`'s `Plugin` trait.

Depended on by **`format`** (which serializes every type here) and **`app`** (which loads a `Composition` and calls
`TuningList.fromComposition`). Its coverage floor is set high (statement and branch both 80%), reflecting its status as
a core, logic-heavy domain module.
