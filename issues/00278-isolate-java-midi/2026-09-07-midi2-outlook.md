# MIDI 2.0 Outlook for `sc-midi`

- **Date**: 2026-09-07
- **Issue**: [#283](https://github.com/calinburloiu/microtonalist/issues/283) — "Write a MIDI 2.0 outlook document
  for a future sc-midi implementation", sub-issue of [#278](https://github.com/calinburloiu/microtonalist/issues/278)
- **Base commit**: `5b235e6` — "[#278] Add the design document for isolating the Java Sound implementation"
- **Purpose**: a planning sketch, deliberately shallow. It explains MIDI 2.0 to a newcomer, how it coexists with MIDI
  1.0, and what a MIDI 2.0 implementation of `sc-midi` would take. It is **not** a specification: message layouts and
  numbers quoted here must be verified against the MIDI Association documents before any implementation work
  (Section 6).

## 1. MIDI 2.0 for newcomers

MIDI 1.0 (1983) is a one-way byte stream: 7-bit data values, 16 channels, a Note On that carries only note number and
velocity, and channel-wide controllers such as Pitch Bend that apply to every note on the channel at once. MIDI 2.0
(2020, revised 2023) keeps the musical vocabulary but changes the container, the resolution, and the addressing, and
adds a two-way negotiation layer.

**Universal MIDI Packet (UMP).** The transport unit is no longer a byte stream but a packet of one to four 32-bit
words. The first byte of every packet carries a *Message Type* and a *Group*. Message types include: Utility
(no-op, jitter-reduction timestamps), System Common / Real-Time, **MIDI 1.0 Channel Voice** (32-bit packets carrying
ordinary MIDI 1.0 messages), Data 64 (SysEx split into 7-bit chunks, "SysEx7"), **MIDI 2.0 Channel Voice** (64-bit
packets), Data 128 (SysEx8 and Mixed Data Sets), Flex Data (tempo, time signature, lyrics, chord names) and UMP Stream
(endpoint and Function Block discovery). A MIDI 2.0 device can therefore speak MIDI 1.0 *inside* UMP.

**Resolution.** MIDI 2.0 Channel Voice messages widen everything: 16-bit velocity, 32-bit Control Change, Channel
Pressure and Pitch Bend, 32-bit Polyphonic Pressure. Registered and Assignable Controllers (the successors of RPN and
NRPN) carry their 32-bit value in a single message instead of the four-message Data Entry procedure that
`MidiChannelStateTracker` and `RpnMessages` implement today. Program Change carries its bank in the same message.

**Per-note expression.** This is the change that matters most to Microtonalist. MIDI 2.0 adds **Per-Note Pitch
Bend** (32-bit, addressed to one note), **Registered and Assignable Per-Note Controllers** (256 of each, per note),
and a Note On *attribute* field with defined types, one of which, **Pitch 7.9**, states the note's exact pitch in
semitones with 9 fractional bits (a resolution of about 0.2 cents). Note On can also carry an attribute for
articulation. A microtonal note therefore no longer needs a private channel: MIDI 2.0 does natively, per note, what
MPE achieves in MIDI 1.0 by allocating one channel per note.

**Addressing.** UMP has 16 *Groups*, each with 16 channels, so an endpoint addresses 256 channels. A UMP endpoint is
bidirectional and is subdivided into *Function Blocks* (a keyboard's keys, its control surface, its synth engine),
each spanning one or more groups. This replaces the MIDI 1.0 notion of separate input and output ports that
`MidiManager` models with its two endpoint sets.

**MIDI-CI (Capability Inquiry).** A bidirectional protocol carried in Universal SysEx that lets two devices discover
each other (each gets a 28-bit MUID), negotiate capabilities, enable **Profiles** (agreed sets of behaviour, e.g. a
piano profile), and exchange **Properties** (JSON documents: device name, controller lists, program lists). MIDI-CI
works over MIDI 1.0 transports too, which is how a device can announce MIDI 2.0 ability before any UMP is exchanged.
The original *Protocol Negotiation* part of MIDI-CI was deprecated in the 2023 revision in favour of UMP Stream
messages.

## 2. How MIDI 1.0 and 2.0 coexist

- **MIDI 1.0 inside UMP.** A UMP endpoint can carry plain MIDI 1.0 Channel Voice messages in 32-bit packets. A driver
  that exposes a MIDI 1.0 device over a UMP API (Apple CoreMIDI, Windows MIDI Services, ALSA) does exactly this: the
  application sees UMP, the cable carries bytes.
- **Translation rules.** The specification defines how to translate between MIDI 1.0 and MIDI 2.0 Channel Voice
  messages in both directions: 7-bit values are scaled up to 16 or 32 bits (not simply shifted; the rules preserve
  minimum, centre and maximum), a Note On with velocity 0 becomes a Note Off, RPN/NRPN Data Entry sequences collapse
  into single Registered/Assignable Controller messages, and 14-bit Pitch Bend scales to 32 bits. Downward translation
  loses precision, and per-note messages have **no MIDI 1.0 equivalent**: they are dropped, or an application maps them
  itself (MPE is the obvious target, but the specification does not define that mapping).
- **What a MIDI 1.0-only device sees.** Nothing changes for it. A MIDI 2.0 host talks to it over the MIDI 1.0
  protocol, either as a byte stream on a legacy port or as MIDI 1.0 Channel Voice packets that the driver serialises.
- **Negotiation.** Two MIDI 2.0-capable devices start in MIDI 1.0 protocol and switch to MIDI 2.0 protocol per group
  through UMP Stream configuration (or, on older firmware, MIDI-CI Protocol Negotiation).
- **Operating systems.** Apple CoreMIDI (macOS 11+, iOS 14+) exposes UMP endpoints; Windows MIDI Services (Windows
  11, 2024–2025) is UMP-native; Linux ALSA gained UMP support in kernel 6.5; Android 13+ exposes UMP devices through
  `android.media.midi`. The JVM's `javax.sound.midi` is MIDI 1.0 byte-stream only and has no UMP or MIDI 2.0 support.

## 3. What a MIDI 2.0 `sc-midi` implementation would take

Ordered roughly by dependency, each item a candidate issue.

1. **`ScMidi2Message` hierarchy** (`message` package). The `ScMidi2Message` sealed trait introduced by #279 gets the
   MIDI 2.0 Channel Voice messages: Note On/Off with 16-bit velocity and typed attribute, Poly Pressure, Per-Note
   Pitch Bend, Registered/Assignable Per-Note Controller, Per-Note Management, Control Change, Registered/Assignable
   Controller (absolute and relative), Program Change with bank, Channel Pressure, Pitch Bend. Every case class carries
   a `group` (0–15) besides `channel`. A `ChannelScMidi2Message.mapChannel`/`mapGroup` mirrors the MIDI 1.0 side.
   Validation goes into `MidiRequirements` (16-bit and 32-bit `require…` helpers).

2. **A UMP codec** (a `ump` package or object modelled after `JavaMidiConverters`): `ScMidiMessage` ↔ UMP words
   (`Array[Int]` or an opaque `Ump` value class). It must handle both MIDI 1.0 Channel Voice packets (type 0x2), which
   map onto the existing `ScMidi1Message` case classes, and MIDI 2.0 Channel Voice packets (type 0x4). SysEx maps to
   Data 64 (SysEx7) packet sequences; System messages to type 0x1. Unknown packets become an
   `UnsupportedScMidi2Message` holding the raw words, the lossless escape hatch that `UnsupportedScMidiMessage`
   already provides for bytes. This codec is pure Scala and fully unit-testable without hardware.

3. **A `Midi2Manager` / `Midi2DeviceHandle` implementation** of the `MidiManager` and `MidiDeviceHandle` traits from
   #282. The JVM offers no MIDI 2.0 API, so the options are:
    - **Native binding through the Java FFM API** (final since Java 22; the project builds on Java 23) to CoreMIDI's
      UMP functions on macOS, to Windows MIDI Services, and to ALSA's UMP interface on Linux, each behind the same
      trait. A C/C++ library such as `libremidi` (which already abstracts those three backends and supports UMP) would
      reduce the surface to one binding.
    - **Android**: `android.media.midi` with `TRANSPORT_UNIVERSAL_MIDI_PACKETS` gives UMP directly, which is the same
      trait implemented over a platform API rather than a native binding. This is where the Android implementation
      mentioned in #278 and a MIDI 2.0 implementation converge.
    - The `MidiManager` trait's input/output split does not fit bidirectional UMP endpoints with Function Blocks
      exactly. A first implementation can present each Function Block's input and output directions as the two
      endpoint sets; a later revision of the trait may add groups and blocks as first-class concepts.

4. **Boundary translation.** Following D7 of the #278 design, each device handle converts at its boundary:
    - `JavaMidiDeviceHandle` (MIDI 1.0 only) drops `ScMidi2Message`s with a warning. A later improvement translates
      them downward with the specification's rules and maps per-note messages onto MPE, since that is the project's
      own domain.
    - A `Midi2DeviceHandle` sends `ScMidi1Message`s as MIDI 1.0 Channel Voice packets (no loss) or, when the group is
      negotiated to MIDI 2.0 protocol, translates them upward.
    - `MidiChannelStateTracker` needs a MIDI 2.0 counterpart (or extension) that reads 32-bit controllers directly and
      tracks per-note state.

5. **Tuner implications** (`tuner` module).
    - A **`Midi2Tuner`** that sets each note's pitch with the Note On *Pitch 7.9* attribute or with Per-Note Pitch
      Bend is the most direct microtonal tuner possible: no channel allocation, no bend-range arithmetic, no
      Zone reconfiguration. It makes `MpeTuner` the MIDI 1.0 fallback rather than the polyphonic default.
    - MTS still works unchanged (SysEx7 in Data 64 packets), so `MtsTuner` needs only the codec.
    - `MonophonicPitchBendTuner` and `MpeTuner` remain MIDI 1.0 tuners; with upward translation at the boundary they
      keep working against MIDI 2.0 devices that run in MIDI 1.0 protocol.
    - Pitch Bend Sensitivity for MIDI 2.0 Pitch Bend and Per-Note Pitch Bend is configured through Registered
      Controllers rather than the RPN Data Entry procedure; `PitchBendSensitivityMessages` and `RpnMessages` need
      MIDI 2.0 renderings.

6. **Format and UI.** Track specs need a way to select a MIDI 2.0 device and, eventually, a group; the device list in
   the UI must present UMP endpoints and Function Blocks.

## 4. What this refactoring provides

The #278 refactoring is the prerequisite; the parts a MIDI 2.0 implementation relies on:

- **D3** — `ScMidi1Message` / `ScMidi2Message` under `ScMidiMessage`, so MIDI 2.0 messages flow through the same
  pipeline types and the Java converters reject them at compile time.
- **D4** — the `MidiTransmitter` family and `MidiReceiver` are protocol-agnostic; nothing in the plumbing knows about
  bytes or packets.
- **D7** — conversion happens only inside a device handle, so the codec of each implementation is a local concern.
- **D8** — `MidiManager` and `MidiDeviceHandle` are traits; a MIDI 2.0 implementation is a third package next to
  `javamidi`, chosen at the composition root (D9).

## 5. Out of scope now

- Any MIDI 2.0 code, including the `ScMidi2Message` case classes and the UMP codec.
- MIDI-CI, Profiles, and Property Exchange.
- Jitter-reduction timestamps; the pipeline's `timeStamp: Long` stays as it is.
- Choosing the native backend or binding library.
- Revising the `MidiManager` trait for groups and Function Blocks.

## 6. Sources to verify against

Before implementing, verify every message layout, attribute type, controller number and translation rule in this
document against the current MIDI Association specifications (available at midi.org after free registration):

- *Universal MIDI Packet (UMP) Format and MIDI 2.0 Protocol*, version 1.1 (June 2023).
- *MIDI Capability Inquiry (MIDI-CI)*, version 1.2 (June 2023).
- *Common Rules for MIDI-CI Profiles* and *Common Rules for MIDI-CI Property Exchange*.
- *Default Control Change Mapping* and the *MIDI 1.0 ↔ MIDI 2.0 translation* appendix of the UMP specification.
- Platform APIs: Apple CoreMIDI UMP documentation, Microsoft Windows MIDI Services, ALSA UMP (kernel 6.5+),
  Android `android.media.midi` (API 33+).
