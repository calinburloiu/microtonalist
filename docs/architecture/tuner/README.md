# `tuner` module architecture

The `tuner` module (directory `tuner/`, package `org.calinburloiu.music.microtonalist.tuner`) is the low-level MIDI
tuning engine. It turns a 12-pitch-class `Tuning` into the MIDI messages an output instrument needs to play
microtonally, and it manages the per-instrument processing pipelines (*tracks*) that route MIDI from input to output
while applying tuning and reacting to tuning-change triggers.

## Responsibility

- Convert a `Tuning` (cent offsets per pitch class) into protocol-specific MIDI messages: MIDI Tuning Standard (MTS, all
  four octave variants), MIDI Polyphonic Expression (MPE), and monophonic Pitch Bend.
- Process the live MIDI stream of an instrument: detect tuning-change triggers (e.g. piano pedals), apply the current
  tuning to passing notes, and forward the result to the output device or another track.
- Hold the application's runtime tuning/track state on the business thread and expose thread-safe services to the UI and
  application layers.

It does **not** decide *which* tunings exist or how scales map to them — that is the `composition` module, which builds
a `TuningList` and hands the resulting `Seq[Tuning]` to this module. It also does **not** read/write tracks files: the
`TrackRepo` trait lives here but its formats and concrete repos live in `format`.

## Key types

**Plugin pattern.** `Tuner`, `TuningChanger`, `TrackInputSpec`, and `TrackOutputSpec` extend `Plugin` (from `common`),
declaring a `familyName` (e.g. `"tuner"`) and a `typeName` (e.g. `"mpe"`). The `format` module keys JSON
(de)serialization off these names, so adding a variant means adding a `typeName` here and a serializer there.

**`Tuning`** wraps 12 optional cent offsets, one per pitch class (`None` marks a key with no offset). It carries
named-note accessors and combinators — notably `fill`, `overwrite`, and `merge` (which yields `None` on a conflict
beyond tolerance) — that the `composition` reducer uses when combining tunings. `Tuning.Standard` is 12-EDO.

**Tuners.** `Tuner` (the `@NotThreadSafe` plugin trait) is the protocol abstraction; its three methods define the
contract the pipeline drives: `reset()` configures the output device, `tune(tuning)` stores a tuning and emits the
messages it requires now, and `process(message)` rewrites each message flowing through the track. Implementations:

- `MtsTuner` and its four octave variants (`MtsOctave{1,2}Byte{Non,}RealTimeTuner`) retune the instrument's pitch table
  in advance via a single MTS SysEx, so notes pass through untouched. The SysEx bytes are built by `MtsMessageGenerator`
  / `MtsOctaveMessageGenerator`.
- `MonophonicPitchBendTuner` tunes via per-channel Pitch Bend; because Pitch Bend is channel-wide it enforces monophony,
  folding all input onto one output channel and combining the performer's expressive bend with the tuning bend.
- `MpeTuner` is the polyphonic tuner: it distributes notes across MPE Member Channels so each can carry an independent
  pitch-class bend, and reconfigures zones on an MPE Configuration Message. Its supporting types (`MpeZone*`,
  `MpeChannelAllocator`) model the zone layout and note→channel allocation. See [Supported tuning
  protocols](#supported-tuning-protocols) and the linked design docs.

**Tuning-change detection.** `TuningChanger` (`@NotThreadSafe` plugin) inspects messages and returns a `TuningChange`;
`PedalTuningChanger` triggers on a pedal-like CC crossing a threshold, reading its trigger map from a
`TuningChangeTriggers[T]` that binds trigger values (CC numbers here) to previous/next/index changes. `TuningChange`
splits into `EffectiveTuningChange` (`PreviousTuningChange` / `NextTuningChange` / `IndexTuningChange`, which actually
change the tuning) and `IneffectiveTuningChange` (no change, or "part of a trigger pattern but nothing yet" — e.g. a
held pedal's CC stream).

**The processor pipeline.** Each `Tuner`/`TuningChanger` is wrapped in a `MidiProcessor` (from `sc-midi`) so it can be
chained. `TuningChangeProcessor` asks its `TuningChanger`s in order (first effective decision wins) and, on an effective
change, calls `TuningService.changeTuning`. `TunerProcessor` wraps a `Tuner`, forwarding `tune`/`process`, sending
`reset()` on connect, and restoring 12-EDO on disconnect.

**Track and lifecycle.** `Track` (`@ThreadSafe`) is one instrument pipeline built from a `TrackSpec`: it opens the
input/output MIDI devices via `MidiManager` and assembles the processor chain (see [Track pipeline](#track-pipeline)).
`TrackSpec` / `TrackSpecs` are the declarative description of a track and an immutable, id-keyed ordered collection of
them. `TrackIO` holds the input/output spec plugins, including inter-track routing (`FromTrackInputSpec` /
`ToTrackOutputSpec`). `TrackManager` (`@NotThreadSafe`) builds and replaces the live tracks from `TrackSpecs`, wires
inter-track connections, and re-tunes every track when the tuning changes (it subscribes to `TuningEvent`).

**Sessions, services, and events.** Mutable state lives in `@NotThreadSafe` `*Session` objects (business-thread only)
and is exposed through `@ThreadSafe` `*Service` facades that marshal calls onto the business thread via `Businessync`:

- `TuningSession` holds the `Seq[Tuning]` and current `tuningIndex`; mutating either publishes a `TuningEvent`
  (`TuningIndexUpdatedEvent` / `TuningsUpdatedEvent`). `TuningService.changeTuning` runs the matching session mutation
  on the business thread.
- `TrackSession` loads tracks from a URI via `TrackRepo` and offers add/update/move/remove editing, pushing changes to
  `TrackManager` and publishing `TrackEvent`s; `TrackService` is its thread-safe facade.
- `TunerModule` is the composition root that lazily wires the sessions, services, an internal `MidiManager`, and the
  `TrackManager`. The `app` layer injects this rather than constructing the pieces individually.

**Persistence.** `TrackRepo` is the repository-pattern trait for reading/writing `TrackSpecs` by `URI`. It lives here
because `tuner` owns the domain types, but its `TrackFormat` and the concrete `File`/`Http`/`Default` repos live in the
`format` module.

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

- Either processor stage is optional: a `TrackSpec` with no `tuningChangers` omits that stage, and likewise for the
  `tuner`.
- Input/output can be a MIDI device or another track (`FromTrackInputSpec` / `ToTrackOutputSpec`); `TrackManager` wires
  these inter-track connections after constructing the tracks, using each track's `multiTransmitter`.
- A device spec's optional channel config means *filter incoming messages by channel* (input) or *remap outgoing
  messages to a channel* (output).

## Tuning-change flow

A pedal press (or any trigger) flows through the chain as follows:

```
MIDI trigger message (e.g. CC pedal)
  → TuningChangeProcessor (asks each TuningChanger.decide; first EffectiveTuningChange wins)
  → TuningService.changeTuning(effectiveChange)   [marshalled onto the business thread via Businessync]
  → TuningSession.{next,previous,index}           (updates tuningIndex)
  → TuningIndexUpdatedEvent (published via Businessync)
  → TrackManager.onTuningChanged
  → Track.tune(currentTuning) for every track
  → TunerProcessor.tune → Tuner.tune → protocol MIDI messages
  → output device
```

The reverse, application-driven path (loading a composition) sets the available tunings: a `TuningList` from the
`composition` module is assigned to `TunerModule.tuningSession.tunings`, which publishes `TuningsUpdatedEvent` and makes
`TrackManager` re-tune all tracks.

## Threading model

The module follows the Businessync two-thread model (see the [`businessync` doc](../businessync/README.md) and the
project [Threading Model](https://github.com/calinburloiu/microtonalist/wiki/Threading-Model) wiki page):

- All mutable domain state (`TuningSession`, `TrackSession`, `TrackManager`, every `Tuner`/`TuningChanger` and the
  processors that wrap them) is `@NotThreadSafe` and must be touched only on the **business thread**.
- `TuningService` and `TrackService` are `@ThreadSafe` facades: UI and application callers go through them, and they
  marshal work onto the business thread via `Businessync`.
- State changes are broadcast as `BusinessyncEvent`s; `TrackManager`'s subscription to `TuningEvent` is the link between
  a tuning change and the instruments being retuned.

## Dependencies

The module **depends on** `sc-midi` (the Scala-idiomatic MIDI API: `MidiManager`, `MidiProcessor`/`MidiSerialProcessor`,
`MidiSplitter`, message types, `MidiNote`, `PitchClass`, …), `businessync` (the event bus and `BusinessyncEvent`), and
`common` (the `Plugin` trait and `OpenableSession`).

It is **depended on by** `app`, `ui`, `composition`, and `format`, so `tuner` sits below the domain/format/UI layers but
above `sc-midi`/`businessync`/`common`. In particular `composition` produces the `Seq[Tuning]` consumed here, and
`format` provides the `TrackFormat`/`*TrackRepo` implementations of this module's `TrackRepo` trait. See the top-level
[architecture overview](../README.md) for the full module graph.

## Subject to change

These are signalled directly in the code:

- `Track#run` is an unimplemented stub (TODO #121); `TrackManager` already provisions a per-track thread pool, but track
  threads are not yet driven.
- `TuningService.tunings` is `@deprecated` (TODO #99) and slated for removal once the UI migrates to JavaFX.
- `MpeTuner` warns/forbids are still TODO for the non-MPE-input-with-both-zones case, and per-note MPE expression is not
  yet updated continuously (TODO #154).
- `TrackManager` still relies on a Guava `@Subscribe` annotation pending fuller Businessync integration (TODO #90).
