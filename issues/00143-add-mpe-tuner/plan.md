# Plan: Add MpeTuner

## Overview

Implement `MpeTuner`, a `Tuner` that uses MIDI Polyphonic Expression (MPE) to apply microtonal tunings to polyphonic
MIDI streams. The design follows the [MPE Tuner paper](../../docs/tuner/mpe-tuner-paper.md), which details dual-group
channel partitioning, the pitch-class invariant, note dropping, and non-MPE to MPE conversion.

Update `JsonTunerPluginFormat` to support serialization/deserialization of `MpeTuner`.

All code is developed with strict TDD: tests are written first, verified to fail, then implementation is added.

---

## 1. Add New MIDI Message Types to `ScMidiMessage.scala`

MPE requires Channel Pressure and Polyphonic Key Pressure messages. Add:

- `ScChannelPressureMidiMessage(channel: Int, value: Int)` with companion `unapply` extractor.
- `ScPolyKeyPressureMidiMessage(channel: Int, midiNote: MidiNote, value: Int)` with companion `unapply` extractor.

### Test Cases (`ScMidiMessageTest` or similar)

1. **`ScChannelPressureMidiMessage` creates correct Java MIDI message** — verify command, channel, data.
2. **`ScChannelPressureMidiMessage.unapply` extracts from a valid Channel Pressure message**.
3. **`ScChannelPressureMidiMessage.unapply` returns None for non-Channel-Pressure messages**.
4. **`ScPolyKeyPressureMidiMessage` creates correct Java MIDI message**.
5. **`ScPolyKeyPressureMidiMessage.unapply` extracts from a valid Poly Key Pressure message**.
6. **`ScPolyKeyPressureMidiMessage.unapply` returns None for non-Poly-Key-Pressure messages**.
7. **Validation rejects invalid channel/value for both new message types**.

---

## 2. Implement MPE Zone Model

Create supporting types in the `tuner` module (e.g. `MpeZone.scala` or inside `MpeTuner.scala`):

- `MpeZone` — represents a zone with master channel, member channel range, pitch class group size, expression group
  size.
- `MpeZoneConfig` — configuration: zone type (Lower/Upper), number of member channels.
- Group size computation per the paper's table (Section 4.3 / Appendix A).

### Test Cases (`MpeZoneTest`)

1. **Lower Zone with 15 member channels** — master=0, members=1..15, pitchClassGroup=12, expressionGroup=3.
2. **Lower Zone with 7 member channels** — master=0, members=1..7, pitchClassGroup=5, expressionGroup=2.
3. **Upper Zone with 7 member channels** — master=15, members=8..14, pitchClassGroup=5, expressionGroup=2.
4. **Lower Zone with 3 member channels** — pitchClassGroup=1, expressionGroup=2.
5. **Lower Zone with 2 member channels** — pitchClassGroup=1, expressionGroup=1.
6. **Lower Zone with 1 member channel** — pitchClassGroup=1, expressionGroup=0.
7. **Two zones: Lower 7 + Upper 7** — channels don't overlap.
8. **Invalid: 0 member channels** — should fail validation.
9. **Group sizes match Appendix A table for all n from 1 to 15**.

---

## 3. Implement Channel Allocator

Create `MpeChannelAllocator` (internal to tuner module) implementing the allocation algorithm from Section 4.4 and
Appendix B.

### Test Cases (`MpeChannelAllocatorTest`)

#### 3.1 Basic Allocation (Pitch Class Group)

1. **First note allocates to an unoccupied Pitch Class Group channel**.
2. **Notes with distinct pitch classes each get their own Pitch Class Group channel**.
3. **Filling all Pitch Class Group channels with distinct pitch classes works correctly**.

#### 3.2 Expression Group Allocation

4. **Second note with same pitch class as existing Pitch Class Group note goes to Expression Group**.
5. **Third note with same pitch class (Expression Group also has one) goes to occupied channel with same pitch class**.
6. **Note with new pitch class when Pitch Class Group is full goes to Expression Group**.

#### 3.3 Channel Sharing

7. **When both groups are full, new note with existing pitch class shares channel with same pitch class (lowest note
   count, then oldest)**.
8. **Tie-breaking: prefer channel with lowest active note count**.
9. **Tie-breaking: among equal note counts, prefer channel with oldest last Note Off**.

#### 3.4 Note Dropping — Channel Exhaustion (Section 5.1)

10. **When all channels occupied and new pitch class needs a channel, a channel is freed**.
11. **Freed channel excludes highest-pitched and lowest-pitched note channels**.
12. **Among remaining candidates, the channel with the oldest last onset is freed**.
13. **Freed channel's notes receive Note Off before new note is assigned**.

#### 3.5 Note Dropping — High Expressive Pitch Bend (Section 5.2)

14. **When a note on a shared channel develops high expressive pitch bend (>50 cents), other notes on that channel are
    dropped**.
15. **New note with high expressive pitch bend assigned to occupied channel: existing notes are dropped (channel freed)
    **.
16. **New note assigned to channel with existing high-bend note: channel is freed first**.
17. **A note with high expressive pitch bend is always sole note on its channel**.

#### 3.6 Channel Release

18. **Note Off makes channel available for reuse when all notes on it have ended**.
19. **Channel with multiple notes remains occupied until all notes receive Note Off**.

#### 3.7 MPE Input — Preserving Input Channel (Section 4.5)

20. **MPE input: input channel assignment is preserved when it doesn't violate constraints**.
21. **MPE input: input channel is overridden when it would violate pitch-class invariant**.

---

## 4. Implement `MpeTuner`

Create `MpeTuner` class extending `Tuner` in the `tuner` module.

### 4.1 Constructor Parameters

- `zone`: Zone configuration (Lower/Upper, number of member channels). Default: Lower Zone with 15 member channels.
- `inputMode`: Non-MPE or MPE. Default: Non-MPE.
- `highExpressionThreshold`: Threshold in cents for high expressive pitch bend. Default: 50.
- `pitchBendSensitivity`: Member channel pitch bend sensitivity. Default: `PitchBendSensitivity(48)`.

### 4.2 `MpeTuner` Test Cases (`MpeTunerTest`)

#### 4.2.1 `reset()` — Initialization (Section 6.1, 6.2)

1. **`reset()` outputs MPE Configuration Message (MCM) for the configured zone**.
2. **`reset()` outputs RPN 0 (Pitch Bend Sensitivity) on all member channels with ±48 semitones**.
3. **`reset()` clears internal state (no lingering notes or pitch bends from before reset)**.

#### 4.2.2 `tune()` — Tuning Application (Section 7)

4. **`tune()` with no active notes stores tuning but outputs no messages**.
5. **`tune()` with active notes outputs updated Pitch Bend on each occupied member channel**.
6. **`tune()` recomputes pitch bend = tuning offset + current expressive pitch bend for each channel**.
7. **Tuning change correctly retunes notes of different pitch classes on different channels**.

#### 4.2.3 `process()` — Non-MPE Input, Basic Note Handling

8. **Single Note On: outputs Pitch Bend (tuning offset), CC #74 (64), Channel Pressure (0), then Note On on allocated
   member channel**.
9. **Note Off: outputs Note Off on the correct member channel**.
10. **Multiple notes with distinct pitch classes are allocated to separate member channels**.
11. **Note On velocity and Note Off velocity are preserved**.
12. **Notes from any input channel are correctly allocated to member channels**.

#### 4.2.4 `process()` — Non-MPE Input, Pitch Bend Handling

13. **Input Pitch Bend (expressive) is redirected to Master Channel as Zone-level Pitch Bend**.
14. **Expressive pitch bend from input does NOT affect member channel tuning pitch bend**.
15. **When input has per-note pitch bend (MPE input mode), it is combined with tuning offset on the member channel**.

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

#### 4.2.7 `process()` — Pitch Bend Computation (Section 4.5)

24. **Single note on channel: output pitch bend = tuning offset + expressive pitch bend**.
25. **Multiple notes on shared channel: output pitch bend = tuning offset + average(expressive pitch bends)**.
26. **Pitch bend is clamped to valid 14-bit signed range**.

#### 4.2.8 `process()` — Dual-Group Allocation Integration

27. **Second note with same pitch class goes to Expression Group; both channels have correct independent pitch bends**.
28. **Bending one note on Expression Group channel does not affect Pitch Class Group channel for same pitch class**.

#### 4.2.9 `process()` — Note Dropping Integration

29. **Channel exhaustion triggers note dropping with Note Off output for dropped notes**.
30. **High expressive pitch bend on shared channel drops other notes (Note Off output)**.
31. **Boundary notes (highest/lowest) are preserved during channel exhaustion dropping**.

#### 4.2.10 `process()` — MPE Input Mode

32. **MPE input: notes already on member channels are processed with tuning offsets applied**.
33. **MPE input: per-note pitch bend is treated as expressive pitch bend and combined with tuning offset**.
34. **MPE input: Master Channel pitch bend is forwarded without modification**.
35. **Receiving MCM switches input mode to MPE automatically**.

#### 4.2.11 `process()` — Real-Time Tuning Change Integration (Section 7)

36. **Tuning change while notes are active: all occupied channels get updated pitch bend immediately**.
37. **Tuning change with notes on shared channel: single pitch bend update retunes all notes on that channel**.

#### 4.2.12 `process()` — Note Off Behavior (Section 6.5)

38. **After Note Off, channel's pitch bend is no longer updated by tuning changes**.
39. **Channel becomes available for reuse after all its notes receive Note Off**.

#### 4.2.13 Worked Examples from Paper

40. **Section 8.1: Basic allocation in quarter-comma meantone** — reproduce the 5-step example.
41. **Section 8.2: Tuning change during performance** — reproduce the meantone-to-Pythagorean example.
42. **Section 8.3: Note dropping under channel exhaustion** — reproduce the 3-channel example.

---

## 5. Update `JsonTunerPluginFormat`

Add `MpeTuner` serialization/deserialization support to `JsonTunerPluginFormat.scala`.

### 5.1 JSON Schema

```json
{
  "type": "mpe",
  "zone": "lower",
  "memberChannelCount": 15,
  "inputMode": "nonMpe",
  "highExpressionThreshold": 50,
  "pitchBendSensitivity": {
    "semitoneCount": 48,
    "centCount": 0
  }
}
```

Default values: `zone` = `"lower"`, `memberChannelCount` = `15`, `inputMode` = `"nonMpe"`, `highExpressionThreshold` =
`50`, `pitchBendSensitivity` = `{ semitoneCount: 48, centCount: 0 }`.

### 5.2 Test Cases (`JsonTunerPluginFormatTest` — add to existing)

1. **Deserialize MpeTuner with all fields specified**.
2. **Deserialize MpeTuner with default values (minimal JSON with just `type`)**.
3. **Serialize MpeTuner and verify JSON structure**.
4. **Round-trip: serialize then deserialize produces equal MpeTuner**.
5. **Validation failures table**:
    - `zone`: invalid string value (not `"lower"` or `"upper"`).
    - `memberChannelCount`: out of range (0, 16).
    - `memberChannelCount`: wrong type.
    - `inputMode`: invalid string value.
    - `highExpressionThreshold`: negative value.
    - `pitchBendSensitivity.semitoneCount`: out of uint7 range.
    - `pitchBendSensitivity.centCount`: out of uint7 range.

---

## 6. Implementation Order (TDD)

Each step follows Red-Green-Refactor:

1. **ScMidiMessage additions** — write tests for `ScChannelPressureMidiMessage` and `ScPolyKeyPressureMidiMessage`, then
   implement.
2. **MpeZone model** — write `MpeZoneTest`, then implement `MpeZone`.
3. **MpeChannelAllocator** — write `MpeChannelAllocatorTest` incrementally (basic allocation → expression group →
   sharing → dropping), implement after each group of tests.
4. **MpeTuner** — write `MpeTunerTest` incrementally (reset → tune → process basics → conversion → zone messages → pitch
   bend computation → dropping → MPE input → worked examples), implement after each group.
5. **JsonTunerPluginFormat** — write tests for MpeTuner JSON format, then implement.
6. **Integration review** — ensure all tests pass, review edge cases.
