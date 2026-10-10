# `sc-midi` module architecture

## Responsibility

`sc-midi` is a Scala-idiomatic MIDI API: an immutable, typed message model; composable receivers, transmitters and
processors; and device handling that publishes its events on the [Businessync](../businessync/README.md) bus. It knows
nothing about scales, tunings or the GUI.

Java Sound is confined to the `javamidi` package, by convention rather than by the build. `tuner` and `cli` see only the
`MidiManager` / `MidiDeviceHandle` traits and the `MidiMsg` model, and the composition roots instantiate
`JavaMidiManager`. Within `javamidi`, messages cross to and from Java Sound in one place, `JavaMidiDeviceHandle`;
everything upstream of it carries `MidiMsg`. On macOS, devices come from CoreMIDI4J, which replaces Java Sound's
default MIDI device provider.

## Key types

### Device handling

- **`MidiManager`** — discovers, opens and closes devices. Each method takes the `MidiDirection` in which the caller
  wants to use a device; `refresh()` rescans the environment.
- **`MidiDeviceHandle`** — a read-only handle to one device, identified by a `MidiDeviceId`. It can exist before its
  device is available, and its `receiver` and `transmitter` keep working while the device goes away and comes back.
  Only its manager changes its `State`.
- **`MidiEvent`** — the events a manager publishes: a device becoming available or unavailable, opened or closed, each
  with a failure counterpart, and `MidiEnvironmentChangedEvent`.
- **`JavaMidiManager`** — the Java Sound implementation. Java Sound exposes a device working in both directions as two
  devices sharing a `MidiDeviceId`, so the manager accepts only the `Input` and `Output` directions, keeping a registry
  of live handles for each, which it reconciles with the devices found on every refresh. Its handles share a
  `JavaMidiDeviceReferenceCounter`, since Java Sound's `MidiDevice.close()` closes a device however many times it was
  opened.
- **`JavaMidiEnvironment`** — the seam between the manager and the platform, faked in tests; `CoreMidi4JEnvironment`
  is the production implementation.

### Messages

- **`MidiMsg`** — the sealed base of the message model: Channel Voice, Channel Mode, system, System Exclusive and
  Standard MIDI File meta messages, all under `Midi1Msg`, next to `Midi2Msg`, which has no messages yet.
- **`UnsupportedMidiMsg`** — the lossless escape hatch for a message without a type of its own.
- **`JavaMidiConverters`** — `asScala` and `asJava` between `MidiMsg` and Java Sound's `MidiMessage`. Only `Midi1Msg`
  has `asJava`, Java Sound speaking MIDI 1.0 only.

### Plumbing

- **`MidiReceiver`** — consumes `MidiMsg`; every stage of a pipeline is one.
- **`MidiTransmitter`** — a read-only sequence of receivers, implemented by `ImmutableMidiTransmitter`,
  `MutableMidiTransmitter` and the thread-safe `ConcurrentMidiTransmitter`.
- **`MidiSplitter`** — fans each message out to the receivers of a transmitter.
- **`MidiProcessor`** — filters, rewrites or synthesises messages. Its transmitter calls `onAttach` / `onDetach` with
  the receivers a change adds or removes, then `onReceiversChanged`; `tuner` builds its processors on these hooks.
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
  inDev["Input device"] -->|asScala| inHandle["Input handle's transmitter"]
  inHandle --> procs["MidiProcessor chain"]
  procs --> outHandle["Output handle's receiver"]
  outHandle -->|asJava| outDev["Output device"]
```

## Using a device

1. Construct one `JavaMidiManager` at the composition root and pass it around as a `MidiManager`.
2. List the devices usable in a direction with `deviceIdsFor`.
3. Request one with `openDevice`, which returns its `MidiDeviceHandle`.
4. Send through `handle.receiver`; subscribe with `handle.transmitter.addReceiver`.
5. Release it with `closeDevice`. Opening and closing are reference-counted, so a device shared by several tracks
   closes with its last reference.

A device's states, the events reporting them and how they relate to the wiring are described in
[`midi-device-lifecycle.md`](../midi-device-lifecycle.md).

## Threading

`JavaMidiManager` serialises its operations under its locks, whose order its ScalaDoc gives, and publishes the events
they collect only after releasing them, synchronously on the calling thread. For a platform change, that is
CoreMIDI4J's notification thread.

## Subject to change

- Two identical devices plugged in at once share a `MidiDeviceId`, so only the one resolved last gets a handle (#306).
- A vanished device is noticed only once CoreMIDI4J reports it, and a handle whose device failed to open stays unusable
  until the tracks are rebuilt (#302).
- `Midi2Msg` has no messages yet (#292).
