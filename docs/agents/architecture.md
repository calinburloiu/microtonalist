# Architecture (Agents)

This is the crucial, always-loaded architecture overview for coding agents. It is `@import`ed by the root
[`AGENTS.md`](../../AGENTS.md) / `CLAUDE.md` so it loads in every session. Deeper, context-specific detail lives in
the per-module and per-topic documents under [`docs/architecture/`](../architecture/) and is loaded on demand.

## How architecture docs are organized

- **This file (`docs/agents/architecture.md`)** — the always-in-context overview: the module dependency graph, the key
  domain concepts, and the end-to-end data flow. Read it to orient yourself in any task.
- **Per-module deep dives (`docs/architecture/$MODULE/README.md`)** — one document per SBT module (an architecture
  document covering responsibility, key types, dependencies, and module-specific concerns). Each module's
  `$MODULE/CLAUDE.md` `@import`s its document, so when you work inside a module that detail loads automatically. To read
  another module's architecture without editing in it, open its `docs/architecture/$MODULE/README.md` directly.
- **Per-topic documents** — some directories carry focused topic docs alongside the module README, e.g.
  [`docs/architecture/tuner/mpe-spec.md`](../architecture/tuner/mpe-spec.md) and
  [`docs/architecture/tuner/mpe-tuner-paper.md`](../architecture/tuner/mpe-tuner-paper.md) for MPE tuning.
- **Human-facing index (`docs/architecture/README.md`)** — the same material framed for human readers, with a table
  mapping each module to its directory and document.

**Before coding**, identify which architecture documents are strictly relevant to the task (this overview plus the
README(s) of the module(s) you will touch and their immediate collaborators) and read them. Architecture docs may note
that an area is *subject to change* under the GitHub `Architecture` milestone; treat those notes as forward-looking, not
as the current state of the code.

## Module Overview

The project uses a layered module structure. Dependency direction flows from `app` downward:

```
app
├── ui            (GUI, depends on tuner)
├── composition   (domain model, depends on intonation + tuner)
├── format        (JSON/file I/O, depends on composition + tuner)
├── tuner         (MIDI tuning, depends on sc-midi + businessync)
├── sc-midi       (Scala-idiomatic MIDI API, depends on businessync)
├── intonation    (interval math, no application deps)
├── businessync   (event bus + threading, no application deps)
├── common        (shared utilities)
└── config        (HOCON config, depends on common)
```

`cli` is a separate executable (utility tool) that depends only on `sc-midi`.

## Key Domain Concepts

**Intonation (`intonation` module, `org.calinburloiu.music.intonation`):**

- `Interval` (sealed trait) — musical interval, base for `RatioInterval`, `CentsInterval`, `EdoInterval`,
  `RealInterval`; all support arithmetic in logarithmic space and conversion to cents
- `Scale[I <: Interval]` — ordered sequence of intervals with direction that represent a musical scale, with helper
  methods for things like relative intervals, softness (entropy) etc.
- `IntonationStandard` — enum-like sealed trait describing how intervals are expressed (`CentsIntonationStandard`,
  `JustIntonationStandard`, `EdoIntonationStandard`)

**Composition (`composition` module, `org.calinburloiu.music.microtonalist.composition`):**

- `Composition` — top-level container that models a microtonal musical composition with respect to its microtonal scales
  and how are mapped to tunings: holds an `IntonationStandard`, a `TuningReference`, a sequence of `TuningSpec`, a
  `TuningReducer`, and fill configuration
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
  `StandardTuningReference` is relative to 12-EDO, `ConcertPitchTuningReference` is relative to a concert-pitch frequency
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

> **More detail:** the threading model behind this flow is documented in
> [`docs/architecture/businessync/`](../architecture/businessync/README.md); serialization in
> [`docs/architecture/format/`](../architecture/format/README.md); the startup wiring that builds this pipeline in
> [`docs/architecture/app/`](../architecture/app/README.md).
