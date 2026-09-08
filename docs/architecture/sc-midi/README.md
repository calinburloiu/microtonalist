# `sc-midi` module architecture

## Responsibility

The `sc-midi` module (SBT project `sc-midi`, directory `sc-midi/`, `build.sbt` `lazy val` `scMidiModule`) is a **Scala-idiomatic MIDI API layered over
`javax.sound.midi`**. The standard Java Sound MIDI API is verbose, mutable, byte-oriented, and awkward on macOS; this
module wraps it to give the rest of Microtonalist:

- **Device handling** — enumeration, connection tracking, and reference-counted opening/closing of MIDI devices,
  publishing device lifecycle events on the [Businessync](../businessync/README.md) bus.
- **An immutable, typed message model** — a sealed `MidiMsg` hierarchy of `case class`es with validated, named
  fields, plus bidirectional converters to/from Java's `MidiMessage`.
- **MIDI plumbing** — composable receivers/transmitters and a `MidiProcessor` chain that intercepts and rewrites the
  MIDI stream (the foundation on which `tuner` builds its tuning processors).
- **MIDI domain helpers** — `MidiNote`, `PitchClass`, `PitchBendSensitivity`, CC/RPN/NRPN constants, and a per-channel
  state tracker.

It is low-level infrastructure: it knows nothing about scales, tunings, compositions, or the GUI, and depends only on
`businessync` (the device-event bus) and `common` (the `Locking` helper). `sc-midi` is almost the only Microtonalist
module that touches `javax.sound.midi` directly: `tuner` no longer imports it at all (#281), and `cli` still reads
`MidiDevice.Info` to enumerate devices until #282. Inside `sc-midi`, messages cross to and from Java Sound in exactly
one place, `MidiDeviceHandle`; everything upstream of it carries `MidiMsg`.

Package: `org.calinburloiu.music.scmidi`, with a `message` sub-package holding the message model and its constants. A
`javamidi` sub-package is where the code that touches Java Sound directly is being gathered; today it holds
`JavaMidiConverters` and its `MidiDevice` capability extensions; the rest of the module is on its way to becoming a pure
Scala API (see the `Architecture` milestone and #278). macOS support comes from **CoreMIDI4J**, which replaces the
default Java Sound MIDI device provider and prefixes device names with `"CoreMIDI4J - "` (stripped for display by
`MidiDeviceId.sanitizedName`).

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
`handle.receiver: MidiReceiver` and **subscribe** to an input via `handle.transmitter: ConcurrentMidiTransmitter`; both
survive disconnect/reconnect without re-wiring. The handle is the **Java Sound boundary**: its receiver converts each
`Midi1Msg` with `asJava` and sends it to the open device (a `Midi2Msg` is dropped with a warning, since Java Sound
speaks MIDI 1.0 only), and the Java `Receiver` it hands to the device's transmitter converts with `asScala` into an
internal `MidiSplitter(ConcurrentMidiTransmitter())`.

Supporting value types: `MidiDeviceId` (`case class(name, vendor)` derived from Java device info) and `MidiEndpointType`
(an `enum` of `None`/`Input`/`Output`/`InputOutput`).

**`MidiEvent`** is a sealed `BusinessyncEvent` hierarchy — everything `MidiManager` publishes on the bus.
`MidiEnvironmentChangedEvent` signals a change to the environment; the rest come as success/failure pairs for each
lifecycle transition (connected/disconnected/opened/closed, each with a `…FailedTo…Event` carrying the cause). All carry
the `MidiDeviceId`. Note "connected" means *available to the system*, not *opened by the application* — they are
distinct, separately-evented states.

### MIDI message model (`message` sub-package)

**`MidiMsg`** is the sealed base of the immutable message model — the Scala-idiomatic counterpart to Java's mutable,
byte-oriented `MidiMessage`/`ShortMessage`. Directly under it sit **`Midi1Msg`**, the base of every MIDI 1.0 message,
and **`Midi2Msg`**, reserved for MIDI 2.0 and empty for now (#283). Pipeline signatures take `MidiMsg`. Its
sub-hierarchies cover channel voice/mode messages (`ChannelMidiMsg`, with a `mapChannel` that rewrites the channel),
system-common and system-real-time messages, the full set of Standard MIDI File meta events, and System Exclusive
(`SysExMidiMsg`). Anything with no dedicated counterpart becomes `UnsupportedMidiMsg`, a lossless escape hatch that
round-trips back to the right Java type. `PitchBendMidiMsg` is notable: it normalises Java's two raw LSB/MSB bytes into
a single signed 14-bit value and offers cents conversion against a `PitchBendSensitivity`.

**`JavaMidiConverters`**, in the `javamidi` sub-package
(`import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*`), is the boundary with Java Sound MIDI, modelled
after `scala.jdk.CollectionConverters`: importing its members enables `message.asJava` / `javaMessage.asScala`.
`asJava` is defined on `Midi1Msg` only, so converting a future `Midi2Msg` value is a compile-time error; `asScala`
returns `MidiMsg`. Both directions dispatch through lookup tables (by concrete subtype `Class` outbound, by
status/meta-type byte inbound) rather than large pattern matches. The same object also carries the
`isInputDevice`/`isOutputDevice` extensions on `javax.sound.midi.MidiDevice`. Value validation for message constructors
is centralized in `MidiRequirements` (channel and bit-width `require…` checks), and the controller/parameter numbers
live in `MidiCc` / `MidiRpn` / `MidiNrpn` (including the MPE Configuration Message and the MPE Slide CC).

### MIDI plumbing (receivers, transmitters, processors)

These are the composable pieces `tuner` builds its tuning pipeline from:

- **`MidiReceiver`** — an `AutoCloseable` counterpart of `javax.sound.midi.Receiver` that consumes `MidiMsg`
  directly; every stage of the pipeline is one.
- **`MidiTransmitter`** — the read-only, `AutoCloseable` transmitter of the Scala API: a single
  `receivers: Seq[MidiReceiver]` member, no locks. Three implementations, all with a no-op `close()`:
  `ImmutableMidiTransmitter` (a case class whose `withReceiver`/`withReceivers`/`withoutReceiver`/`withoutReceivers`
  return new instances), `MutableMidiTransmitter` (`@NotThreadSafe`; every modifier funnels through `receivers_=`, so
  a subclass overriding the setter intercepts every change) and `ConcurrentMidiTransmitter` (`@ThreadSafe`; the
  mutable one with every accessor and modifier under a `ReentrantReadWriteLock` via `Locking`).
- **`MidiSplitter(transmitter: MidiTransmitter)`** — a `MidiReceiver` that fans every message out to the receivers
  of the transmitter it is given; the caller picks the transmitter implementation, and the splitter never closes it.
  `MidiDeviceHandle` uses one over a `ConcurrentMidiTransmitter` to broadcast a device's stream.
- **`MidiProcessor`** — a MIDI interceptor that can filter, modify, or synthesise messages as they pass through.
  Subclasses implement `process(message: MidiMsg, timeStamp): Seq[MidiMsg]`; its `receiver` processes each message
  once and forwards the results to every receiver of its `transmitter`, a `MidiProcessorTransmitter` (a
  `ConcurrentMidiTransmitter`) that calls `onDisconnect()` before and `onConnect()` after every change of its receiver
  set — from or to a non-empty set respectively, and never for an unchanged set — inside its write lock, so that the
  reset/initialisation messages the hooks emit cannot interleave with a send that has not yet read the receivers (a
  fan-out already in flight is not held off). **This is the abstraction `tuner` extends** to tune the
  MIDI stream. A processor with no output receivers drops messages without processing them.
- **`MidiSerialProcessor`** — a `MidiProcessor` that chains a mutable, thread-safe sequence of `MidiProcessor`s end
  to end, rewiring the chain automatically on every mutation (`receivers = Seq(next.receiver)` between neighbours,
  its own output receivers on the last one) and forwarding input straight to the output when empty. Its hooks take
  its own lock inside the transmitter's, so a chain mutation and an output-receiver change of the same instance must
  not race from two threads (they do not today; #121 removes the concern).
- **`MidiChannelStateTracker`** — an explicitly `@NotThreadSafe` `MidiReceiver` (for a single track thread) that
  derives **per-channel MIDI state** (active notes, CC/RPN/NRPN/pressure/pitch-bend/program values) from the messages
  sent to it, implementing the RPN/NRPN Data Entry protocol and the relevant Channel Mode messages. Notes are
  **reference-counted**: a note struck twice without an intervening release needs two Note Offs to go inactive, which
  is what lets a consumer discharge MIDI 1.0's one-Note-Off-per-Note-On obligation. Active notes are ordered by their
  most recent Note On, so a duplicate Note On moves a note to the end of `orderedActiveNotes` instead of listing it
  twice — each active note appears there exactly once, whatever its reference count.
  `MonophonicPitchBendTuner` uses it to track held-note state; `MpeTuner` uses it for Master Channel notes, which
  bypass its allocator, and also reads it per input channel — the RPN selector for routing, and the Pitch Bend,
  Channel Pressure, and CC #74 (MPE Slide) state it seeds a newly allocated note's Expression Values from.

### MIDI domain helpers

The package object and a few value types provide `MidiNote` (a value class over a 0–127 note number, with `pitchClass`,
`octave`, `freq`, and named constants), `PitchClass` (a value class over 0–11 with sharp/flat names and parsing), and
`PitchBendSensitivity` (RPN #0 pitch-bend range, default ±2 semitones, with a helper that builds the RPN message
sequence). The package object also carries the `clampValue` helpers; channel rewriting is `ChannelMidiMsg.mapChannel`.

`RpnSelector` and `RpnMessages` are the two halves of the Registered and Non-Registered Parameter vocabulary.
`RpnSelector` is the parameter a channel holds selected — `None`, an `Rpn(msb, lsb)`, or an `Nrpn(msb, lsb)` — and is
the type the two directions of MIDI 1.0's parameter procedure meet in: `MidiChannelStateTracker` derives it from an
incoming stream, `RpnMessages` renders it back out. It carries only *complete* parameters: the tracker assembles each
one from its two selector CCs, in whichever order they arrive, and a parameter with a half still pending selects
nothing and so reads as `RpnSelector.None`. Only the Null *pair* deselects — a lone CC carrying 127 does not, 127 being
a parameter number like any other, so an RPN or NRPN with a 127 half is selected and tracked like any other.
`PartialRpnSelector`, alongside it in the same file, is the assembly in progress — `RpnSelector` with each half
optional — read through the tracker's `partialRpnSelector` accessor by callers that need to tell a channel awaiting a
parameter's second CC apart from one holding no selection at all, a distinction `rpnSelector` collapses.

`RpnSelector.None` is the deselected state on both sides: `RpnMessages.select` renders it as the Null Function
(RPN 7F 7F), and the tracker reads that same pair back as `None`, so every selector survives a round trip through the
two. Deselecting therefore has no separate vocabulary — there is no `NullRpnSelector` constant to reach for, and the
Null goes out as an RPN whatever it closes, MIDI 1.0 giving the RPN Null the job of cancelling an RPN *or* NRPN
selection. `RpnMessages` also names the parameters Microtonalist selects (Pitch Bend Sensitivity and the MPE
Configuration Message) and renders a selector as its pair of Control Change messages, so that the transmission order of
the pair — LSB before MSB — is decided in one place for every sequence the application emits.

## How MIDI devices are opened, enumerated, and used

1. Construct a single `MidiManager(businessync)`; its initialization runs a first `refresh()` and registers a
   `CoreMidiNotification` listener so the device list stays current.
2. Enumerate with `inputDeviceIds` / `outputDeviceIds` (or the `…DevicesInfo` variants); `sanitizedName` gives a
   UI-friendly name.
3. Open a device with `openInput`/`openOutput`, or try a prioritised list with `openFirstAvailable*`; each returns a
   `MidiDeviceHandle`.
4. Use the handle: send `MidiMsg` values via `handle.receiver` (outputs), subscribe `MidiReceiver`s via
   `handle.transmitter.addReceiver` (inputs). The wiring survives disconnect/reconnect cycles.
5. `closeInput`/`closeOutput` (reference-counted) release a device; `MidiManager.close()` closes everything and removes
   the listener.

## Device lifecycle and events

`MidiManager`'s internal endpoints reconcile the scanned device set against known state on every `refresh()` and
publish the [`MidiEvent`s](#device-handling) as side effects of that diff: a newly seen device is reported
*connected*, a vanished one *disconnected* (preceded by `MidiEnvironmentChangedEvent`), opening and closing emit
*opened*/*closed*, and any failed transition emits the matching `…Failed…Event` carrying the exception. Consumers —
notably the `tuner` track lifecycle — react by subscribing through Businessync rather than polling.

## Message conversion model

The `MidiMsg` ↔ `MidiMessage` conversion provided by
[`JavaMidiConverters`](#midi-message-model-message-sub-package), in the `javamidi` sub-package, runs in two
directions. Inbound, a device's raw message becomes a typed, validated `MidiMsg` via `.asScala`, with anything
unrecognised falling back to `UnsupportedMidiMsg` so nothing is lost; outbound, a `Midi1Msg` is rendered back to a Java
`MidiMessage` via `.asJava` — `Midi2Msg` has no `asJava`, since Java Sound speaks MIDI 1.0 only. Conversion happens
exactly once per message, in `MidiDeviceHandle`: outbound in its receiver, inbound in the Java receiver it registers on
the device. Everything upstream — the splitter, the processors, the `tuner` pipeline — carries `MidiMsg`, so no
processor converts on entry or exit.

## Dependencies

**Depends on** `businessync` (the bus used to publish `MidiEvent`s) and `common` (the `Locking` mixin used by the
thread-safe device/transmitter/processor classes), plus the external **CoreMIDI4J** library and the inherited common
logging/test stack.

**Depended on by** `tuner` (builds `MidiProcessor`-based pipelines and uses `MidiManager` for device I/O) and `cli`
(lists connected devices); `app`, `composition`, `format`, and `ui` reach it transitively through `tuner`.

## Notes / subject to change

- Coverage targets are currently below the project-wide 80% goal (TODO #177); device-handling code that needs real MIDI
  hardware is hard to cover with unit tests.
- The `MidiMsg` model is broad (it covers the full set of SMF meta events) even though Microtonalist does not yet
  exercise every one; treat the typed model as the supported surface and `UnsupportedMidiMsg` as the lossless
  escape hatch.
- The `Sc` prefix is gone (#279), the `MidiTransmitter` family replaced `MultiTransmitter` (#280, #281) and the
  pipeline carries `MidiMsg` end to end (#281); #282 turns `MidiManager` / `MidiDeviceHandle` into traits with a
  `JavaMidiManager` / `JavaMidiDeviceHandle` implementation under `javamidi` — see `issues/00278-isolate-java-midi/`.
