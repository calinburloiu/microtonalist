# `sc-midi` module architecture

## Responsibility

The `sc-midi` module (SBT project `sc-midi`, directory `sc-midi/`, `build.sbt` `lazy val` `scMidiModule`) is a
**Scala-idiomatic MIDI API** with a **Java Sound implementation** kept apart in its `javamidi` package. The standard
Java Sound MIDI API is verbose, mutable, byte-oriented, and awkward on macOS; this module hides it behind traits and
typed messages to give the rest of Microtonalist:

- **Device handling** — enumeration, connection tracking, and reference-counted opening/closing of MIDI devices,
  publishing device lifecycle events on the [Businessync](../businessync/README.md) bus.
- **An immutable, typed message model** — a sealed `MidiMsg` hierarchy of `case class`es with validated, named
  fields, plus bidirectional converters to/from Java's `MidiMessage`.
- **MIDI plumbing** — composable receivers/transmitters and a `MidiProcessor` chain that intercepts and rewrites the
  MIDI stream (the foundation on which `tuner` builds its tuning processors).
- **MIDI domain helpers** — `MidiNote`, `PitchClass`, `PitchBendSensitivity`, CC/RPN/NRPN constants, and a per-channel
  state tracker.

It is low-level infrastructure: it knows nothing about scales, tunings, compositions, or the GUI, and depends only on
`businessync` (the device-event bus) and `common` (the `Locking` helper). Only the `javamidi` package imports
`javax.sound.midi` and CoreMIDI4J (#282): `tuner` and `cli` see the `MidiManager` / `MidiDeviceHandle` traits and the
`MidiMsg` model, and the composition roots (`MicrotonalistApp`, `MicrotonalistToolApp`) pick the implementation by
instantiating `JavaMidiManager`. Inside `javamidi`, messages cross to and from Java Sound in exactly one place,
`JavaMidiDeviceHandle`; everything upstream of it carries `MidiMsg`.

Package: `org.calinburloiu.music.scmidi` is the pure Scala API, with a `message` sub-package holding the message model
and its constants. The `javamidi` sub-package is the Java Sound implementation: `JavaMidiManager`,
`JavaMidiDeviceHandle`, the `JavaMidiEnvironment` seam with its `CoreMidi4JEnvironment` production implementation, and
`JavaMidiConverters`. The two live in the same sbt module; the isolation is enforced by convention and review, not by
the build (#278, D1). macOS support comes from **CoreMIDI4J**, which replaces the default Java Sound MIDI device
provider and prefixes device names with `"CoreMIDI4J - "` (stripped for display by `MidiDeviceId.sanitizedName`).

## Key types

### Device handling

**`MidiManager`** is the trait through which devices are discovered and opened. It is `AutoCloseable` and offers a
per-direction API mirrored for input and output (availability, id/info enumeration as `MidiDeviceInfo`,
`open*`/`close*`, handle lookup), because a platform may expose a physical bidirectional device as two endpoints that
nonetheless share one `MidiDeviceId`. `refresh()` rescans the environment; an implementation also refreshes when the
platform reports a change, emitting the device events described in
[Device lifecycle and events](#device-lifecycle-and-events) as it reconciles state.

**`MidiDeviceHandle`** is the read-only trait for a handle to a single device identified by a `MidiDeviceId`.

- **Ownership.** The manager creates the handle and is the only one to change its state. A consumer requests a device
  with `openInput` / `openOutput` and releases it with `closeInput` / `closeOutput`, both reference-counted; otherwise
  it only inspects the handle and uses it for I/O.
- **Disconnected devices.** A handle can exist for a device that is **not currently connected**:
  `info: Option[MidiDeviceInfo]` is defined only while it is connected, and `isInputDevice` / `isOutputDevice` /
  `endpointType` derive from it. A request made while the device is not connected moves the handle to
  `WaitingToOpen`, and the handle opens once the device gets connected.
- **States.** The `State` enum in the companion captures the Closed/Connected/WaitingToOpen/Open transitions, drawn in
  its ScalaDoc. A failed transition sets to false the property it concerns: a failure of the connection leaves the
  handle not connected, and a failure to open or close the device leaves it not requested to open. `isConnected`,
  `isOpen` (true only in `Open`) and `isOpenRequested` all derive from `state`, so they cannot disagree.
- **Liveness.** A handle is **live** while its manager holds it, which is exactly while its state is not `Closed`. A
  handle that reaches `Closed` is forgotten and stays `Closed`; a later request for the same id returns a new handle.
- **I/O.** Callers **send** to an output via `handle.receiver: MidiReceiver` and **subscribe** to an input via
  `handle.transmitter: ConcurrentMidiTransmitter`. Both survive disconnect/reconnect without re-wiring.

Supporting value types: `MidiDeviceInfo` (`case class(name, vendor, description, version, transmittersLimit,
receiversLimit)` with a derived `id: MidiDeviceId` and `endpointType`), `MidiConnectionLimit` (an `enum` of
`Unlimited` / `Limited(count)` — how many transmitters or receivers a device can open; it prints as `unlimited` or the
count, and its `allowsConnections` is what a device's direction derives from), `MidiDeviceId` (`case class(name,
vendor)`) and `MidiEndpointType` (an `enum` of `None`/`Input`/`Output`/`InputOutput`).

**The Java Sound implementation** (`javamidi`). `JavaMidiManager(businessync, environment = CoreMidi4JEnvironment)`
keeps two internal endpoints, one for inputs and one for outputs. Each is a registry of its **live handles**: one
`JavaMidiDeviceHandle` for every device that is connected, requested to open, or both.

- **Refresh.** Each `refresh()` resolves every `MidiDevice` once and builds its `MidiDeviceInfo` through
  `JavaMidiConverters.asMidiDeviceInfo` (Java Sound's `-1` becomes `Unlimited`). It then reconciles each registry with
  the result: a found device is handed to the handle of its id (created if there is none) with `connect`, and a
  connected handle whose device was not found gets `disconnect` and is forgotten if that leaves it `Closed`.
- **Replugged and swapped devices.** The device **resolved last** for an id wins, and a handle compares device
  instances. CoreMIDI4J keeps one `MidiDevice` per endpoint while the endpoint stays present, creates a new one when
  it reappears, and closes the instance of a vanished endpoint before it reports the change. Another instance under a
  still-present id therefore means the device was replugged or swapped between two refreshes: a `Connected` handle
  swaps it silently. An `Open` handle counts it as a swap only if the instance it holds is no longer open; it then
  closes the old device and opens the new one, reporting *closed* and *opened* but not *disconnected* and
  *connected*.
  - The check exists for the JDK software devices CoreMIDI4J passes through, such as the Gervill `Synthesizer` and
    the `Real Time Sequencer`. Their providers build a new instance on every lookup, so without it every refresh (any
    MIDI plug anywhere) would close and reopen them. While the held instance is still open, the handle keeps it and
    its info, and reports nothing.
- **Locking and publishing.** A manager-wide `ReentrantLock` serialises `refresh()`, the open/close operations,
  `close()` and the registry reads (lock order: manager, then handle). Resolution happens before taking the lock. The
  events an operation collects are published in order only after the lock is released, because Guava delivers them
  synchronously and `TrackManager`'s handler sends MIDI.

`JavaMidiDeviceHandle` is the `@ThreadSafe` handle over a `javax.sound.midi.MidiDevice` for one direction, its
`direction`, which its events carry. The device is reachable only through its
`private[javamidi] device: Option[MidiDevice]`.

- **Commands.** Its five `private[javamidi]` commands (`connect`, `disconnect`, `open`, `close` and `closeAll`) are
  called only by the manager and return the `MidiEvent`s of their transitions instead of publishing them.
- **Transactional transitions.** A failed open closes the device as far as it can and rolls back to `Connected` with
  no reference held. A failed close still moves to `Connected`. A failed disconnect still leaves the handle
  disconnected.
- **Java Sound boundary, outbound.** Its receiver converts each `Midi1Msg` with `asJava` and sends it to the open
  device; a `Midi2Msg` is dropped with a warning, since Java Sound speaks MIDI 1.0 only.
  - It sends through the single Java `Receiver` it obtains from an output device each time it opens it. A Java Sound
    device creates a new receiver on every `getReceiver` call and keeps it until it is closed, so asking per message
    would leak one per message. A device that cannot provide one fails the open with `MidiDeviceFailedToOpenEvent`.
  - The receiver reads that Java `Receiver` from a volatile field defined only while the handle is open, so sending
    takes no lock.
  - A send that hits a receiver Java Sound already closed (a device vanishing before CoreMIDI4J reports it) is
    dropped, with a warning once per open (#302).
- **Java Sound boundary, inbound.** The Java `Receiver` it hands to the device's transmitter converts with `asScala`
  into an internal `MidiSplitter(ConcurrentMidiTransmitter())`.

`JavaMidiEnvironment` is the seam between the manager and the platform — `deviceInfos`, `deviceOf(info)` and
`subscribeToEnvironmentChanged(handler)` — so that the bookkeeping can be unit-tested over a fake environment, as
`JavaMidiManagerTest` does; `CoreMidi4JEnvironment`, in a file of its own, is the production implementation and the
only file that calls the CoreMIDI4J and `MidiSystem` statics, which is why `build.sbt` excludes it from coverage.

**`MidiEvent`** is a sealed `BusinessyncEvent` hierarchy covering everything a `MidiManager` implementation publishes
on the bus.

- `MidiEnvironmentChangedEvent` signals a change to the environment.
- The rest come as success/failure pairs for each lifecycle transition (connected/disconnected/opened/closed), each
  failure event (`…FailedTo…Event`) carrying the cause.
- All carry the `MidiDeviceId`. All but `MidiDeviceFailedToConnectEvent`, which is published before resolution tells
  the direction, also carry an `endpointType`: always `Input` or `Output`, the direction of the handle that made the
  transition.

Note that "connected" means *available to the system*, not *opened by the application*: they are distinct,
separately evented states.

### MIDI message model (`message` sub-package)

**`MidiMsg`** is the sealed base of the immutable message model — the Scala-idiomatic counterpart to Java's mutable,
byte-oriented `MidiMessage`/`ShortMessage`. Directly under it sit **`Midi1Msg`**, the base of every MIDI 1.0 message,
and **`Midi2Msg`**, the base of every MIDI 2.0 message, with no case classes yet (#292). Pipeline signatures take
`MidiMsg`. Its sub-hierarchies cover Channel Voice messages and, under `ChannelModeMidiMsg`, the eight Channel Mode
messages (both are `ChannelMidiMsg`s, with a `mapChannel` that rewrites the channel), system-common and
system-real-time messages, the full set of Standard MIDI File meta events, and System Exclusive (`SysExMidiMsg`).
Anything with no dedicated counterpart becomes `UnsupportedMidiMsg`, a lossless escape hatch that round-trips back to
the right Java type. `PitchBendMidiMsg` is notable: it normalises Java's two raw LSB/MSB bytes into a single signed
14-bit value and offers cents conversion against a `PitchBendSensitivity`. MIDI 1.0 puts the Channel Mode messages on
the wire as Control Changes with numbers 120–127, but defines them as a category of their own, so the model does too:
`AllSoundOffMidiMsg`, `ResetAllControllersMidiMsg`, `LocalControlMidiMsg`, `AllNotesOffMidiMsg`, `OmniModeOffMidiMsg`,
`OmniModeOnMidiMsg`, `MonoModeOnMidiMsg` and `PolyModeOnMidiMsg` each own their number in their companion,
`ChannelModeMidiMsg.NumberRange` spans them, and `CcMidiMsg.number` is restricted to 0–119 by
`MidiRequirements.requireControllerNumber`. Only Local Control and Mono Mode On carry a field; the other six emit data
byte `0` and ignore whatever arrived — the one place the model deliberately drops the byte-level round trip.

**`JavaMidiConverters`**, in the `javamidi` sub-package
(`import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*`), is the boundary with Java Sound MIDI, modelled
after `scala.jdk.CollectionConverters`: importing its members enables `message.asJava` / `javaMessage.asScala`.
`asJava` is defined on `Midi1Msg` only, so converting a future `Midi2Msg` value is a compile-time error; `asScala`
returns `MidiMsg`. Both directions dispatch through lookup tables (by concrete subtype `Class` outbound, by
status/meta-type byte inbound) rather than large pattern matches. A Control Change is the one status byte both tables
split further, by controller number, into a `CcMidiMsg` and a Channel Mode message; a Mono Mode On asking for more
channels than MIDI 1.0 allows decodes to `UnsupportedMidiMsg` rather than throwing on the device's own thread. The same
object also builds the device-level API values from Java Sound: `device.asMidiDeviceInfo`, `info.asMidiDeviceId` and
`connectionLimit(javaMaxConnections)`. Value validation for message constructors is centralized in `MidiRequirements`
(channel and bit-width `require…` checks), and the controller/parameter numbers live in `MidiCc` / `MidiRpn` /
`MidiNrpn` (including the MPE Configuration Message and the MPE Slide CC).

### MIDI plumbing (receivers, transmitters, processors)

These are the composable pieces `tuner` builds its tuning pipeline from:

- **`MidiReceiver`** — the Scala-idiomatic counterpart of `javax.sound.midi.Receiver` that consumes `MidiMsg`
  directly; every stage of the pipeline is one. Unlike its Java counterpart, it carries no `close()`: nothing in the
  module calls one generically across a `MidiReceiver`, so an implementation that ever needs a release hook mixes in
  `AutoCloseable` itself instead of the trait mandating one everywhere.
- **`MidiTransmitter`** — the read-only transmitter of the Scala API: a single `receivers: Seq[MidiReceiver]` member,
  and, likewise, no `close()`. The trait *itself* declares no state, no locking and no implementation, so a consumer
  that only forwards messages does not depend on how, or whether, the sequence can change. That is a statement about
  the trait, not about the values behind it: locking is each implementation's business, and a reference typed as
  `MidiTransmitter` may well hold a `ConcurrentMidiTransmitter` that takes a lock on every read. Three
  implementations, none holding a resource of its own: `ImmutableMidiTransmitter` (a case class whose
  `withReceiver`/`withReceivers`/`withoutReceiver`/`withoutReceivers` return new instances),
  `MutableMidiTransmitter` (`@NotThreadSafe`) and `ConcurrentMidiTransmitter` (`@ThreadSafe`).
  The mutable class makes every modifier and the `receivers` setter `final` and offers a subclass two `protected`
  hooks instead: `setReceivers`, which every change funnels through, and `withChangeGuard`, which wraps each change
  together with the read of the current receivers that computes it. `ConcurrentMidiTransmitter` overrides only those
  two points — `receivers` under the read lock, `withChangeGuard` under the write lock of a `ReentrantReadWriteLock`
  via `Locking`. A subclass overriding `setReceivers` therefore runs inside the write lock whatever the entry point,
  including a direct `receivers = …` assignment, and may read `receivers` re-entrantly (a downgrade, which the lock
  permits) to compare the incoming sequence with the current one. `MidiSplitter`, `MidiProcessor` and
  `MidiDeviceHandle` are built on top of them; `MultiTransmitter`, the Java-typed transmitter the family superseded,
  is gone.
- **`MidiSplitter(transmitter: MidiTransmitter)`** — a `MidiReceiver` that fans every message out to the receivers
  of the transmitter it is given; the caller picks the transmitter implementation, and the splitter only reads it,
  never owning its lifetime. `JavaMidiDeviceHandle` uses one over a `ConcurrentMidiTransmitter` to broadcast a
  device's stream.
- **`MidiProcessor`** — a MIDI interceptor that can filter, modify, or synthesise messages as they pass through.
  Subclasses implement `process(message: MidiMsg, timeStamp): Seq[MidiMsg]`; its `receiver` processes each message
  once and forwards the results to every receiver of its `transmitter`, a `MidiProcessorTransmitter` (a
  `ConcurrentMidiTransmitter`) that calls `onDisconnect(removed)` before and `onConnect(added)` after every change of
  its receiver set — with exactly the receivers the change drops/adds, never for a receiver present on both sides of
  the change, and never with an empty sequence — and then `onReceiversChanged(newReceivers)` with the whole sequence.
  The membership hooks are what a processor overrides to initialise or clean up an individual receiver; the sequence
  hook is what it overrides to keep something else in step with the sequence as a whole, and it is the only one that
  reports a change which merely reorders the receivers or repeats one already connected. All three run inside the
  write lock, so that the reset/initialisation messages the hooks emit cannot interleave with a send that has not yet
  read the receivers (a fan-out already in flight is not held off). **This is the abstraction `tuner` extends** to
  tune the MIDI stream. A processor with no output receivers drops messages without processing them.
- **`MidiSerialProcessor`** — a `MidiProcessor` that chains a mutable, thread-safe sequence of `MidiProcessor`s end
  to end, rewiring the chain automatically on every mutation (`receivers = Seq(next.receiver)` between neighbours,
  its own output receivers on the last one) and forwarding input straight to the output when empty. It mirrors its own
  output receivers onto the last processor through `onReceiversChanged` — the whole sequence, not the delta, so that a
  partial removal or a reordering cannot leave the chain's tail out of step — and the last processor's transmitter
  then runs the membership protocol over the mirrored sequence. That hook takes the serial processor's own lock inside
  the transmitter's, so a chain mutation and an output-receiver change of the same instance must not race from two
  threads (they do not today; #121 removes the concern).
- **`MidiChannelStateTracker`** — an explicitly `@NotThreadSafe` `MidiReceiver` (for a single track thread) that derives
  **per-channel MIDI state** (active notes, CC/RPN/NRPN/pressure/pitch-bend/program values) from the messages sent to
  it, implementing the RPN/NRPN Data Entry protocol and, in a branch of its own over `ChannelModeMidiMsg`, the Channel
  Mode messages (their numbers are never recorded as CC values, and `ccOption` / `cc` reject them). The MIDI Mode
  messages 124–127 set the receive mode
  (`isOmniModeOn`, `isPolyModeOn` / `isMonoModeOn`, and the channel count of Mono mode, `monoModeChannelCount`) and
  Local Control sets `isLocalControlOn`; both start in MIDI 1.0's recommended power-up state — Omni On/Poly, Local
  Control on — and no reset message changes them. When the tracker is
  told its receiver honours them, All Sound Off, All Notes Off and Reset All Controllers act on the tracked state, and
  so do the MIDI Mode messages, which MIDI 1.0 makes act as All Notes Off too; the tracker does not model MIDI 1.0's
  rule that a receiver in Omni mode ignores All Notes Off and Reset All Controllers, since it cannot tell which channel
  is the receiver's Basic Channel. Notes are **reference-counted**: a note struck
  twice without an intervening release needs two Note Offs to go inactive, which is what lets a consumer discharge MIDI
  1.0's one-Note-Off-per-Note-On obligation. Active notes are ordered by their most recent Note On, so a duplicate Note
  On moves a note to the end of `orderedActiveNotes` instead of listing it twice — each active note appears there
  exactly once, whatever its reference count. `MonophonicPitchBendTuner` uses it to track held-note state; `MpeTuner`
  uses it for Master Channel notes, which bypass its allocator, and also reads it per input channel — the RPN selector
  for routing, and the Pitch Bend, Channel Pressure, and CC #74 (MPE Slide) state it seeds a newly allocated note's
  Expression Values from.

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

1. Construct a single `JavaMidiManager(businessync)` at the composition root (`MicrotonalistApp`,
   `MicrotonalistToolApp`) and pass it around as a `MidiManager`; its initialization runs a first `refresh()` and
   subscribes to environment changes so the device list stays current.
2. Enumerate with `inputDeviceIds` / `outputDeviceIds` (or the `…DevicesInfo` variants); `sanitizedName` gives a
   UI-friendly name.
3. Open a device with `openInput`/`openOutput`; each returns a `MidiDeviceHandle`.
4. Use the handle: send `MidiMsg` values via `handle.receiver` (outputs), subscribe `MidiReceiver`s via
   `handle.transmitter.addReceiver` (inputs). The wiring survives disconnect/reconnect cycles: the manager hands the
   replugged device to the same live handle.
5. `closeInput`/`closeOutput` (reference-counted) release a device. The handle is read-only and has no `close()`;
   `Track.close()` releases its devices this way. `MidiManager.close()` releases every reference held through the
   manager, so every device it opened ends up closed, and stops watching the environment.

## Device lifecycle and events

`JavaMidiManager`'s endpoints reconcile the scanned device set against their live handles on every `refresh()`. Each
[`MidiEvent`](#device-handling) reports one transition of one handle, and a failure event replaces its success event:

- *connected* / *disconnected* on `connect` / `disconnect`; `…FailedToDisconnect` replaces *disconnected* when releasing
  the device throws, and the handle ends up disconnected either way. Only the first refresh that sees an id reports it
  connected.
- *opened* on every entry into `Open`, or `…FailedToOpen`. This includes a `WaitingToOpen` handle whose device gets
  connected, and a device swap.
- *closed* on every exit from `Open`, or `…FailedToClose`: releasing the last reference, a disconnection, or a swap. An
  open handle whose device gets unplugged reports *closed*, then *disconnected* (or only `…FailedToDisconnect`).

Other events around a refresh:

- A refresh triggered by the platform is preceded by `MidiEnvironmentChangedEvent`.
- A device that fails to resolve for an unexpected reason is reported by `MidiDeviceFailedToConnectEvent`.
- A device working in both directions is reported once per direction, by two events that differ in their
  `endpointType`.

Delivery and subscribers:

- The events are published after the manager releases its lock, synchronously on the publishing thread. For a
  platform change, that is CoreMIDI4J's notification thread.
- `TrackManager` (in `tuner`) is the first subscriber. It resets the tuner of the tracks whose output device opens and
  releases the output of the tracks whose input device disconnects (see
  [`tuner`](../tuner/README.md#device-changes)).
- It subscribes through Guava's `@Subscribe`, since `Businessync.subscribe` is still a stub (#90).

## Message conversion model

The `MidiMsg` ↔ `MidiMessage` conversion provided by
[`JavaMidiConverters`](#midi-message-model-message-sub-package), in the `javamidi` sub-package, runs in two
directions. Inbound, a device's raw message becomes a typed, validated `MidiMsg` via `.asScala`, with anything
unrecognised falling back to `UnsupportedMidiMsg` so nothing is lost; outbound, a `Midi1Msg` is rendered back to a Java
`MidiMessage` via `.asJava` — `Midi2Msg` has no `asJava`, since Java Sound speaks MIDI 1.0 only. Conversion happens
exactly once per message, in `JavaMidiDeviceHandle`: outbound in its receiver, inbound in the Java receiver it
registers on the device. Everything upstream — the splitter, the processors, the `tuner` pipeline — carries `MidiMsg`,
so no processor converts on entry or exit.

## Dependencies

**Depends on** `businessync` (the bus used to publish `MidiEvent`s) and `common` (the `Locking` mixin used by the
thread-safe device/transmitter/processor classes), plus the external **CoreMIDI4J** library and the inherited common
logging/test stack.

**Depended on by** `tuner` (builds `MidiProcessor`-based pipelines and uses the `MidiManager` it is given for device
I/O), `cli` (lists connected devices) and `app` (instantiates `JavaMidiManager` and injects it into `TunerModule`);
`composition`, `format`, and `ui` reach it transitively through `tuner`.

## Notes / subject to change

- A vanished device is noticed only when CoreMIDI4J reports the change: until then a send to it is dropped. A handle
  whose device failed to open stays `Connected` with no reference held, and so unusable by the track that requested
  it, until the tracks are rebuilt (#302).
- The `MidiMsg` model is broad (it covers the full set of SMF meta events) even though Microtonalist does not yet
  exercise every one; treat the typed model as the supported surface and `UnsupportedMidiMsg` as the lossless
  escape hatch.
- The `Sc` prefix is gone (#279), the `MidiTransmitter` family replaced `MultiTransmitter` (#280, #281), the
  pipeline carries `MidiMsg` end to end (#281), the device layer is a pair of traits with a Java Sound
  implementation under `javamidi` (#282), the MIDI 2.0 outlook that the empty `Midi2Msg` stands in for has been
  written (#283, `issues/00278-isolate-java-midi/2026-09-07-midi2-outlook.md`), and the Channel Mode messages are
  their own types (#285). Every #278 sub-issue nonetheless remains open until the branch stack that implements them
  merges — see `issues/00278-isolate-java-midi/`.
