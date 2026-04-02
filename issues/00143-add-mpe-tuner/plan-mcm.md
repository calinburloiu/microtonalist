# Plan: MpeZones class + MCM/PBS Processing in MpeTuner

## Context

`MpeTuner` currently uses a `(MpeZone, MpeZone)` tuple for zone configuration and does NOT process incoming MCM (MPE
Configuration Messages) or PBS (Pitch Bend Sensitivity) RPN messages. The MPE spec requires receivers to handle both:
MCM to reconfigure zones dynamically (with overlap resolution), and PBS to update pitch bend sensitivity on master and
member channels. This plan introduces an `MpeZones` case class with overlap-aware construction and update semantics,
integrates MCM + PBS processing into `MpeTuner`, and updates `JsonTunerPluginFormat` to use the new type.

## Step 1: Create `MpeZones` case class in MpeZone.scala

**File:** `tuner/src/main/scala/.../MpeZone.scala`

Add after existing `MpeZone` companion:

- **`MpeZones` case class** with private constructor, `lower: MpeZone`, `upper: MpeZone`
- **Companion `apply`**: `require` zone types match parameter names, then adjust lower on overlap (upper treated as
  later arrival)
- **`def update(zone: MpeZone): MpeZones`**: replaces zone of matching type; shrinks the *other* zone on overlap (new
  zone takes precedence). Uses `new MpeZones(...)` directly to bypass the constructor's "shrink lower" default.
- **Overlap formula**: `lower.isEnabled && upper.isEnabled && lower.memberCount + upper.memberCount > 14` (NOT Set
  intersection)
- **Shrink formula**: `Math.max(0, 14 - newZone.memberCount)`
- **Companion `wouldOverlap(lower, upper)`**: `require` zone types, return boolean using the formula above
- **Companion `DefaultZones`**: `MpeZones(MpeZone(Lower, 15), MpeZone(Upper, 0))` — moved from `MpeTuner`
- PBS is preserved when a zone is shrunk (only `memberCount` changes via `copy`)

## Step 2: Add `MpeZones` tests

**File:** `tuner/src/test/scala/.../MpeZoneTest.scala`

New `behavior of "MpeZones"` section:
- Construction with no overlap preserves both zones
- Construction with overlap shrinks lower zone, preserves PBS
- `wouldOverlap` returns correct results
- `update` with no overlap preserves other zone
- `update` causing overlap shrinks other zone, preserves its PBS
- Full example flow: lower=15/upper=0 -> update lower=14 -> update upper=7 (lower shrinks to 7) -> update lower=6 (upper
  unchanged)
- `require` fails for wrong zone types

## Step 3: Update `MpeTuner` to use `MpeZones` + add MCM/PBS processing

**File:** `tuner/src/main/scala/.../MpeTuner.scala`

### 3a. Constructor/field changes

- Rename `inputMode` param to `initialInputMode`
- `val initialZones: MpeZones = MpeZones.DefaultZones` (replaces `zones` tuple)
- `private var currentZones: MpeZones = initialZones`
- `private var currentInputMode: MpeInputMode = initialInputMode`
- Allocators become `var`, created via `private def createAllocator(zone: MpeZone): Option[MpeChannelAllocator]`
- `lowerZone`/`upperZone` delegate to `currentZones.lower`/`currentZones.upper`

### 3b. RPN state machine for MCM + PBS detection
- `private val rpnLsbState: mutable.Map[Int, Int]` and `rpnMsbState` — track CC#100/CC#101 per channel
- Clear in `reset()`
- Used to detect both MCM (RPN 0x00/0x06) and PBS (RPN 0x00/0x00) sequences

### 3c. RPN detection in `processCc`

Handle CC#100 (RPN LSB), CC#101 (RPN MSB), CC#6 (Data Entry MSB), CC#38 (Data Entry LSB) before existing match:

- **CC#100, CC#101**: store value per channel AND forward to output (downstream devices need the full RPN sequence)
- **CC#6 (Data Entry MSB)**: check accumulated RPN for that channel:
  - If MCM (MSB=0, LSB=6) on channel 0 or 15 -> call `processMcm`
  - If PBS (MSB=0, LSB=0) -> call `processPbsMsb` (updates semitones)
  - Otherwise fall through to existing CC processing
- **CC#38 (Data Entry LSB)**: check accumulated RPN for that channel:
  - If PBS (MSB=0, LSB=0) -> call `processPbsLsb` (updates cents)
  - Otherwise fall through
- **Other CCs**: proceed normally (do NOT clear RPN state — RPN state is per-channel and persistent, MIDI streams may be
  interleaved across channels)

### 3d. Refactor `reset()` to separate state reset from output generation

Extract two reusable helpers from `reset()`:

- **`resetState()`**: clears all internal mutable state (noteChannelMap, mpeInputChannelMap, expression maps, RPN state,
  allocators, `_globalExpressivePitchBend`). Recreates allocators from `currentZones`.
- **`configurationMessages()`**: returns `Seq[MidiMessage]` with MCM + PBS messages for all enabled zones in
  `currentZones` (the existing output logic from `reset()`).

`reset()` becomes: revert `currentZones = initialZones` and `currentInputMode = initialInputMode`, call `resetState()`,
return `configurationMessages()`.

### 3e. `processMcm` method
1. Create `MpeZone(zoneType, memberCount)` with default PBS
2. `currentZones = currentZones.update(newZone)`
3. Send Note Off for all active notes (iterate `noteChannelMap`)
4. Call `resetState()` (clears tracking state, recreates allocators)
5. Append `configurationMessages()` to buffer (MCM + PBS for enabled zones)
6. Set `currentInputMode = MpeInputMode.Mpe`

### 3f. PBS processing methods

Following `MonophonicPitchBendTuner` pattern (lines 118-175 of that file):

**`processPbsMsb(buffer, channel, semitones)`:**

1. Determine which zone the channel belongs to (master or member)
2. If master channel: update zone's `masterPitchBendSensitivity` semitones via
   `currentZones.update(zone.copy(masterPitchBendSensitivity = pbs.copy(semitones = value)))`
3. If member channel: per MPE spec, "a receiver must apply the last Pitch Bend Sensitivity message received on any
   Member Channel to all Member Channels in the Zone" — update zone's `memberPitchBendSensitivity` semitones
4. Recreate the affected allocator (since `MpeChannelAllocator` uses `zone.memberPitchBendSensitivity` for pitch bend
   threshold)
5. Forward PBS to output: reuse `pitchBendSensitivityMessages()` for the affected zone (propagates complete PBS RPN
   sequence to all relevant channels downstream)
6. Recompute and output updated pitch bends on occupied member channels (after the forwarded PBS, since the sensitivity
   changed and the same cents offset now maps to a different pitch bend value)

**`processPbsLsb(buffer, channel, cents)`:**
Same as above but updates the `cents` field of the PBS.

### 3g. Update all `inputMode` references -> `currentInputMode`
In: `processNoteOn`, `processNoteOff`, `processPitchBend`, `forwardToMemberChannel`, `getAllocatorForInput`

### 3h. Remove `DefaultZones` from `MpeTuner` companion (moved to `MpeZones`)

## Step 4: Update `JsonTunerPluginFormat`

**File:** `format/src/main/scala/.../JsonTunerPluginFormat.scala`

- `zonesReads` returns `Reads[MpeZones]`
- Replace `lower.memberChannels.toSet.intersect(upper.memberChannels.toSet).nonEmpty` with
  `MpeZones.wouldOverlap(lower, upper)`
- Default: `Reads.pure(MpeZones.DefaultZones)`
- Yield: `MpeZones(lower, upper)` instead of tuple
- Construct: `MpeTuner(zones, inputMode)` (named params `initialZones`, `initialInputMode`)
- Writes: `tuner.initialZones.lower` / `.upper`, `tuner.initialInputMode`

## Step 5: Update test files

### `JsonTunerPluginFormatTest.scala`

- `assertMpeTuner`: access `mpe.initialZones.lower`/`.upper` and `mpe.initialInputMode`
- `"serialize"` and `"round-trip"` tests: use `MpeZones(...)` instead of tuple, `initialInputMode`

### `MpeTunerTest.scala`

- Update fixture methods to use `MpeZones` and `initialInputMode`
- Add `behavior of "MpeTuner - MCM Processing"`:
  - MCM on ch 0 reconfigures lower zone (verify output MCM + PBS)
  - MCM on ch 15 reconfigures upper zone
  - MCM stops active notes (Note Off output)
  - MCM with overlap shrinks other zone
  - MCM switches to MPE input mode
  - MCM with memberCount=0 disables zone
  - Incomplete RPN sequence doesn't trigger MCM
  - Non-MCM RPN (e.g., PBS) doesn't trigger MCM
  - MCM on non-master channel ignored
  - `reset()` reverts to `initialZones` and `initialInputMode`
- Add `behavior of "MpeTuner - PBS Processing"`:
  - PBS on master channel updates master PBS for correct zone
  - PBS on member channel updates member PBS for all member channels in zone
  - PBS change recomputes pitch bends on occupied channels
  - PBS on member channel of one zone does not affect the other zone
  - `reset()` reverts PBS to initial values

## Verification

1. `sbt tuner/compile` — compile tuner module
2. `sbt format/compile` — compile format module
3. `sbt "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeZoneTest"` — MpeZones tests
4. `sbt "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest"` — MpeTuner tests
5. `sbt "format/testOnly org.calinburloiu.music.microtonalist.format.JsonTunerPluginFormatTest"` — format tests
6. `sbt test` — full test suite
