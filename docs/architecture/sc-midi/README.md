# `scMidi` module architecture

## Responsibility

The `scMidi` module (SBT project `scMidi`, directory `sc-midi/`) is a **Scala-idiomatic MIDI API layered over
`javax.sound.midi`**. The standard Java Sound MIDI API is verbose, mutable, byte-oriented, and awkward on macOS; this
module wraps it to give the rest of Microtonalist:

- **Device handling** — enumeration, connection tracking, and reference-counted opening/closing of MIDI devices,
  publishing device lifecycle events on the [Businessync](../businessync/README.md) bus.
- **An immutable, typed message model** — a sealed `ScMidiMessage` hierarchy of `case class`es with validated,
  named fields, plus bidirectional converters to/from Java's `MidiMessage`.
- **MIDI plumbing** — composable receivers/transmitters and a `MidiProcessor` chain that intercepts and rewrites the
  MIDI stream (the foundation on which the `tuner` module builds its tuning processors).
- **MIDI domain helpers** — `MidiNote`, `PitchClass`, `PitchBendSensitivity`, CC/RPN/NRPN constants, and a per-channel
  state tracker.

Its role in the layered architecture:

- It is a low-level infrastructure module. It does **not** know about scales, tunings, compositions, or the GUI; it
  deals only with MIDI devices and messages.
- It depends only on `businessync` (for the device-event bus) and `common` (for the `Locking` concurrency helper). See
  [Dependencies](#dependencies).
- The `tuner` module builds tuning logic on top of `MidiProcessor` and `MidiManager`, and the `cli` utility uses it to
  enumerate MIDI devices. `scMidi` is the only Microtonalist module that touches `javax.sound.midi` directly.

Package: `org.calinburloiu.music.scmidi`, with a `message` sub-package
(`org.calinburloiu.music.scmidi.message`) holding the message model and its converters/constants. macOS support comes
from the **CoreMIDI4J** library, which replaces the default Java Sound MIDI device provider (see
[CoreMIDI4J and device naming](#coremidi4j-and-device-naming)).

## Key types

### Device handling

#### `MidiManager` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiManager.scala:35`)

The entry point for device discovery and connection. Constructed with a `Businessync`, it is `AutoCloseable` and
maintains two private `MidiEndpoint` instances — one for inputs, one for outputs — because the Java/CoreMIDI4J API
exposes a physical bidirectional device as two separate `MidiDevice` instances (input and output) that nonetheless
share a single `MidiDeviceId`.

- `refresh()` (`:60`) rescans the environment via `CoreMidiDeviceProvider.getMidiDeviceInfo`, diffs against the known
  set, and updates both endpoints. A `CoreMidiNotification` listener (`:42`) calls `refresh()` automatically whenever
  the MIDI environment changes (and publishes `MidiEnvironmentChangedEvent`).
- Per-direction API (mirrored for input and output): `is{Input,Output}Available`, `{input,output}DeviceIds`,
  `{input,output}DevicesInfo`, `open{Input,Output}(deviceId)`, `openFirstAvailable{Input,Output}(deviceIds)`,
  `{input,output}DeviceHandleOf`, `{input,output}OpenedDevices`, `close{Input,Output}(deviceId)`.
- The private nested `MidiManager.MidiEndpoint` (`:169`) holds two `ConcurrentHashMap`s (id → `MidiDevice.Info` for
  connected devices, id → `MidiDeviceHandle` for opened devices) and is responsible for emitting the connect /
  disconnect / open / close events as it reconciles state
  (see [Device lifecycle and events](#device-lifecycle-and-events)).

#### `MidiDeviceHandle` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala:53`)

A thread-safe (`@ThreadSafe`, `Locking`) handle to a single MIDI device, identified by a `MidiDeviceId`. Created and
kept up to date by `MidiManager`. A handle can exist for a device that is **not currently connected**; `info` and
`device` (the underlying `javax.sound.midi.MidiDevice`) are `Option`s that become defined only once the device is
physically connected.

- Reference-counted lifecycle: `open()` / `close()` track an `openRefCount`; the device opens only on the first `open`
  and closes on the last `close`. `open()` may be called before the device is connected — the handle moves to
  `WaitingToOpen` and opens automatically once `onConnect` fires.
- State machine via the `MidiDeviceHandle.State` enum (`:320`): `Closed`, `Connected`, `WaitingToOpen`, `Open` (each
  carrying `isConnected` / `isOpen` flags). The companion's ScalaDoc draws the transition diagram.
- I/O accessors: `receiver: Receiver` (`:261`, a private `HandleReceiver` forwarding to the device's receiver) lets
  callers **send** to an output device; `multiTransmitter: MultiTransmitter` (`:269`) lets callers **subscribe** to
  messages from an input device. Both are wired through an internal `MidiSplitter`, so they keep working across
  reconnects without re-wiring.
- `endpointType`, `isInputDevice`, `isOutputDevice`, `isConnected`, `isOpen` query capabilities and status.
- `private[scmidi]` hooks `onConnect(info)` / `onDisconnect()` are driven by `MidiManager`.

#### `MidiDeviceId` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceId.scala:30`)

Value identifier of a device, a `case class(name, vendor)`. `apply(MidiDevice.Info)` derives one from Java device info;
`correspondsToInfo` checks the match; `sanitizedName` strips the CoreMIDI4J `"CoreMIDI4J - "` name prefix for display.

#### `MidiEndpointType` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiEndpointType.scala:26`)

`enum` describing input/output capability: `None`, `Input`, `Output`, `InputOutput`. The companion's `apply(isInput,
isOutput)` builds the right case from two booleans.

#### `MidiEvent` and subtypes (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiEvent.scala:24`)

A sealed `MidiEvent extends BusinessyncEvent` hierarchy — all events `MidiManager` publishes on the Businessync bus. The
environment event plus matched success/failure pairs for each lifecycle transition:

| Event | Meaning |
| ----- | ------- |
| `MidiEnvironmentChangedEvent` (`:34`) | The MIDI environment changed (devices added/removed/reconfigured). |
| `MidiDeviceConnectedEvent` / `MidiDeviceFailedToConnectEvent(cause)` | A device became available / failed to connect. |
| `MidiDeviceDisconnectedEvent` / `MidiDeviceFailedToDisconnectEvent(cause)` | A device was removed / failed to disconnect. |
| `MidiDeviceOpenedEvent` / `MidiDeviceFailedToOpenEvent(cause)` | A device was opened / failed to open. |
| `MidiDeviceClosedEvent` / `MidiDeviceFailedToCloseEvent(cause)` | A device was closed / failed to close. |

All carry the `MidiDeviceId`; failure variants also carry the causing `Exception`. Note that "connected" means
*available to the system*, not *opened by the application*.

### MIDI message model (`message` sub-package)

#### `ScMidiMessage` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiMessage.scala:32`)

The sealed base trait for the immutable message model — the Scala-idiomatic counterpart to Java's mutable, byte-oriented
`MidiMessage`/`ShortMessage`. Sub-hierarchies:

- `ChannelScMidiMessage(channel)` (`:40`) — channel voice/mode messages with a validated `channel` (0–15) and a
  `mapChannel(map)` that returns a same-typed copy with a rewritten channel. Concrete cases: `NoteOnScMidiMessage`,
  `NoteOffScMidiMessage` (both via abstract `NoteScMidiMessage`), `PolyPressureScMidiMessage`, `CcScMidiMessage`,
  `ProgramChangeScMidiMessage`, `ChannelPressureScMidiMessage`, and `PitchBendScMidiMessage`.
- `SysCommonScMidiMessage` (`:54`) — `MidiTimeCodeScMidiMessage`, `SongPositionPointerScMidiMessage`,
  `SongSelectScMidiMessage`, `TuneRequestScMidiMessage`.
- `SysRealTimeScMidiMessage` (`:57`) — `TimingClock`, `Start`, `Continue`, `Stop`, `ActiveSensing`, `SystemReset`
  (all `case object`s).
- `MetaScMidiMessage` (`:60`) — Standard MIDI File meta events (`SequenceNumber`, the text-bearing family `Text` /
  `CopyrightNotice` / `TrackName` / `InstrumentName` / `Lyric` / `Marker` / `CuePoint` / `ProgramName` / `DeviceName`,
  `MidiChannelPrefix`, `MidiPort`, `EndOfTrack`, `SetTempo`, `SmpteOffset`, `TimeSignature`, `KeySignature`,
  `SequencerSpecific`). Each carries its SMF `MetaType` byte in its companion.
- `SysExScMidiMessage` (`:376`) — System Exclusive, storing the full byte sequence as an `ArraySeq[Byte]` so
  `case class` structural equality works.
- `UnsupportedScMidiMessage` (`:632`) — fallback wrapping raw bytes of any Java message with no dedicated counterpart;
  it round-trips back to the correct Java type (Short/Sysex/Meta, detected from the status byte).

`PitchBendScMidiMessage` (`:203`) is notable: it normalises Java's two raw LSB/MSB data bytes into a single signed
14-bit `value` (−8192…8191) and offers cents conversion (`centsFor` / `cents`) against a `PitchBendSensitivity`. Its
companion holds the byte ↔ value ↔ cents conversion math (`convertDataBytesToValue`, `convertValueToDataBytes`,
`convertCentsToValue`, `convertValueToCents`, `fromCents`).

#### `JavaMidiConverters` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/JavaMidiConverters.scala:40`)

Bidirectional converters modelled after `scala.jdk.CollectionConverters`. Importing its members enables the extension
methods `scMessage.asJava` and `javaMessage.asScala`. Both directions dispatch through lookup tables rather than large
pattern matches: `asJava` keys on the concrete subtype `Class`, `asScala` keys on the MIDI command / status byte
(short messages) or meta-type byte (meta messages), defaulting to `UnsupportedScMidiMessage`. It also reconstructs
SMF meta payloads using big-endian and variable-length-quantity (VLQ) helpers.

#### `MidiRequirements` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiRequirements.scala:22`)

Centralised value validation (`require…`) used by message constructors: `requireChannel` (0–15) and
unsigned/signed bit-width checks (3/4/7/8/14/16/24-bit and signed 14-bit). It defines `MinSigned14BitValue` /
`MaxSigned14BitValue` (±8192/8191) used by pitch bend.

#### CC / RPN / NRPN constants

- `ScMidiCc` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiCc.scala:22`) — Control Change
  controller numbers: RPN/NRPN MSB/LSB, Data Entry/Increment/Decrement, All Sound/Notes Off, Reset All Controllers,
  Bank Select, Modulation, Volume, Pan, Expression, the pedals, and `MpeSlide` (#74).
- `ScMidiRpn` (`.../message/ScMidiRpn.scala:22`) — Registered Parameter Numbers: Null, Pitch Bend Sensitivity,
  Coarse/Fine Tuning, Tuning Bank/Program Select, and the MPE Configuration Message (MCM).
- `ScMidiNrpn` (`.../message/ScMidiNrpn.scala:22`) — Null NRPN MSB/LSB (NRPNs are otherwise vendor-specific).

### MIDI plumbing (receivers, transmitters, processors)

#### `MidiSplitter` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiSplitter.scala:27`)

A `Receiver` that fans every incoming message out to a configurable set of receivers, exposed via its `receiver`
(input) and `multiTransmitter` (output). Used inside `MidiDeviceHandle` to broadcast a device's incoming stream.

#### `MultiTransmitter` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MultiTransmitter.scala:29`)

A thread-safe (`Locking`) transmitter abstraction allowing **multiple** receivers, unlike Java's `Transmitter` which
permits only one. Manage the set with `receivers` (getter/setter), `addReceiver(s)`, `removeReceiver`, `clearReceivers`.

#### `ScMidiReceiver` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiReceiver.scala:28`)

A Scala-idiomatic `AutoCloseable` counterpart of `javax.sound.midi.Receiver` that consumes `ScMidiMessage` directly
(`send(message, timeStamp = -1L)`), so callers avoid wrapping/unwrapping Java messages. After `close()`, `send` should
be a no-op.

#### `MidiProcessor` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiProcessor.scala:37`)

A MIDI interceptor that can filter, modify, or synthesise messages as they pass through. It owns a nested
`MidiProcessorReceiver` (input) and `MidiProcessorTransmitter` (output); subclasses implement the abstract
`process(message, timeStamp): Seq[MidiMessage]`. It is "connected" once a receiver is set on its transmitter, which
triggers the `onConnect()` / `onDisconnect()` callbacks (used to leave the downstream device in a consistent state when
the processor is rewired). The transmitter offers Scala-idiomatic `receiver: Option[Receiver]` getter/setter. This is
the abstraction the `tuner` module extends to implement tuning of the MIDI stream.

#### `MidiSerialProcessor` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiSerialProcessor.scala:35`)

A `MidiProcessor` that chains a mutable, thread-safe sequence of `MidiProcessor`s end to end, terminating in the
receiver set on its own transmitter:

```
MidiProcessor -> MidiProcessor -> ... -> MidiProcessor -> output receiver
```

It rewires the chain automatically on every mutation: `processors` (get/set), `insert`, `append`, `update`, `remove`,
`removeAt`, `clear`, `size`. When the chain is empty it simply forwards input to the output receiver.

#### `ScMidiChannelStateTracker` (`sc-midi/.../ScMidiChannelStateTracker.scala:41`)

A `ScMidiReceiver` (explicitly `@NotThreadSafe`, intended for a single track thread) that derives and exposes
**per-channel MIDI state** from the messages sent to it: active notes (with velocity and Polyphonic Key Pressure),
CC values, RPN/NRPN values, Channel Pressure, Pitch Bend, and Program Change. It implements the full RPN/NRPN Data
Entry / Increment / Decrement protocol and the relevant Channel Mode messages (All Sound/Notes Off, Reset All
Controllers, modelled per MIDI 1.0 RP-015). Getters fall back through a documented lookup chain (recorded value →
per-call override → constructor defaults → companion defaults). The companion provides MIDI-1.0 default tables
(`DefaultCcValues`, `DefaultRpnValues`, empty `DefaultNrpnValues`) and the `RpnSelector` enum (`None` / `Rpn` / `Nrpn`).

### MIDI domain helpers (package object and value types)

#### Package object (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/package.scala`)

- `MidiNote` (`:29`) — a value class over an `Int` note number (0–127), with `pitchClass`, `octave`, `freq` (relative
  to A4 = 440 Hz), `assertValid()`, and a companion full of named constants (`C4`…`C5`, `ConcertPitch = A4`,
  `Lowest`/`Highest`) plus `apply(pitchClass, octave)`.
- `DefaultConcertPitchFreq = 440.0` Hz.
- `isInputDevice` / `isOutputDevice` (`:84`, `:89`) — capability checks based on a device's max transmitters/receivers.
- `mapShortMessageChannel` (`:94`) — rewrites the channel of a Java `ShortMessage`/`MidiMessage`.
- `clampValue` (`:104`) — `Int` and `Double` clamping helpers.

#### `PitchClass` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/PitchClass.scala:22`)

A value class over a pitch-class number (0–11) with named constants (`C`, `CSharp`/`DFlat`, …), display names with
sharps/flats, an implicit conversion to `Int`, and parsing helpers `fromNumber`, `fromName`, `parse`.

#### `PitchBendSensitivity` (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/PitchBendSensitivity.scala:44`)

`case class(semitones, cents = 0)` modelling RPN #0 pitch-bend range, exposing `totalCents`; `Default` is ±2 semitones.
The accompanying `PitchBendSensitivityMessages.create(channel, sensitivity)` (`:61`) builds the RPN message sequence
(select Pitch Bend Sensitivity RPN, set Data Entry MSB/LSB, then null the RPN) as Java `MidiMessage`s.

## How MIDI devices are opened, enumerated, and used

1. Construct a single `MidiManager(businessync)`. Its `init()` runs an initial `refresh()` and registers a
   `CoreMidiNotification` listener so the device list stays current.
2. Enumerate with `inputDeviceIds` / `outputDeviceIds` (or the `…DevicesInfo` variants). IDs are `MidiDeviceId`s;
   `sanitizedName` gives a UI-friendly name.
3. Open a device with `openInput(id)` / `openOutput(id)`, or try a prioritised list with
   `openFirstAvailable{Input,Output}(ids)` (returns the first that ends up open). Each returns a `MidiDeviceHandle`.
4. Use the handle: send via `handle.receiver` (outputs) and subscribe via `handle.multiTransmitter` (inputs). Because
   wiring goes through an internal `MidiSplitter`, it survives disconnect/reconnect cycles.
5. `closeInput` / `closeOutput` (reference-counted) release a device; `MidiManager.close()` closes everything and
   removes the notification listener.

A device need not be connected when its handle is opened: `MidiDeviceHandle` enters `WaitingToOpen` and opens
automatically once the physical device appears.

## Device lifecycle and events

`MidiManager.MidiEndpoint` reconciles the scanned device set against its known state and publishes Businessync events as
side effects:

- On `refresh()`/`updateDevices`: newly seen devices emit `MidiDeviceConnectedEvent`; vanished devices emit
  `MidiDeviceDisconnectedEvent`. Any environment change first emits `MidiEnvironmentChangedEvent`.
- `purgeDisconnectedDevices()` closes and forgets handles whose devices were disconnected, emitting
  `MidiDeviceClosedEvent`.
- `openDevice` emits `MidiDeviceOpenedEvent` on success. Failures during connect/disconnect/open/close emit the
  corresponding `…Failed…Event` carrying the exception (see [`MidiEvent`](#midievent-and-subtypes)).

Consumers (notably the `tuner` module's track lifecycle) subscribe to these events through Businessync rather than
polling. "Connected" is about system availability; "opened" is about this application actively using the device — they
are distinct, separately-evented states.

## Message conversion model

The boundary with Java Sound MIDI is the `ScMidiMessage` ↔ `MidiMessage` pair handled by `JavaMidiConverters`:

- Inbound: a device's raw `MidiMessage` is turned into a typed, validated `ScMidiMessage` via `.asScala` (anything
  unrecognised becomes `UnsupportedScMidiMessage`, never lost).
- Outbound: an `ScMidiMessage` is rendered to a Java `MidiMessage` via `.asJava` for transmission.

Within `scMidi`, message-producing/processing code prefers the typed model and validated constructors; the raw byte
layer is confined to the converters and the few places that talk to `javax.sound.midi` directly.

## Dependencies

Verified against `build.sbt` (the `scMidi` project block at `build.sbt:217`).

**Upstream (what `scMidi` depends on):**

- `businessync` — the event bus used to publish `MidiEvent`s about device state.
- `common` — the `Locking` concurrency mixin (`org.calinburloiu.music.microtonalist.common.concurrency.Locking`) used
  by the thread-safe device/transmitter/processor classes.
- External library **CoreMIDI4J** (`uk.co.xfactory-librarians % coremidi4j`) for reliable MIDI device enumeration and
  notifications on macOS (and Windows), plus the project-wide `commonDependencies` (Logback, scala-logging, and
  ScalaTest/ScalaMock in `Test` scope).

**Downstream (what depends on `scMidi`):**

- `tuner` (`dependsOn(businessync, common, scMidi)`) — builds `MidiProcessor`-based tuning pipelines and uses
  `MidiManager` for device I/O.
- `cli` (`dependsOn(scMidi)`) — the standalone utility that, e.g., lists connected MIDI devices.
- Transitively, `app`, `composition`, `format`, and `ui` reach `scMidi` through `tuner`.

## Notes / subject to change

- Coverage targets are currently below the project-wide 80% goal (`coverageSettings(stmt = 62, branch = 44)`, with
  **TODO #177** to raise them); device-handling code that requires real MIDI hardware is hard to cover with unit tests.
- `MidiProcessorReceiver.close()` / `MidiProcessorTransmitter.close()` are intentionally minimal stubs ("Nothing to do
  for the moment") and may gain real cleanup logic later.
- The `ScMidiMessage` model is broad (channel voice/mode, system common/real-time, SysEx, and a full set of SMF meta
  events) even though Microtonalist itself does not yet exercise every meta event; treat the typed model as the
  supported surface and `UnsupportedScMidiMessage` as the lossless escape hatch.
