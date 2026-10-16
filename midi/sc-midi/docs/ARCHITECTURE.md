# `sc-midi` module architecture

## Responsibility

`sc-midi` is a Scala-idiomatic MIDI API: an immutable, typed message model; composable receivers, transmitters and
processors; and the traits of device handling, whose events a manager publishes through a `MidiEventPublisher` that the
composition root gives it. It depends on no event bus, and knows nothing about scales, tunings or the GUI.

It doesn't use Java Sound: the [`sc-midi-javamidi`](../../sc-midi-javamidi/docs/ARCHITECTURE.md) module implements its
device traits over it. Only the composition roots, `app` and `cli`, depend on that module; the rest of the
application, `rig` included, sees only the `MidiManager` / `MidiDeviceHandle` traits and the `MidiMsg` model.

## Key types

### Device handling

- **`MidiManager`** — discovers, opens and closes devices. Each method takes the `MidiDirection` in which the caller
  wants to use a device; `refresh()` rescans the environment.
- **`MidiDeviceHandle`** — a read-only handle to one device, identified by a `MidiDeviceId`. It can exist before its
  device is available, and its `receiver` and `transmitter` keep working while the device goes away and comes back.
  Only its manager changes its `State`.
- **`MidiEvent`** — the events a manager publishes: a device becoming available or unavailable, opened or closed, each
  with a failure counterpart, and `MidiEnvironmentChangedEvent`.
- **`MidiEventPublisher`** — where a manager publishes its `MidiEvent`s, synchronously on the thread that made the
  change; `app`'s forwards them to the Businessync bus.

### Messages

- **`MidiMsg`** — the sealed base of the message model: Channel Voice, Channel Mode, system, System Exclusive and
  Standard MIDI File meta messages, all under `Midi1Msg` for MIDI 1.0, next to `Midi2Msg` for MIDI 2.0, which has no
  messages yet.
  - **`UnsupportedMidiMsg`** — the lossless escape hatch for a MIDI 1.0 message without a type of its own.

### Plumbing

- **`MidiReceiver`** — consumes `MidiMsg`; every stage of a pipeline is one.
- **`MidiTransmitter`** — a read-only sequence of receivers, implemented by `ImmutableMidiTransmitter`,
  `MutableMidiTransmitter` and the thread-safe `ConcurrentMidiTransmitter`.
- **`MidiSplitter`** — fans each message out to the receivers of a transmitter.
- **`MidiProcessor`** — filters, rewrites or synthesises messages. Its transmitter calls `onAttach` / `onDetach` with
  the receivers a change adds or removes, then `onReceiversChanged`; `rig` builds its processors on these hooks.
- **`MidiSerialProcessor`** — chains processors end to end, rewiring the chain on every change.

### Helpers

- **`MidiChannelStateTracker`** — derives each channel's state from the messages sent to it: active notes,
  controllers, the selected parameter, pressure, Pitch Bend and the Channel Mode.
- **`RpnSelector`** / **`RpnMessages`** — the parameter a channel holds selected, and its rendering as Control Changes.
- **`MidiNote`**, **`PitchClass`**, **`PitchBendSensitivity`** — value types for notes, pitch classes and the Pitch Bend
  range.

## Message path

```mermaid
flowchart LR
  inDev["Input device"] --> inHandle["Input handle's transmitter"]
  inHandle --> procs["MidiProcessor chain"]
  procs --> outHandle["Output handle's receiver"]
  outHandle --> outDev["Output device"]
```

A handle converts between `MidiMsg` and the messages of its platform.

## Using a device

1. Construct one `MidiManager` at the composition root, e.g. `sc-midi-javamidi`'s `JavaMidiManager`, and pass it
   around as a `MidiManager`.
2. List the devices usable in a direction with `deviceIdsFor`.
3. Request one with `openDevice`, which returns its `MidiDeviceHandle`.
4. Send through `handle.receiver`; subscribe with `handle.transmitter.addReceiver`.
5. Release it with `closeDevice`. Opening and closing are reference-counted, so a device shared by several tracks
   closes with its last reference.

A device's states, the events reporting them and how they relate to the wiring are described in
[`midi-device-lifecycle.md`](../../../docs/architecture/midi-device-lifecycle.md).

## Subject to change

- `Midi2Msg` has no messages yet (#292).
