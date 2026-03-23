# Plan: Add MpeTuner

## Overview

Implement `MpeTuner`, a `Tuner` that uses MIDI Polyphonic Expression (MPE) to apply microtonal tunings to polyphonic
MIDI streams. The design follows the [MPE Tuner paper](../../docs/tuner/mpe-tuner-paper.md), which details dual-group
channel partitioning, the pitch-class invariant, note dropping, and non-MPE to MPE conversion. The paper references the
[MPE specification](../../docs/tuner/mpe-spec.md) for details.

Update `JsonTunerPluginFormat` to support serialization/deserialization of `MpeTuner`.

All code is developed with strict TDD: tests are written first, verified to fail, then implementation is added.

---

## 1. Add New MIDI Message Types to `ScMidiMessage.scala`

MPE requires Channel Pressure and Polyphonic Key Pressure messages. Add:

- `ScChannelPressureMidiMessage(channel: Int, value: Int)` with companion `unapply` extractor.
- `ScPolyPressureMidiMessage(channel: Int, midiNote: MidiNote, value: Int)` with companion `unapply` extractor.

### Test Cases (`ScMidiMessageTest` or similar)

1. **`ScChannelPressureMidiMessage` creates correct Java MIDI message** — verify command, channel, data.
2. **`ScChannelPressureMidiMessage.unapply` extracts from a valid Channel Pressure message**.
3. **`ScChannelPressureMidiMessage.unapply` returns None for non-Channel-Pressure messages**.
4. **`ScPolyPressureMidiMessage` creates correct Java MIDI message**.
5. **`ScPolyPressureMidiMessage.unapply` extracts from a valid Poly Pressure message**.
6. **`ScPolyPressureMidiMessage.unapply` returns None for non-Poly-Pressure messages**.
7. **Validation rejects invalid channel/value for both new message types**.

---

## 2. Implement MPE Zone Model

Create `MpeZone` in the `tuner` module (e.g. `MpeZone.scala`).

`MpeZone` takes as input:

- Whether it is a **Lower** or **Upper** zone.
- **Member count** (`0` to `15`). A member count of `0` means the zone is disabled, as per the MPE Specification.
- **Master Pitch Bend Sensitivity** (`PitchBendSensitivity`). Default: 2 semitones.
- **Member Pitch Bend Sensitivity** (`PitchBendSensitivity`). Default: 48 semitones.

All other properties are computed from these inputs:

- `masterChannel`: `0` for Lower, `15` for Upper.
- `memberChannels`: range of member channels based on zone type and member count.
- `isEnabled`: `true` if member count > 0.
- `pitchClassGroupSize` and `expressionGroupSize`: computed per the paper's table (Section 4.3 / Appendix A).

No separate `MpeZoneConfig` type is needed. Two `MpeZone` instances may overlap in their channel ranges; when both
zones are used together, the last-defined zone takes precedence on overlapping channels, as described in the MPE
Specification.

### Test Cases (`MpeZoneTest`)

1. **Lower Zone with 15 member channels** — master=0, members=1..15, pitchClassGroup=12, expressionGroup=3.
2. **Lower Zone with 7 member channels** — master=0, members=1..7, pitchClassGroup=5, expressionGroup=2.
3. **Upper Zone with 7 member channels** — master=15, members=8..14, pitchClassGroup=5, expressionGroup=2.
4. **Lower Zone with 3 member channels** — pitchClassGroup=1, expressionGroup=2.
5. **Lower Zone with 2 member channels** — pitchClassGroup=1, expressionGroup=1.
6. **Lower Zone with 1 member channel** — pitchClassGroup=1, expressionGroup=0.
7. **Lower Zone with 0 member channels** — zone is disabled (`isEnabled` is `false`), no member channels.
8. **Upper Zone with 0 member channels** — zone is disabled.
9. **Two zones: Lower 7 + Upper 7** — channels don't overlap.
10. **Group sizes match Appendix A table for all n from 1 to 15**.

---

## 3. Implement Channel Allocator

Create `MpeChannelAllocator` (internal to tuner module) implementing the allocation algorithm from Section 4.4 and
Appendix B.

### Interface

`MpeChannelAllocator` manages channel allocation for a single `MpeZone`. It is used by `MpeTuner` to determine which
member channel a note should be assigned to. Key operations:

- `allocate(midiNote, expressivePitchBend?, preferredChannel?): AllocationResult` — allocates a member
  channel for a new note. The pitch class is computed internally from `midiNote`, so it does not need to be passed
  separately. Returns the allocated channel and any notes that must be dropped (with Note Off messages).
  The optional `preferredChannel` is used in MPE input mode to attempt preserving the input channel assignment.
- `release(midiNote, channel)` — signals that a note has ended on a channel (Note Off received).
- `updateExpressivePitchBend(channel, pitchBend): Seq[DroppedNote]` — updates the expressive pitch bend for a channel;
  if the bend exceeds the `ExpressionPitchBendThreshold` (50 cents) and the channel is shared, other notes on that
  channel are dropped.
- `reset()` — clears all allocation state.

`AllocationResult` contains the allocated channel and a list of notes that were dropped (if any) to make room.

### State Inspection Accessors

The following accessors are exposed for inspecting internal state, primarily useful in tests:

- `activeNotes(channel): Seq[ActiveNote]` — returns the active notes on a given member channel. Each `ActiveNote`
  includes the MIDI note number and expression parameters (expressive pitch bend, channel pressure, slide / CC #74).
- `channelPitchClass(channel): Option[PitchClass]` — returns the pitch class assigned to a channel, if any.
- `activeChannelCount: Int` — returns the number of currently occupied member channels.
- `isChannelOccupied(channel): Boolean` — whether a channel has any active notes.
- `isInPitchClassGroup(channel): Boolean` — whether the given channel belongs to the Pitch Class Group.
- `isInExpressionGroup(channel): Boolean` — whether the given channel belongs to the Expression Group.

### Test Cases (`MpeChannelAllocatorTest`)

#### 3.1 Basic Allocation (Pitch Class Group)

1. **First note allocates to an unoccupied Pitch Class Group channel**.
2. **Notes with distinct pitch classes each get their own Pitch Class Group channel**.
3. **Filling all 12 Pitch Class Group channels with distinct pitch classes works correctly** (zone with 15 members).
4. **Filling all Pitch Class Group channels with distinct pitch classes works correctly** (zone with fewer members,
   e.g. 7 members → 5 Pitch Class Group channels).

#### 3.2 Expression Group Allocation

5. **Second note with same pitch class as existing Pitch Class Group note goes to Expression Group**.
6. **Third note with same pitch class when Expression Group has only one member goes to occupied channel with same
   pitch class** (i.e. sharing occurs because Expression Group is full).
7. **Third note with same pitch class when Expression Group has more than one member goes to another unoccupied
   Expression Group channel** (no sharing yet).
8. **Note with new pitch class when Pitch Class Group is full goes to Expression Group**.

#### 3.3 Channel Sharing

9. **When both groups are full, new note with existing pitch class shares channel with same pitch class (lowest note
   count, then oldest)**.
10. **Tie-breaking: prefer channel with lowest active note count**.
11. **Tie-breaking: among equal note counts, prefer channel with oldest last Note Off**.
12. **When Expression Group is full and Pitch Class Group is not, new note with existing pitch class in the latter
    group but not the former shares channel in Pitch Class Group (lowest note count, then oldest)**.
13. **When both groups are full, new note with existing pitch class in Expression Group but not in Pitch Class Group
    shares channel in Expression Group (lowest note count, then oldest)**. Note: this applies when Pitch Class Group
    has fewer than 12 Member Channels and all its occupied channels have pitch classes different from the new note's
    pitch class.

#### 3.4 Note Dropping — Channel Exhaustion (Section 5.1)

14. **When all channels occupied and new pitch class needs a channel, a channel is freed** — only applies when the zone
    has fewer than 15 Member Channels and the new note's pitch class does not appear on any existing Member Channel.
15. **Freed channel excludes highest-pitched and lowest-pitched note channels**.
16. **Among remaining candidates, the channel with the oldest last onset is freed**.
17. **Freed channel's notes receive Note Off before new note is assigned**.

#### 3.5 Note Dropping — High Expressive Pitch Bend (Section 5.2)

18. **When a note on a shared channel develops high expressive pitch bend (>50 cents), other notes on that channel are
    dropped**.
19. **When a note on a shared channel has expressive pitch bend ≤50 cents, no notes are dropped** (below-threshold
    variant of test 18).
20. **New note with high expressive pitch bend assigned to occupied channel: existing notes are dropped (channel
    freed)**.
21. **New note with expressive pitch bend ≤50 cents assigned to occupied channel: no notes are dropped**
    (below-threshold variant of test 20).
22. **New note assigned to channel with existing high-bend note: channel is freed first**.
23. **New note assigned to channel with existing note whose bend is ≤50 cents: channel is not freed**
    (below-threshold variant of test 22).
24. **A note with high expressive pitch bend is always sole note on its channel**.

#### 3.6 Channel Release

25. **Note Off makes channel available for reuse when all notes on it have ended**.
26. **Channel with multiple notes remains occupied until all notes receive Note Off**.

#### 3.7 MPE Input — Preserving Input Channel (Section 4.5)

27. **MPE input: input channel assignment is preserved when it doesn't violate constraints**.
28. **MPE input: input channel is overridden when it would violate pitch-class invariant**.

---

## 4. Implement `MpeTuner`

Create `MpeTuner` as a regular class (not a case class) extending `Tuner` in the `tuner` module. Since the project now
uses Scala 3, case classes are not needed for convenient instantiation.

### 4.1 Constructor Parameters

- `zones`: A pair of `MpeZone` instances (lower and upper). Default: Lower Zone with 15 member channels, Upper Zone
  disabled (0 member channels). Each `MpeZone` includes its own `masterPitchBendSensitivity` and
  `memberPitchBendSensitivity`.
- `inputMode`: Non-MPE or MPE. Default: Non-MPE.

The high expression pitch bend threshold is a constant `ExpressionPitchBendThreshold` = 50 cents (not configurable).

#### MPE Input Mode Channel Mapping

In MPE input mode, notes arrive on specific input member channels with their own expression parameters (pitch bend,
channel pressure, CC #74). The `MpeTuner` maintains a mapping from input channel to output channel for each active
note. When a Note On arrives on an input member channel, the allocator attempts to preserve that channel assignment
(via the `preferredChannel` parameter). If the channel must change (e.g. pitch-class invariant violation), the mapping
tracks the reassignment so that subsequent expression messages on the input channel are correctly routed to the output
channel. On Note Off, the mapping entry is removed.

### 4.2 `MpeTuner` Test Cases (`MpeTunerTest`)

#### 4.2.1 `reset()` — Initialization (Section 6.1, 6.2)

1. **`reset()` outputs MPE Configuration Message (MCM) for the configured zone(s)**.
2. **`reset()` outputs RPN 0 (Pitch Bend Sensitivity) on all member channels with configured sensitivity**.
3. **`reset()` outputs RPN 0 on master channel(s) with configured master pitch bend sensitivity**.
4. **`reset()` clears internal state (no lingering notes or pitch bends from before reset)**.

#### 4.2.2 `tune()` — Tuning Application (Section 7)

5. **`tune()` with no active notes stores tuning but outputs no messages**.
6. **`tune()` with active notes outputs updated Pitch Bend on each occupied member channel**.
7. **`tune()` recomputes pitch bend = tuning offset + current expressive pitch bend for each channel**.
8. **Tuning change correctly retunes notes of different pitch classes on different channels**.

#### 4.2.3 `process()` — Non-MPE Input, Basic Note Handling

9. **Single Note On: outputs Pitch Bend (tuning offset), CC #74 (64), Channel Pressure (0), then Note On on allocated
   member channel**.
10. **Note Off: outputs Note Off on the correct member channel**.
11. **Multiple notes with distinct pitch classes are allocated to separate member channels**.
12. **Note On velocity and Note Off velocity are preserved**.
13. **Notes from any input channel are correctly allocated to member channels**.

#### 4.2.4 `process()` — Non-MPE Input, Pitch Bend Handling

14. **Input Pitch Bend (expressive) is redirected to Master Channel as Zone-level Pitch Bend**.
15. **Expressive pitch bend from input does NOT affect member channel tuning pitch bend**.

#### 4.2.5 `process()` — Non-MPE to MPE Conversion (Section 3.3)

16. **Polyphonic Key Pressure in input is converted to Channel Pressure on the appropriate member channel**.
17. **CC #74 from input is forwarded to the appropriate member channel**.
18. **Channel Pressure from input is forwarded to the appropriate member channel**.
19. **Control dimensions are initialized before Note On even when input omits them**.

#### 4.2.6 `process()` — Zone-Level Messages (Section 6.4)

20. **Sustain Pedal (CC #64) is forwarded on Master Channel**.
21. **Program Change is forwarded on Master Channel**.
22. **Reset All Controllers (CC #121) is forwarded on Master Channel**.
23. **Modulation (CC #1) is forwarded on Master Channel**.
24. **Sostenuto Pedal (CC #66) is forwarded on Master Channel**.
25. **Soft Pedal (CC #67) is forwarded on Master Channel**.

#### 4.2.7 `process()` — Pitch Bend Computation (Section 4.5)

26. **Single note on channel: output pitch bend = tuning offset + expressive pitch bend**.
27. **Multiple notes on shared channel: output pitch bend = tuning offset + average(expressive pitch bends)**.
28. **Pitch bend is clamped to valid 14-bit signed range**.

#### 4.2.8 `process()` — Dual-Group Allocation Integration

29. **Second note with same pitch class goes to Expression Group; both channels have correct independent pitch bends**.
30. **Bending one note on Expression Group channel does not affect Pitch Class Group channel for same pitch class**.

#### 4.2.9 `process()` — Note Dropping Integration

31. **Channel exhaustion triggers note dropping with Note Off output for dropped notes**.
32. **High expressive pitch bend on shared channel drops other notes (Note Off output)**.
33. **Incoming new note with high expressive pitch bend triggers dropping of existing notes on the allocated channel**.
34. **Boundary notes (highest/lowest) are preserved during channel exhaustion dropping**.

#### 4.2.10 `process()` — MPE Input Mode

35. **MPE input: notes already on member channels are processed with tuning offsets applied**.
36. **MPE input: per-note pitch bend is treated as expressive pitch bend and combined with tuning offset**.
37. **MPE input: Master Channel pitch bend is forwarded without modification**.
38. **Receiving MCM switches input mode to MPE automatically**.
39. **MPE input: per-note pitch bend with high expression triggers note dropping on shared channel**.

#### 4.2.11 `process()` — Note Off Behavior (Section 6.5)

40. **After Note Off, channel's pitch bend is no longer updated by tuning changes**.
41. **Channel becomes available for reuse after all its notes receive Note Off**.
42. **After Note Off, `tune()` no longer updates the released channel's pitch bend**.

#### 4.2.12 Worked Examples from Paper

43. **Section 8.1: Basic allocation in quarter-comma meantone** — reproduce the 5-step example.
44. **Section 8.2: Tuning change during performance** — reproduce the meantone-to-Pythagorean example.
45. **Section 8.3: Note dropping under channel exhaustion** — reproduce the 3-channel example.

---

## 5. Update `JsonTunerPluginFormat`

Add `MpeTuner` serialization/deserialization support to `JsonTunerPluginFormat.scala`.

### 5.1 JSON Format

```json
{
  "type": "mpe",
  "inputMode": "nonMpe",
  "zones": {
    "lower": {
      "memberCount": 7,
      "masterPitchBendSensitivity": {
        "semitoneCount": 2,
        "centCount": 0
      },
      "memberPitchBendSensitivity": {
        "semitoneCount": 48,
        "centCount": 0
      }
    },
    "upper": {
      "memberCount": 7,
      "masterPitchBendSensitivity": {
        "semitoneCount": 1,
        "centCount": 0
      },
      "memberPitchBendSensitivity": {
        "semitoneCount": 12,
        "centCount": 0
      }
    }
  }
}
```

#### Defaults

- `inputMode`: `"nonMpe"`. Can be `"nonMpe"` or `"mpe"`.
- `zones`: defaults to a single `"lower"` zone with `memberCount` of `15`. Inside `zones`, both `lower` and `upper`
  properties are optional and default as follows:
    - `lower`: `memberCount` = `15`, `masterPitchBendSensitivity` = `{ semitoneCount: 2, centCount: 0 }`,
      `memberPitchBendSensitivity` = `{ semitoneCount: 48, centCount: 0 }`.
    - `upper`: `memberCount` = `0` (disabled), same default pitch bend sensitivities as `lower`.
- `masterPitchBendSensitivity` and `memberPitchBendSensitivity` have the same default values for both zones:
  2 semitones and 48 semitones, respectively.

#### Semantics

- If `inputMode` is `"mpe"`, the `MpeTuner` assumes that input zone configuration, including Pitch Bend Sensitivities,
  is identical for both input and output. If `inputMode` is `"nonMpe"`, the zones configuration is just for the output.
- `zones` and `inputMode` act as default and initial values, but may be overwritten upon receiving MPE Configuration
  Messages or Pitch Bend Sensitivity messages at runtime.
- The high expression pitch bend threshold is not configurable and uses a constant value of 50 cents.

### 5.2 Test Cases (`JsonTunerPluginFormatTest` — add to existing)

1. **Deserialize MpeTuner with all fields specified** (both zones, both pitch bend sensitivities, inputMode).
2. **Deserialize MpeTuner with default values (minimal JSON: just `{ "type": "mpe" }`)** — should produce default
   lower zone with 15 members, upper zone disabled, nonMpe input mode, default pitch bend sensitivities.
3. **Deserialize MpeTuner with only lower zone specified** — upper zone gets defaults (memberCount=0).
4. **Deserialize MpeTuner with only upper zone specified** — lower zone gets defaults (memberCount=15).
5. **Deserialize MpeTuner with zones but omitting pitch bend sensitivities** — defaults applied.
6. **Serialize MpeTuner and verify JSON structure**.
7. **Round-trip: serialize then deserialize produces equal MpeTuner**.
8. **Validation failures table**:
    - `inputMode`: invalid string value.
   - `zones.lower.memberCount`: out of range (e.g. 16, negative).
   - `zones.lower.memberCount`: wrong type.
   - `zones.lower.masterPitchBendSensitivity.semitoneCount`: out of uint7 range.
   - `zones.lower.memberPitchBendSensitivity.centCount`: out of uint7 range.
    - Overlapping zone channel ranges (e.g. lower with 10 members and upper with 10 members) — deserialization must
      fail with a validation error.

---

## 6. Implementation Order (TDD)

Each step follows Red-Green-Refactor:

1. **ScMidiMessage additions** — write tests for `ScChannelPressureMidiMessage` and `ScPolyPressureMidiMessage`, then
   implement.
2. **MpeZone model** — write `MpeZoneTest`, then implement `MpeZone`.
3. **MpeChannelAllocator** — write `MpeChannelAllocatorTest` incrementally (basic allocation → expression group →
   sharing → dropping), implement after each group of tests.
4. **MpeTuner** — write `MpeTunerTest` incrementally (reset → tune → process basics → conversion → zone messages → pitch
   bend computation → dropping → MPE input → worked examples), implement after each group.
5. **JsonTunerPluginFormat** — write tests for MpeTuner JSON format, then implement.
6. **Integration review** — ensure all tests pass, review edge cases.

After each step, if all tests pass, commit changes in Git and move on to the next step.
