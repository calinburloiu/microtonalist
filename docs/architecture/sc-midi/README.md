# `sc-midi` module architecture

## Responsibility

The `sc-midi` module (SBT project `sc-midi`, directory `sc-midi/`, `build.sbt` `lazy val` `scMidi`) is a **Scala-idiomatic MIDI API layered over
`javax.sound.midi`**. The standard Java Sound MIDI API is verbose, mutable, byte-oriented, and awkward on macOS; this
module wraps it to give the rest of Microtonalist:

- **Device handling** — enumeration, connection tracking, and reference-counted opening/closing of MIDI devices,
  publishing device lifecycle events on the [Businessync](../businessync/README.md) bus.
- **An immutable, typed message model** — a sealed `ScMidiMessage` hierarchy of `case class`es with validated, named
  fields, plus bidirectional converters to/from Java's `MidiMessage`.
- **MIDI plumbing** — composable receivers/transmitters and a `MidiProcessor` chain that intercepts and rewrites the
  MIDI stream (the foundation on which `tuner` builds its tuning processors).
- **MIDI domain helpers** — `MidiNote`, `PitchClass`, `PitchBendSensitivity`, CC/RPN/NRPN constants, and a per-channel
  state tracker.

It is low-level infrastructure: it knows nothing about scales, tunings, compositions, or the GUI, and depends only on
`businessync` (the device-event bus) and `common` (the `Locking` helper). `sc-midi` is the only Microtonalist module that
touches `javax.sound.midi` directly; the `tuner` module builds tuning logic on top of `MidiProcessor`/`MidiManager` and
the `cli` utility uses it to enumerate devices.

Package: `org.calinburloiu.music.scmidi`, with a `message` sub-package holding the message model and its converters and
constants. macOS support comes from **CoreMIDI4J**, which replaces the default Java Sound MIDI device provider and
prefixes device names with `"CoreMIDI4J - "` (stripped for display by `MidiDeviceId.sanitizedName`).

## Key types

### Device handling

**`MidiManager`** is the entry point for device discovery and connection. Constructed with a `Businessync`, it is
`AutoCloseable` and keeps two internal endpoints — one for inputs, one for outputs — because the Java/CoreMIDI4J API
exposes a physical bidirectional device as two separate `MidiDevice` instances that nonetheless share one
`MidiDeviceId`. Its `refresh()` rescans the environment and diffs against the known set; a `CoreMidiNotification`
listener calls it automatically whenever the MIDI environment changes. It offers a per-direction API mirrored for input
and output (availability, id/info enumeration, `open*`/`close*`, `openFirstAvailable*`, handle lookup), emitting the
device events described in [Device lifecycle and events](#device-lifecycle-and-events) as it reconciles state.

**`MidiDeviceHandle`** is a thread-safe handle to a single device identified by a `MidiDeviceId`, created and kept up to
date by `MidiManager`. A handle can exist for a device that is **not currently connected** (its `info`/`device` are
`Option`s defined only once physically connected), and its lifecycle is **reference-counted**: the device opens on the
first `open()` and closes on the last `close()`. `open()` may be called before the device is connected — the handle
moves to `WaitingToOpen` and opens automatically when the device appears (a small `State` enum captures the
Closed/Connected/WaitingToOpen/Open transitions, drawn in the companion's ScalaDoc). Callers **send** to an output via
`handle.receiver` and **subscribe** to an input via `handle.multiTransmitter`; both are wired through an internal
`MidiSplitter`, so they survive disconnect/reconnect without re-wiring.

Supporting value types: `MidiDeviceId` (`case class(name, vendor)` derived from Java device info) and `MidiEndpointType`
(an `enum` of `None`/`Input`/`Output`/`InputOutput`).

**`MidiEvent`** is a sealed `BusinessyncEvent` hierarchy — everything `MidiManager` publishes on the bus.
`MidiEnvironmentChangedEvent` signals a change to the environment; the rest come as success/failure pairs for each
lifecycle transition (connected/disconnected/opened/closed, each with a `…FailedTo…Event` carrying the cause). All carry
the `MidiDeviceId`. Note "connected" means *available to the system*, not *opened by the application* — they are
distinct, separately-evented states.

### MIDI message model (`message` sub-package)

**`ScMidiMessage`** is the sealed base of the immutable message model — the Scala-idiomatic counterpart to Java's
mutable, byte-oriented `MidiMessage`/`ShortMessage`. Its sub-hierarchies cover channel voice/mode messages
(`ChannelScMidiMessage`, with a `mapChannel` that rewrites the channel), system-common and system-real-time messages,
the full set of Standard MIDI File meta events, and System Exclusive (`SysExScMidiMessage`). Anything with no dedicated
counterpart becomes `UnsupportedScMidiMessage`, a lossless escape hatch that round-trips back to the right Java type.
`PitchBendScMidiMessage` is notable: it normalises Java's two raw LSB/MSB bytes into a single signed 14-bit value and
offers cents conversion against a `PitchBendSensitivity`.

**`JavaMidiConverters`** is the boundary with Java Sound MIDI, modelled after `scala.jdk.CollectionConverters`:
importing its members enables `scMessage.asJava` / `javaMessage.asScala`. Both directions dispatch through lookup tables
(by concrete subtype `Class` outbound, by status/meta-type byte inbound) rather than large pattern matches. Value
validation for message constructors is centralized in `MidiRequirements` (channel and bit-width `require…` checks), and
the controller/parameter numbers live in `ScMidiCc` / `ScMidiRpn` / `ScMidiNrpn` (including the MPE Configuration
Message and the MPE Slide CC).

### MIDI plumbing (receivers, transmitters, processors)

These are the composable pieces `tuner` builds its tuning pipeline from:

- **`MidiSplitter`** — a `Receiver` that fans every incoming message out to a configurable set of receivers; used inside
  `MidiDeviceHandle` to broadcast a device's stream.
- **`MultiTransmitter`** — a thread-safe transmitter allowing **multiple** receivers, unlike Java's single-receiver
  `Transmitter`.
- **`ScMidiReceiver`** — an `AutoCloseable` counterpart of `javax.sound.midi.Receiver` that consumes `ScMidiMessage`
  directly, so callers avoid wrapping/unwrapping Java messages.
- **`MidiProcessor`** — a MIDI interceptor that can filter, modify, or synthesise messages as they pass through.
  Subclasses implement `process(message, timeStamp): Seq[MidiMessage]`; `onConnect`/`onDisconnect` callbacks let a
  processor leave the downstream device consistent when rewired. **This is the abstraction `tuner` extends** to tune the
  MIDI stream.
- **`MidiSerialProcessor`** — a `MidiProcessor` that chains a mutable, thread-safe sequence of `MidiProcessor`s end to
  end, rewiring the chain automatically on every mutation (and forwarding input straight to the output when empty).
- **`ScMidiChannelStateTracker`** — an explicitly `@NotThreadSafe` `ScMidiReceiver` (for a single track thread) that
  derives **per-channel MIDI state** (active notes, CC/RPN/NRPN/pressure/pitch-bend/program values) from the messages
  sent to it, implementing the RPN/NRPN Data Entry protocol and the relevant Channel Mode messages.
  `MonophonicPitchBendTuner` uses it to track held-note state.

### MIDI domain helpers

The package object and a few value types provide `MidiNote` (a value class over a 0–127 note number, with `pitchClass`,
`octave`, `freq`, and named constants), `PitchClass` (a value class over 0–11 with sharp/flat names and parsing), and
`PitchBendSensitivity` (RPN #0 pitch-bend range, default ±2 semitones, with a helper that builds the RPN message
sequence). Smaller helpers (`isInputDevice`/`isOutputDevice`, `mapShortMessageChannel`, clamping) round out the object.

## How MIDI devices are opened, enumerated, and used

1. Construct a single `MidiManager(businessync)`; its initialization runs a first `refresh()` and registers a
   `CoreMidiNotification` listener so the device list stays current.
2. Enumerate with `inputDeviceIds` / `outputDeviceIds` (or the `…DevicesInfo` variants); `sanitizedName` gives a
   UI-friendly name.
3. Open a device with `openInput`/`openOutput`, or try a prioritised list with `openFirstAvailable*`; each returns a
   `MidiDeviceHandle`.
4. Use the handle: send via `handle.receiver` (outputs), subscribe via `handle.multiTransmitter` (inputs). Because the
   wiring goes through an internal `MidiSplitter`, it survives disconnect/reconnect cycles.
5. `closeInput`/`closeOutput` (reference-counted) release a device; `MidiManager.close()` closes everything and removes
   the listener.

## Device lifecycle and events

`MidiManager`'s internal endpoints reconcile the scanned device set against known state on every `refresh()` and
publish the [`MidiEvent`s](#device-handling) as side effects of that diff: a newly seen device is reported
*connected*, a vanished one *disconnected* (preceded by `MidiEnvironmentChangedEvent`), opening and closing emit
*opened*/*closed*, and any failed transition emits the matching `…Failed…Event` carrying the exception. Consumers —
notably the `tuner` track lifecycle — react by subscribing through Businessync rather than polling.

## Message conversion model

The `ScMidiMessage` ↔ `MidiMessage` conversion provided by
[`JavaMidiConverters`](#midi-message-model-message-sub-package) runs in two directions. Inbound, a device's raw
message becomes a typed, validated `ScMidiMessage` via `.asScala`, with anything unrecognised falling back to
`UnsupportedScMidiMessage` so nothing is lost; outbound, an `ScMidiMessage` is rendered back to a Java `MidiMessage`
via `.asJava`. Within `sc-midi`, message code prefers the typed model and validated constructors; the raw byte layer
stays confined to the converters and the few places that talk to `javax.sound.midi` directly.

## Dependencies

**Depends on** `businessync` (the bus used to publish `MidiEvent`s) and `common` (the `Locking` mixin used by the
thread-safe device/transmitter/processor classes), plus the external **CoreMIDI4J** library and the inherited common
logging/test stack.

**Depended on by** `tuner` (builds `MidiProcessor`-based pipelines and uses `MidiManager` for device I/O) and `cli`
(lists connected devices); `app`, `composition`, `format`, and `ui` reach it transitively through `tuner`.

## Notes / subject to change

- Coverage targets are currently below the project-wide 80% goal (TODO #177); device-handling code that needs real MIDI
  hardware is hard to cover with unit tests.
- The `ScMidiMessage` model is broad (it covers the full set of SMF meta events) even though Microtonalist does not yet
  exercise every one; treat the typed model as the supported surface and `UnsupportedScMidiMessage` as the lossless
  escape hatch.
