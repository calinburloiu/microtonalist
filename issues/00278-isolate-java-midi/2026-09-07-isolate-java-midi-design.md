# Isolating the Java Sound Implementation from the `sc-midi` Scala API (Design)

- **Date**: 2026-09-07
- **Issue**: [#278](https://github.com/calinburloiu/microtonalist/issues/278) — "Isolate the Java Sound implementation
  from the sc-midi Scala API" (parent), with sub-issues
  [#279](https://github.com/calinburloiu/microtonalist/issues/279),
  [#280](https://github.com/calinburloiu/microtonalist/issues/280),
  [#281](https://github.com/calinburloiu/microtonalist/issues/281),
  [#282](https://github.com/calinburloiu/microtonalist/issues/282), and
  [#283](https://github.com/calinburloiu/microtonalist/issues/283)
- **Base commit**: `fd8f6d6` — "Start v1.5.0-SNAPSHOT"
- **Modules touched**: `sc-midi` (all of it), `tuner` (pipeline and tuner APIs), `cli`, `app` (composition root)
- **Milestone**: `sc-midi`

## 1. Problem

`sc-midi` presents itself as a Scala-idiomatic MIDI API, but `javax.sound.midi` leaks through most of its public
surface and from there into `tuner` and `cli`:

- `MidiManager` is a concrete class bound to CoreMIDI4J and returns `MidiDevice.Info`; `MidiDeviceHandle` exposes
  `MidiDevice`, `MidiDevice.Info`, and a Java `Receiver`.
- `MidiProcessor`, `MidiSerialProcessor`, `MidiSplitter`, `MultiTransmitter`, and `PitchBendSensitivityMessages` are
  typed on Java `MidiMessage`, `Receiver`, and `Transmitter`.
- In `tuner`, `Tuner`, `TuningChanger`, `TunerProcessor`, `TuningChangeProcessor`, and `Track` are typed on Java
  `MidiMessage`/`Receiver`; every tuner converts with `asScala` on entry and `asJava` on exit of `process`, so each
  message is converted twice per processor instead of once per device.
- `cli` reads `MidiDevice.Info` and queries `MidiSystem` directly.

Two consequences: no second implementation (MIDI 2.0, Android) can be provided without rewriting the consumers, and
`MultiTransmitter` forces a read/write lock on every fan-out even where a single thread will own the pipeline once
[#121](https://github.com/calinburloiu/microtonalist/issues/121) gives each track its own thread.

## 2. Decisions

### D1 — Two packages: a pure API and a Java Sound implementation

`org.calinburloiu.music.scmidi` (with its `message` sub-package) becomes the pure Scala API: no `javax.sound.midi`,
no CoreMIDI4J. A new sub-package `org.calinburloiu.music.scmidi.javamidi` holds everything that touches Java Sound:
`JavaMidiManager`, `JavaMidiDeviceHandle`, `JavaMidiConverters` (moved from `message`), and the `MidiDevice`
capability helpers `isInputDevice`/`isOutputDevice` (moved out of the package object). `MidiRequirements` stays in
`message`.

The implementation stays in the same sbt module. Isolation is enforced by convention and review, not by the build; a
separate `sc-midi-java` module was considered and rejected for now because of the build, coverage-threshold, and
dev-stack churn it would add. The package name is `javamidi` rather than `java` because a nested `java` package would
shadow the JDK's `java` package for relative imports inside `scmidi`.

### D2 — Drop the `Sc` prefix everywhere; message types take the `MidiMsg` suffix

Renamed, with no behavior change: `ScMidiReceiver` → `MidiReceiver`, `ScMidiCc`/`ScMidiRpn`/`ScMidiNrpn` →
`MidiCc`/`MidiRpn`/`MidiNrpn`, `ScMidiChannelStateTracker` → `MidiChannelStateTracker`, `ScMidiKeySignatureMode` →
`MidiKeySignatureMode`. `MultiTransmitter` is replaced (D4), so `multiTransmitter` accessors become `transmitter`.

The message model drops `Sc` too, but a plain `MidiMessage` would collide with `javax.sound.midi.MidiMessage` inside
the Java implementation, so the message types use the suffix **`MidiMsg`** instead: `ScMidiMessage` → `MidiMsg`,
`NoteOffScMidiMessage` → `NoteOffMidiMsg`, `CcScMidiMessage` → `CcMidiMsg`, `SysExScMidiMessage` → `SysExMidiMsg`,
`UnsupportedScMidiMessage` → `UnsupportedMidiMsg`, `TextMetaScMidiMessage` → `TextMetaMidiMsg`, and so on — one
uniform substitution of `ScMidiMessage` by `MidiMsg` across `sc-midi`, `tuner`, `format`, and their tests. Inside
`javamidi`, `MidiMsg` (ours) and `MidiMessage` (Java's) then read as two distinct names without import aliases.

Naming rule, to be recorded in `docs/development/coding-conventions.md` by #279: **`Msg` is the suffix of the message
*types* only**; helpers and prose keep the full word (`RpnMessages`, `PitchBendSensitivityMessages`,
`MtsMessageGenerator`, `MidiRequirements`).

`mapShortMessageChannel` in the package object is deleted; `ChannelMidiMsg.mapChannel` already covers it.

### D3 — Message hierarchy prepared for MIDI 2.0

```scala
sealed trait MidiMsg
sealed trait Midi1Msg extends MidiMsg
sealed trait Midi2Msg extends MidiMsg   // no members yet
```

Every existing subtype — channel voice/mode, system common, system real-time, SysEx, all SMF meta events, and
`UnsupportedMidiMsg` — moves under `Midi1Msg`. SMF meta events belong to the MIDI 1.0 specification
family, so they sit there rather than at the top level. The `asJava` extension in `JavaMidiConverters` is defined on
`Midi1Msg` only, so converting a future MIDI 2.0 message to a Java Sound message is a compile-time error.

Pipeline signatures (`MidiReceiver.send`, `MidiProcessor.process`, `Tuner.process`, `TuningChanger.decide`) take the
top-level `MidiMsg` for forward compatibility. A full MIDI 2.0 hierarchy is out of scope; see
[#283](https://github.com/calinburloiu/microtonalist/issues/283).

### D4 — `MidiTransmitter`: a read-only interface with three implementations

```scala
trait MidiTransmitter {
  def receivers: Seq[MidiReceiver]
}
```

No locks, no `AutoCloseable` (every current `close()` on a transmitter is empty). The name drops `Multi`; multiple
receivers are implicit. Implementations:

| Type                                        | Annotation       | Modifiers                                                                                   |
|---------------------------------------------|------------------|---------------------------------------------------------------------------------------------|
| `ImmutableMidiTransmitter(receivers)`       | case class       | `withReceiver`, `withReceivers`, `withoutReceiver`, `withoutReceivers` — return new instances |
| `MutableMidiTransmitter`                    | `@NotThreadSafe` | `receivers_=`, `addReceiver`, `addReceivers`, `removeReceiver`, `clearReceivers`; every modifier funnels through `receivers_=` |
| `ConcurrentMidiTransmitter`                 | `@ThreadSafe`    | extends `MutableMidiTransmitter`; overrides every method under a `ReentrantReadWriteLock` via `Locking` |

`ConcurrentMidiTransmitter` extends `MutableMidiTransmitter` so that a caller which only needs "something it can add
a receiver to" (e.g. `Track`, `TrackManager`) has one static type. The mutable class must not call other overridable
public methods from inside a modifier, so that the concurrent override does not re-enter the lock through `super`.

### D5 — `MidiSplitter` is a receiver over any transmitter

```scala
class MidiSplitter(val transmitter: MidiTransmitter) extends MidiReceiver
```

`send` fans out to `transmitter.receivers`. The caller chooses the transmitter implementation; `JavaMidiDeviceHandle`
builds `MidiSplitter(ConcurrentMidiTransmitter())`. The separate `receiver` field disappears: the splitter *is* the
receiver.

### D6 — `MidiProcessor` output is a multi-receiver transmitter with connect/disconnect hooks

`MidiProcessor` keeps its `receiver: MidiReceiver` (closed flag; processes, then forwards to every output receiver).
Its output is `transmitter: MidiProcessorTransmitter`, which extends `ConcurrentMidiTransmitter` and overrides
`receivers_=` with the current connect/disconnect protocol, generalised to a set:

1. If the new sequence equals the current one, do nothing.
2. If the current set is non-empty, call `onDisconnect()`.
3. Swap the sequence.
4. If the new set is non-empty, call `onConnect()`.

The override runs inside the write lock that `ConcurrentMidiTransmitter`'s modifiers already hold (every modifier of
the mutable base funnels through `receivers_=`, so `addReceiver`/`removeReceiver`/`clearReceivers` reach this override
by virtual dispatch). This differs from today's `MidiProcessorTransmitter`, which calls the hooks outside its lock; it
is deliberate: a message arriving on another thread cannot interleave with the reset/initialisation messages the hooks
emit, and the hooks only send downstream (read-locking this transmitter re-entrantly), so no lock-ordering issue
arises.

`process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg]`.

Consequences:

- `MidiSerialProcessor` wires neighbours with `receivers = Seq(next.receiver)` and unwires with `clearReceivers()`.
  Its constructor takes `initialOutputReceivers: Seq[MidiReceiver]` instead of an `Option`.
- `Track` no longer needs its output `MidiSplitter`: the pipeline's own transmitter fans out to the device receiver and
  to other tracks. `Track.transmitter` is the pipeline's transmitter.
- Adding a second output receiver to a connected processor re-fires `onDisconnect()`/`onConnect()`, so
  `TunerProcessor` re-sends its reset messages to the receivers that were already connected. This is harmless and
  matches the meaning of "the output configuration changed".
- Observation from reading `Track.scala` at the base commit (not verified at runtime): the pipeline is built with the
  splitter as its output *before* the device receiver is added to the splitter, so `TunerProcessor.onConnect()` sends
  the tuner's `reset()` messages (e.g. Pitch Bend Sensitivity) to an empty splitter. With D6, `onConnect()` fires when
  the device receiver is added, so the messages reach the device. The implementation plan should add a test that pins
  this.

The transmitter is concurrent because tracks do not yet own a thread. When
[#121](https://github.com/calinburloiu/microtonalist/issues/121) lands, `MidiProcessor` can accept the transmitter
implementation as a parameter and default to a non-concurrent one on the track thread.

### D7 — Conversion happens once, at the device boundary

`JavaMidiDeviceHandle` is the only place where messages cross between the Scala model and Java Sound:

- Outbound: its `receiver: MidiReceiver` converts with `asJava` and sends to the open device's Java receiver. A
  `Midi2Msg` is dropped with a warning, because a Java Sound device speaks MIDI 1.0 only.
- Inbound: the device's Java `Transmitter` gets a Java `Receiver` that converts with `asScala` and hands the message
  to the handle's `MidiSplitter(ConcurrentMidiTransmitter())`.

Everything upstream of the handle (`MidiProcessor`, `MidiSerialProcessor`, the tuners) works on `MidiMsg`
directly, so `MpeTuner`, `MonophonicPitchBendTuner`, `MtsTuner`, and `PedalTuningChanger` lose all their `asScala`/
`asJava` calls. `PitchBendSensitivityMessages.create` returns `Seq[MidiMsg]`. `MtsMessageGenerator` uses two new
constants on `SysExMidiMsg` — `StatusByte` (`0xF0`) and `EndOfExclusiveByte` (`0xF7`) — instead of the Java
ones.

### D8 — The device layer becomes traits with a Java implementation

API types in `scmidi`:

- `case class MidiDeviceInfo(name: String, vendor: String, description: String, version: String)` with a derived
  `id: MidiDeviceId`. It replaces `MidiDevice.Info` in every public signature. `MidiDeviceId.correspondsToInfo` takes
  it; the `MidiDeviceId.apply(MidiDevice.Info)` factory moves to the Java side.
- `trait MidiManager extends AutoCloseable` with the current per-direction surface, unchanged in shape: `refresh()`,
  and for each of input/output: `is…Available`, `…DeviceInfoOf`, `…DeviceIds`, `…DevicesInfo`, `open…`,
  `openFirstAvailable…`, `…DeviceHandleOf`, `…OpenedDevices`, `close…`.
- `trait MidiDeviceHandle extends AutoCloseable`: `id`, `info: Option[MidiDeviceInfo]`, `isInputDevice`,
  `isOutputDevice`, `endpointType`, `state`, `isConnected`, `isOpen`, `open()`, `close()`, `receiver: MidiReceiver`,
  `transmitter: ConcurrentMidiTransmitter`. The `State` enum and its transition diagram stay in the companion.
  `device: Option[MidiDevice]` leaves the API.

Java implementation in `scmidi.javamidi`:

- `JavaMidiManager(businessync)`: today's `MidiManager` body, including the private endpoint bookkeeping (device
  diffing, reference counting, `MidiEvent` publishing) and the CoreMIDI4J notification listener. It builds
  `MidiDeviceInfo` from `MidiDevice.Info`. The endpoint bookkeeping is not lifted into a reusable base class yet; a
  second implementation is the moment to do that.
- `JavaMidiDeviceHandle`: today's handle, with `device: Option[MidiDevice]` as a public member of the concrete class
  only, and the boundary conversion of D7.
- `JavaMidiConverters`: moved unchanged, plus a `MidiDeviceInfo` builder and the two `MidiDevice` capability helpers.

### D9 — Consumers pick the implementation at the composition root

- `TunerModule` receives a `MidiManager` through its constructor. `MicrotonalistApp` instantiates `JavaMidiManager`.
  `tuner` therefore imports nothing from `javamidi`.
- `cli` instantiates `JavaMidiManager`, prints the `MidiDeviceInfo` fields, and stops printing the max
  transmitter/receiver counts, a Java Sound detail with no counterpart in the API.
- `Tuner.reset()`, `tune()`, `process()` and `TuningChanger.decide()` are typed on `MidiMsg`; `TunerProcessor`
  and `TuningChangeProcessor` follow. `Track` exposes `receiver: MidiReceiver` and `transmitter`; `TrackManager` calls
  `transmitter.addReceiver`.

After D8 and D9, `grep -r 'javax.sound.midi'` over `src/main` matches only files under `scmidi/javamidi/`.

### D10 — Channel Mode messages are their own types, not Control Changes

MIDI 1.0 sends its eight Channel Mode messages with the Control Change status byte (`0xBn`) and controller numbers
120–127, and the model currently follows the wire: they are `CcScMidiMessage`s, and every consumer that cares —
`MidiChannelStateTracker.handleChannelModeCc`, `MpeMessageRouting.routeCc` and `deselectsOnRelay` — tells them apart
by matching on `number`. The specification, however, defines them as a separate message category with their own
semantics (they are not controllers, and a receiver must not treat them as such), so the model separates them:

```scala
sealed abstract class ChannelModeMidiMsg(channel: Int) extends ChannelMidiMsg(channel)

case class AllSoundOffMidiMsg(channel: Int)                        extends ChannelModeMidiMsg(channel)   // 120
case class ResetAllControllersMidiMsg(channel: Int)                extends ChannelModeMidiMsg(channel)   // 121
case class LocalControlMidiMsg(channel: Int, isOn: Boolean)        extends ChannelModeMidiMsg(channel)   // 122
case class AllNotesOffMidiMsg(channel: Int)                        extends ChannelModeMidiMsg(channel)   // 123
case class OmniModeOffMidiMsg(channel: Int)                        extends ChannelModeMidiMsg(channel)   // 124
case class OmniModeOnMidiMsg(channel: Int)                         extends ChannelModeMidiMsg(channel)   // 125
case class MonoModeOnMidiMsg(channel: Int, channelCount: Int)      extends ChannelModeMidiMsg(channel)   // 126
case class PolyModeOnMidiMsg(channel: Int)                         extends ChannelModeMidiMsg(channel)   // 127
```

- Only the two messages whose data byte carries meaning get a field: Local Control's `isOn` (`0` off, `127` on) and
  Mono Mode On's `channelCount` (0–16, `0` meaning "as many as the receiver has voices"). The others carry no value:
  their data byte is `0` on the wire, so `asJava` emits `0` and `asScala` ignores whatever arrived. This is the one
  deliberate loss of the byte-level round trip; the messages' meaning is preserved, and `UnsupportedMidiMsg` is not
  involved because the message *is* supported.
- The controller numbers move out of `MidiCc` into each case class's companion (`AllSoundOffMidiMsg.Number = 120`, and
  so on), with `ChannelModeMidiMsg.NumberRange = 120 to 127` for the converters.
- **`CcMidiMsg.number` is restricted to 0–119.** A new `MidiRequirements.requireControllerNumber` enforces it, so a
  `CcMidiMsg(ch, 123, 0)` throws at construction and a Channel Mode message can only exist as its own type.
- `JavaMidiConverters`: inbound, a `CONTROL_CHANGE` with `data1` in 120–127 dispatches to the Channel Mode case
  class; outbound, each case class gets its own `ToJavaMap` entry rendering `0xBn`, its number, and its data byte.
- Consumers switch from matching on numbers to matching on types:
    * `MidiChannelStateTracker` handles `ChannelModeMidiMsg` in its own branch and no longer records 120–127 in
      `ccValues`, so `tracker.cc(channel, 123)` is `None` afterwards.
    * `MpeMessageRouting.route` gains a `routeChannelMode` branch that discards the four Mode messages (Omni Off/On,
      Mono On, Poly On) and relays All Sound Off, Reset All Controllers, Local Control and All Notes Off per role,
      exactly as `routeCc` does today for those numbers; `deselectsOnRelay` matches `ResetAllControllersMidiMsg`.
    * `MpeTuner` reaches them through the generic `ChannelMidiMsg` path (`route`, then `ForwardOn`/`Discard` and
      `deselectsOnRelay`), so its own dispatch does not change, but its `MpeTunerTest` and `MpeMessageRoutingTest`
      cases that build `CcScMidiMessage(_, 120..127, _)` move to the new types, and the routing table in the MPE
      Tuner paper and the `tuner` architecture doc say "Channel Mode messages" where they say "CC 120–127".
- `mapChannel` is implemented on every case class as today.

## 3. Sub-issues and merge order

Each sub-issue gets its own branch, PR, and design/plan documents under `issues/00278-isolate-java-midi/`.

| # | Issue                                                          | Scope                                                                                                                                                       | Depends on |
|---|----------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| 1 | [#279](https://github.com/calinburloiu/microtonalist/issues/279) | D2 renames, D3 hierarchy, move `JavaMidiConverters` and the `MidiDevice` helpers into `javamidi`. Mechanical; suite stays green.                          | —          |
| 2 | [#280](https://github.com/calinburloiu/microtonalist/issues/280) | D4: the four transmitter types and their tests. `MultiTransmitter` untouched.                                                                              | —          |
| 3 | [#281](https://github.com/calinburloiu/microtonalist/issues/281) | D5, D6, D7: Scala-typed pipeline, boundary conversion in `MidiDeviceHandle`, `MultiTransmitter` deleted, `tuner` adapted. `tuner` free of `javax.sound.midi`. | 1, 2       |
| 4 | [#282](https://github.com/calinburloiu/microtonalist/issues/282) | D8, D9: `MidiDeviceInfo`, the two traits, `JavaMidiManager`/`JavaMidiDeviceHandle`, `TunerModule` injection, `cli`/`app`. Only `javamidi` imports Java Sound. | 3          |
| 5 | [#283](https://github.com/calinburloiu/microtonalist/issues/283) | The MIDI 2.0 outlook document (Section 6).                                                                                                                 | —          |
| 6 | [#285](https://github.com/calinburloiu/microtonalist/issues/285) | D10: `ChannelModeMidiMsg` and its eight case classes, `CcMidiMsg` restricted to 0–119, converters, tracker and MPE routing adapted.                       | 1          |

Sub-issue 6 is independent of 2–4 and can merge at any point after 1; merging it before 3 keeps the `tuner` adaptation
in 3 from touching the Channel Mode branches twice.

## 4. Testing

Strict TDD per sub-issue (red/green/refactor).

New unit tests:

- `ImmutableMidiTransmitter`, `MutableMidiTransmitter`, `ConcurrentMidiTransmitter` (the last with a concurrency test
  that mutates and reads from several threads).
- `MidiSplitter` over each transmitter implementation.
- `MidiProcessorTransmitter`: `onDisconnect`/`onConnect` on every kind of set change (empty → non-empty, non-empty →
  different non-empty, non-empty → empty, same set → no callbacks).
- A `Track`-level test pinning that a tuner's `reset()` messages reach a receiver added after construction (D6).
- `MidiDeviceInfo` and the updated `MidiDeviceId`.
- The eight `ChannelModeMidiMsg` case classes: construction and validation (`channelCount` 0–16), `mapChannel`, the
  Java round trip through `JavaMidiConvertersTest` (including a non-zero data byte on a valueless message decoding
  to the same case class), and `CcMidiMsg` rejecting numbers 120–127. The tracker and MPE routing tests that today
  send `CcScMidiMessage(_, 120..127, _)` move to the new types and keep their assertions.

Migrated tests: every `sc-midi` and `tuner` test that stubs a Java `Receiver` or builds messages with `asJava` moves
to `MidiReceiver` and plain `MidiMsg` values. `JavaMidiConvertersTest` moves with the converters and remains the
Java-boundary test.

`JavaMidiManager` and `JavaMidiDeviceHandle` remain hardware-bound and uncovered, as the current classes are
([#177](https://github.com/calinburloiu/microtonalist/issues/177)). Coverage policy: the `sc-midi` statement floor of
67% and branch floor of 52% must not drop; new files target 80%. The `tuner` floors are 80%/80%.

## 5. Documentation

- `docs/architecture/sc-midi/README.md`: rewritten around the API/implementation split (packages, the transmitter
  family, the boundary conversion, the device traits).
- `docs/architecture/tuner/README.md`: the `Track` pipeline no longer has an output splitter; `Tuner`/`TuningChanger`
  are typed on `MidiMsg`.
- `docs/architecture/module-overview.md` and `data-flow.md`: check the "only `sc-midi` touches `javax.sound.midi`"
  statements and narrow them to the `javamidi` package.
- ScalaDocs on every new public type and member.

## 6. MIDI 2.0 outlook document ([#283](https://github.com/calinburloiu/microtonalist/issues/283))

A concise planning document, `issues/00278-isolate-java-midi/<date>-midi2-outlook.md`, with four parts:

1. **MIDI 2.0 for newcomers**: Universal MIDI Packets (UMP) as the transport; 32-bit controllers and 16-bit velocity;
   per-note controllers and per-note pitch bend; 16 groups of 16 channels; MIDI-CI for discovery and protocol
   negotiation, with profiles and property exchange in one paragraph.
2. **How MIDI 1.0 and 2.0 coexist**: MIDI 1.0 messages carried in UMP form, the specified 1.0↔2.0 translation rules,
   backward compatibility through MIDI-CI negotiation, and what a 1.0-only device sees.
3. **What an `sc-midi` implementation would take**: a `Midi2Msg` hierarchy for the MIDI 2.0 channel voice
   messages; a UMP codec in place of the Java byte converters; a manager/handle implementation over a native library
   or a platform API, since the JVM has no MIDI 2.0 API; 1.0↔2.0 translation at the device boundary; and the tuner
   implications, notably high-resolution per-note pitch bend as an alternative to MPE.
4. **Scope**: what is explicitly out of scope now, and which parts of this refactoring (D3, D4, D7, D8) it relies on.

## 7. Out of scope

- A full `Midi2Msg` hierarchy or any MIDI 2.0 code.
- A separate sbt module for the Java implementation.
- Per-track threads ([#121](https://github.com/calinburloiu/microtonalist/issues/121)) and the switch to
  non-concurrent transmitters inside processors that they enable.
- Lifting `JavaMidiManager`'s endpoint bookkeeping into a reusable base for other implementations.
- Raising the `sc-midi` coverage floors ([#177](https://github.com/calinburloiu/microtonalist/issues/177)).
