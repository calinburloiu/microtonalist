# `tuner` module architecture

The `tuner` module (directory `tuner/`, package `org.calinburloiu.music.microtonalist.tuner`) is the low-level
MIDI tuning engine. It turns a 12-pitch-class [`Tuning`](#tuning) into the MIDI messages an output instrument needs to
play microtonally, and it manages the per-instrument processing pipelines (tracks) that route MIDI from input to output
while applying tuning and reacting to tuning-change triggers.

## Responsibility

- Convert a `Tuning` (cent offsets per pitch class) into protocol-specific MIDI messages: MIDI Tuning Standard (MTS,
  all four octave variants), MIDI Polyphonic Expression (MPE), and monophonic Pitch Bend.
- Process the live MIDI stream of an instrument: detect tuning-change triggers (e.g. piano pedals), apply the current
  tuning to passing notes, and forward the result to the output device or another track.
- Hold and mutate the application's runtime tuning/track state on the business thread, and expose thread-safe services
  to the UI and application layers.

It does **not** decide *which* tunings exist or how scales map to them — that is the `composition` module, which builds
a `TuningList` and hands the resulting `Seq[Tuning]` to this module. It also does **not** read/write `.mtlist.tracks`
files itself; the `TrackRepo` trait lives here but its formats and concrete repos live in `format` (see
[Persistence](#persistence-trackrepo)).

## Key types

### Plugin pattern

`Tuner`, `TuningChanger`, `TrackInputSpec`, and `TrackOutputSpec` all extend `Plugin` (from `common`). Each plugin
declares a `familyName` (the family, e.g. `"tuner"`) and a `typeName` (the concrete variant, e.g. `"mpe"`). The
`format` module uses these names for JSON (de)serialization, so adding a tuner/changer variant means adding a
`typeName` here and a serializer there. Family names are defined as constants on the companion objects (e.g.
`Tuner.FamilyName = "tuner"`, `TuningChanger.FamilyName = "tuningChanger"`).

### Tuning

- `Tuning` — `tuner/.../Tuning.scala:36`. A `case class` wrapping `Seq[Option[Double]]` of exactly 12 cent offsets, one
  per pitch class (C..B); `None` marks a key with no offset (treated as `0.0` by `apply`). Named-note accessors
  (`c`, `cSharp`/`dFlat`, …) and rich combinators: `fill` (complete empty keys from another), `overwrite`, `merge`
  (returns `None` on a conflict beyond `DefaultCentsTolerance`), `almostEquals`, plus `isComplete`/`completedCount`.
  `Tuning.Standard` (`:282`) is 12-EDO (all zeros); `Tuning.Empty` is all-`None`; `Tuning.Size = 12`.

### Tuners (the protocol implementations)

- `Tuner` (trait, Plugin) — `tuner/.../Tuner.scala:48`, `@NotThreadSafe`. The protocol abstraction with three methods:
  - `reset(): Seq[MidiMessage]` — initialize/configure the output device (e.g. set Pitch Bend Sensitivity); must be
    sent before first use.
  - `tune(tuning): Seq[MidiMessage]` — store the tuning and emit any messages it requires now.
  - `process(message): Seq[MidiMessage]` — transform every MIDI message flowing through the track.
  - `altTuningOutput: Option[MidiDeviceId]` — optional separate device for tuning SysEx (meaningful only for tuners
    where tuning data stands alone, i.e. MTS — not Pitch Bend based ones).
- `MtsTuner` (abstract) + 4 case-class variants — `tuner/.../MtsTuner.scala`. `MtsOctave{1,2}Byte{Non,}RealTimeTuner`
  cover the octave-based MTS protocol in 1-/2-byte and real-time/non-real-time forms. `tune` emits a single MTS SysEx;
  `process` only forwards messages when `thru` is set (the instrument is retuned in advance, notes are untouched).
  SysEx bytes are built by `MtsMessageGenerator` / `MtsOctaveMessageGenerator` (`tuner/.../MtsMessageGenerator.scala`).
- `MonophonicPitchBendTuner` — `tuner/.../MonophonicPitchBendTuner.scala:36`. Tunes by per-channel Pitch Bend; because
  Pitch Bend is channel-wide it enforces monophony, folding all input onto one `outputChannel`, tracking the held note
  via `ScMidiChannelStateTracker`, and combining the performer's expressive bend with the tuning bend. Handles pedal
  interruption to preserve monophony and reacts to Pitch Bend Sensitivity RPN changes.
- `MpeTuner` — `tuner/.../MpeTuner.scala:62`. The polyphonic tuner; see
  [Supported tuning protocols](#supported-tuning-protocols) and the linked design paper. Distributes notes across MPE
  Member Channels so each can carry an independent pitch-class Pitch Bend offset, supports `NonMpe`/`Mpe` input modes
  (`MpeInputMode`), and reconfigures zones on receiving an MPE Configuration Message (MCM). Supporting types:
  `MpeZone`/`MpeZones`/`MpeZoneStructure`/`MpeZoneType` (`tuner/.../MpeZone.scala`) model the Lower/Upper zones and
  their channel layout with overlap resolution; `MpeChannelAllocator` (`tuner/.../MpeChannelAllocator.scala`)
  partitions Member Channels into a Pitch Class Group and an Expression Group and decides note→channel allocation
  (and note drops on exhaustion / large expressive bend).

### Tuning change detection

- `TuningChanger` (abstract, Plugin) — `tuner/.../TuningChanger.scala:30`, `@NotThreadSafe`. `decide(message)` returns a
  `TuningChange`; `triggersThru` controls whether the trigger MIDI message is forwarded; `reset()` clears state.
- `PedalTuningChanger` — `tuner/.../PedalTuningChanger.scala:49`. Triggers a change on a configured pedal-like CC
  crossing a `threshold` (released→pressed edge). Triggers are described by
  `TuningChangeTriggers[CcNumber]` (`tuner/.../TuningChangeTriggers.scala`), which map a trigger to previous/next/index.
- `TuningChange` (sealed) — `tuner/.../TuningChange.scala`. `EffectiveTuningChange` (`PreviousTuningChange`,
  `NextTuningChange`, `IndexTuningChange(index)`) actually changes the tuning; `IneffectiveTuningChange`
  (`NoTuningChange`, `MayTriggerTuningChange`) does not. `mayTrigger` distinguishes "part of a trigger pattern but no
  change yet" (e.g. the continuous CC stream from a held pedal) from a real change.

### The processor pipeline

Each `Tuner`/`TuningChanger` is wrapped in a `MidiProcessor` (from `sc-midi`) so it can be chained:

- `TuningChangeProcessor` — `tuner/.../TuningChangeProcessor.scala:36`, `@NotThreadSafe`. Holds the track's
  `TuningChanger`s and, for each message, asks them in order (first effective decision wins — an OR). On an
  `EffectiveTuningChange` it calls `TuningService.changeTuning`. It forwards the message unless it `mayTrigger` and the
  effective changer is not `triggersThru` (i.e. trigger messages are filtered out by default).
- `TunerProcessor` — `tuner/.../TunerProcessor.scala:46`, `@NotThreadSafe`. Wraps a `Tuner`: `tune` forwards the
  tuner's messages to the receiver; `process` delegates to `tuner.process`; on connect it sends `tuner.reset()`; on
  disconnect/close it restores `Tuning.Standard` (12-EDO). Send failures become `TunerException`.

### Track and lifecycle

- `Track` — `tuner/.../Track.scala:31`, `@ThreadSafe`. One instrument pipeline. From a `TrackSpec` it opens the input
  and output MIDI devices (via `MidiManager`) and builds a `MidiSerialProcessor` chain:
  **input device → `TuningChangeProcessor` → `TunerProcessor` → output (`MidiSplitter`) → output device**. `tune`
  delegates to the `TunerProcessor`; `close` reverts the instrument to 12-EDO and closes the devices. `multiTransmitter`
  exposes the output for track-to-track wiring. (`Track#run` is a stub — see [Future](#subject-to-change).)
- `TrackSpec` / `TrackSpecs` — `tuner/.../TrackSpecs.scala`. `TrackSpec` (`:36`) is the declarative description of a
  track: `id`, `name` (with `#` → 1-based index substitution), optional `input`/`output` specs, `tuningChangers`,
  optional `tuner`, `muted`. `TrackSpecs` (`:60`) is an immutable ordered collection keyed by id with `O(1)` lookup and
  add/update/move/remove operations.
- `TrackIO` — `tuner/.../TrackIO.scala`. Input/output spec plugins. `DeviceTrackInputSpec`/`DeviceTrackOutputSpec` bind
  to a MIDI device (and optional channel: filter on input, remap on output); `FromTrackInputSpec`/`ToTrackOutputSpec`
  wire one track's output into another track's input (inter-track routing).
- `TrackManager` — `tuner/.../TrackManager.scala:33`, `@NotThreadSafe`. Lifecycle manager: builds/replaces the live
  `Track`s from `TrackSpecs` (skipping muted ones), wires inter-track connections, and fans the current tuning out to
  every track. It subscribes (`@Subscribe onTuningChanged(TuningEvent)`) so a tuning change re-tunes all tracks. Owns a
  custom cached thread pool that names track threads `Track-<id>` (see [Future](#subject-to-change)).

### Sessions, services, and events

State lives in `*Session` objects (business-thread only) and is exposed through thread-safe `*Service` facades that
marshal calls onto the business thread via `Businessync`.

- `TuningSession` — `tuner/.../TuningSession.scala:32`, `@NotThreadSafe`. Holds the `Seq[Tuning]` and the current
  `tuningIndex`. `tunings_=` publishes `TuningsUpdatedEvent`; `tuningIndex_=`/`next`/`previous`/`nextBy` (wrap-around)
  publish `TuningIndexUpdatedEvent`. `currentTuning` falls back to `Tuning.Standard` when the index is out of range.
- `TuningService` — `tuner/.../TuningService.scala:33`, `@ThreadSafe`. `changeTuning(EffectiveTuningChange)` runs the
  corresponding session mutation on the business thread. **Note:** its `tunings` accessor is `@deprecated` as
  not thread-safe (TODO #99, to be removed once the UI moves to JavaFX) — do not rely on it.
- `TrackSession` — `tuner/.../TrackSession.scala:37`, `@NotThreadSafe`, an `OpenableSession`. Loads tracks from a URI
  via `TrackRepo`, holds the current `TrackSpecs`, and offers add/update/move/remove editing; mutations push the new
  specs to `TrackManager` and publish `TrackEvent`s.
- `TrackService` — `tuner/.../TrackService.scala:34`, `@ThreadSafe`. `open(uri)` (async) and `replaceAllTracks` on the
  business thread.
- Events — `TuningEvent` (`TuningIndexUpdatedEvent`, `TuningsUpdatedEvent`) in `tuner/.../TuningEvent.scala` and
  `TrackEvent` (`TracksOpened/Closed/Replaced`, `TrackAdded/Updated/Moved/Removed`) in `tuner/.../TrackEvent.scala`.
  All extend `BusinessyncEvent` and are published through `Businessync`.
- `TunerModule` — `tuner/.../TunerModule.scala:22`. Composition root: lazily wires `TuningSession`/`TuningService`,
  `TrackSession`/`TrackService`, an internal `MidiManager`, and the `TrackManager` (registered with `Businessync`).
  Inject this from the `app` layer rather than constructing the pieces individually.

### Persistence (`TrackRepo`)

- `TrackRepo` (trait) — `tuner/.../TrackRepo.scala:29`. Repository-pattern abstraction for reading/writing `TrackSpecs`
  identified by `URI`, with sync and async `read`/`write` methods plus a small exception hierarchy
  (`TrackRepoException` and friends). The trait lives here because `tuner` owns the domain types, but its `TrackFormat`
  and the concrete `File`/`Http`/`Default` repos live in the `format` module (see the `format` architecture doc).

## Supported tuning protocols

| Protocol | Tuner | How it tunes | Polyphony |
| -------- | ----- | ------------ | --------- |
| MTS Octave (1/2-byte, real/non-real-time) | `MtsOctave*Tuner` | Sends an MTS SysEx that retunes the instrument's pitch table in advance; notes pass through unchanged. | Polyphonic (instrument-side) |
| Monophonic Pitch Bend | `MonophonicPitchBendTuner` | Per-channel Pitch Bend, all input folded to one output channel. | Monophonic (enforced) |
| MPE | `MpeTuner` | Per-note Pitch Bend on dynamically allocated MPE Member Channels. | Polyphonic |

The MPE design has dedicated references (do not duplicate them here):

- [`mpe-spec.md`](mpe-spec.md) — the MIDI Polyphonic Expression specification (RP-053 v1.0) notes.
- [`mpe-tuner-paper.md`](mpe-tuner-paper.md) — the MPE Tuner design paper: dual-group Member Channel partitioning,
  non-MPE→MPE conversion, and the deliberate departures from the MPE spec needed for stable microtonal intonation.

## Track pipeline

A `Track` is a `MidiSerialProcessor` chain built from its `TrackSpec`:

```
input device ──▶ TuningChangeProcessor ──▶ TunerProcessor ──▶ MidiSplitter ──▶ output device
  (MidiManager)   (TuningChanger plugins)   (Tuner plugin)     (multiTransmitter)   (MidiManager)
```

- Either processor stage is optional: if a `TrackSpec` has no `tuningChangers`, that stage is omitted; same for the
  `tuner`.
- Input/output can be a MIDI device or another track (`FromTrackInputSpec` / `ToTrackOutputSpec`); `TrackManager` wires
  these inter-track connections after constructing the tracks, using each track's `multiTransmitter`.
- Optional channel config on a device spec means *filter incoming messages by channel* (input) or *remap outgoing
  messages to a channel* (output).

## Tuning-change flow

A pedal press (or any trigger) flows through the chain as follows:

```
MIDI trigger message (e.g. CC pedal)
  → TuningChangeProcessor (asks each TuningChanger.decide; first EffectiveTuningChange wins)
  → TuningService.changeTuning(effectiveChange)   [marshalled onto the business thread via Businessync]
  → TuningSession.{next,previous,index}           (updates tuningIndex)
  → TuningIndexUpdatedEvent (published via Businessync)
  → TrackManager.onTuningChanged (@Subscribe)
  → Track.tune(currentTuning) for every track
  → TunerProcessor.tune → Tuner.tune → protocol MIDI messages
  → output device
```

The reverse, application-driven path (loading a composition) sets the available tunings:

```
TuningList.tunings (from composition module)
  → TunerModule.tuningSession.tunings = …   (publishes TuningsUpdatedEvent)
  → TrackManager re-tunes all tracks to currentTuning
```

## Threading model

The module follows the Businessync two-thread model (see the project
[Threading Model](https://github.com/calinburloiu/microtonalist/wiki/Threading-Model) wiki page):

- All mutable domain state (`TuningSession`, `TrackSession`, `TrackManager`, every `Tuner`/`TuningChanger` and the
  processors that wrap them) is `@NotThreadSafe` and must be touched only on the **business thread**.
- `TuningService` and `TrackService` are `@ThreadSafe` facades: callers from the UI or application layer go through
  them, and they marshal the work onto the business thread via `businessync.run` / `businessync.callAsync`.
- `Track` itself is `@ThreadSafe` (its internal processors run under external synchronization on the track/business
  threads).
- State changes are broadcast as `BusinessyncEvent`s; the `@Subscribe`-annotated `TrackManager.onTuningChanged` is the
  link between a tuning change and the instruments being retuned.

## Dependencies

Declared in `build.sbt` (`lazy val tuner`): the module **depends on**

- `sc-midi` — the Scala-idiomatic MIDI API: `MidiManager`, `MidiProcessor`/`MidiSerialProcessor`, `MidiSplitter`,
  `MultiTransmitter`, message types (`ScMidiMessage` and conversions), `PitchBendSensitivity`, `MidiNote`,
  `PitchClass`, `ScMidiChannelStateTracker`.
- `businessync` — `Businessync` event bus + threading, and `BusinessyncEvent`.
- `common` — the `Plugin` trait and `OpenableSession`.

It also uses Guava (`IntMath`, `DoubleMath`, `Preconditions`) and scala-logging, inherited from the common build
settings.

**Depended on by** (`dependsOn(tuner)`): `app`, `ui`, `composition`, and `format`. So `tuner` sits below the
domain/format/UI layers but above `sc-midi`/`businessync`/`common`. In particular `composition` produces the
`Seq[Tuning]` consumed here, and `format` provides the `TrackFormat`/`*TrackRepo` implementations of this module's
`TrackRepo` trait. See the top-level [architecture overview](../README.md) for the full module graph.

## Subject to change

These are signalled directly in the code:

- `Track#run` is an unimplemented stub (TODO #121); `TrackManager` already provisions a per-track thread pool, but
  track threads are not yet driven.
- `TuningService.tunings` is `@deprecated` (TODO #99) and slated for removal once the UI migrates to JavaFX.
- `MpeTuner` warns/forbids are still TODO for the non-MPE-input-with-both-zones case (TODO #154), and per-note MPE
  expression is not yet updated continuously (TODO #154).
- `TrackManager.onTuningChanged` still relies on a Guava `@Subscribe` annotation pending fuller Businessync
  integration (TODO #90).
