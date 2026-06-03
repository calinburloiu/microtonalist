# Key domain concepts

The core domain vocabulary of Microtonalist, grouped by the module that owns each type. This is the high-level model the
rest of the architecture docs assume; each module's own README in this directory goes deeper.

**Intonation (`intonation` module, `org.calinburloiu.music.intonation`):**

- `Interval` (sealed trait) — musical interval, base for `RatioInterval`, `CentsInterval`, `EdoInterval`,
  `RealInterval`; all support arithmetic in logarithmic space and conversion to cents
- `Scale[I <: Interval]` — ordered sequence of intervals with direction that represent a musical scale, with helper
  methods for things like relative intervals, softness (entropy) etc.
- `IntonationStandard` — enum-like sealed trait describing how intervals are expressed (`CentsIntonationStandard`,
  `JustIntonationStandard`, `EdoIntonationStandard`)

**Composition (`composition` module, `org.calinburloiu.music.microtonalist.composition`):**

- `Composition` — top-level container that models a microtonal musical composition with respect to its microtonal
  scales and how are mapped to tunings: holds an `IntonationStandard`, a `TuningReference`, a sequence of
  `TuningSpec`, a `TuningReducer`, and fill configuration
- `TuningMapper` (trait, Plugin) — maps a `Scale` to a `Tuning`, deciding which keyboard pitch class each scale pitch
  occupies (unused keys stay `None`) and throwing a `TuningMapperConflictException` when two pitches collide on one key;
  `AutoTuningMapper` places pitches automatically (handling quarter tones and the soft chromatic genus),
  `ManualTuningMapper` uses a user-provided `KeyboardMapping`
- `TuningReducer` (trait, Plugin) — merges the per-`TuningSpec` `Tuning`s into ideally fewer final tunings to minimize
  the tuning switches a player must make, producing the `TuningList`; `MergeTuningReducer` (default) merges consecutive
  non-conflicting tunings and applies local back-/fore-fill, while `DirectTuningReducer` applies only the global fill
  (no reduction)
- `TuningReference` (trait, Plugin) — defines the composition's base pitch: the keyboard pitch class it maps to
  (`basePitchClass`) and that pitch's cents offset from 12-EDO (`baseOffset`), combined as a `baseTuningPitch`;
  `StandardTuningReference` is relative to 12-EDO, `ConcertPitchTuningReference` is relative to a concert-pitch
  frequency
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
