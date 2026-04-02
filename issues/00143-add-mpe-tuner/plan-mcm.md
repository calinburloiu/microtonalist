# Plan: MpeZones class + MCM Processing in MpeTuner

## Context

`MpeTuner` currently uses a `(MpeZone, MpeZone)` tuple for zone configuration and does NOT process incoming MCM (MPE
Configuration Messages). The MPE spec requires receivers to handle MCM to reconfigure zones dynamically, including
overlap resolution. This plan introduces an `MpeZones` case class with overlap-aware construction and update semantics,
integrates MCM processing into `MpeTuner`, and updates `JsonTunerPluginFormat` to use the new type.

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
- Full example flow: lower=15/upper=0 → update lower=14 → update upper=7 (lower shrinks to 7) → update lower=6 (upper
  unchanged)
- `require` fails for wrong zone types

## Step 3: Update `MpeTuner` to use `MpeZones` + add MCM processing

**File:** `tuner/src/main/scala/.../MpeTuner.scala`

### 3a. Constructor/field changes

- `val initialZones: MpeZones = MpeZones.DefaultZones` (replaces `zones` tuple)
- `private var currentZones: MpeZones = initialZones`
- `private var currentInputMode: MpeInputMode = inputMode`
- Allocators become `var`, created via `private def createAllocator(zone: MpeZone): Option[MpeChannelAllocator]`
- `lowerZone`/`upperZone` delegate to `currentZones.lower`/`currentZones.upper`

### 3b. RPN state machine for MCM detection

- `private val rpnLsbState: mutable.Map[Int, Int]` and `rpnMsbState` — track CC#100/CC#101 per channel
- Clear in `reset()`

### 3c. MCM detection in `processCc`

Handle CC#100 (RPN LSB), CC#101 (RPN MSB), CC#6 (Data Entry MSB) before existing match:

- CC#100, CC#101: store value, consume (don't forward)
- CC#6: check if accumulated RPN is (MSB=0, LSB=6) on channel 0 or 15 → call `processMcm`; otherwise clear RPN state and
  fall through to existing logic
- Other CCs: clear RPN state, proceed normally

### 3d. `processMcm` method

1. Create `MpeZone(zoneType, memberCount)` with default PBS
2. `currentZones = currentZones.update(newZone)`
3. Send Note Off for all active notes (iterate `noteChannelMap`)
4. Clear internal state (noteChannelMap, mpeInputChannelMap, expression maps)
5. Recreate allocators from `currentZones`
6. Output MCM messages + PBS messages for enabled zones
7. Set `currentInputMode = MpeInputMode.Mpe`

### 3e. `reset()` changes

- Revert `currentZones = initialZones`, `currentInputMode = inputMode`
- Recreate allocators
- Clear RPN state

### 3f. Update all `inputMode` references → `currentInputMode`

In: `processNoteOn`, `processNoteOff`, `processPitchBend`, `forwardToMemberChannel`, `getAllocatorForInput`

### 3g. Remove `DefaultZones` from `MpeTuner` companion (moved to `MpeZones`)

## Step 4: Update `JsonTunerPluginFormat`

**File:** `format/src/main/scala/.../JsonTunerPluginFormat.scala`

- `zonesReads` returns `Reads[MpeZones]`
- Replace `lower.memberChannels.toSet.intersect(upper.memberChannels.toSet).nonEmpty` with
  `MpeZones.wouldOverlap(lower, upper)`
- Default: `Reads.pure(MpeZones.DefaultZones)`
- Yield: `MpeZones(lower, upper)` instead of tuple
- Construct: `MpeTuner(zones, inputMode)` (named param `initialZones`)
- Writes: `tuner.initialZones.lower` / `.upper`

## Step 5: Update test files

### `JsonTunerPluginFormatTest.scala`

- `assertMpeTuner`: access `mpe.initialZones.lower`/`.upper`
- `"serialize"` and `"round-trip"` tests: use `MpeZones(...)` instead of tuple

### `MpeTunerTest.scala`

- Update fixture methods to use `MpeZones`
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
    - `reset()` reverts to `initialZones`

## Verification

1. `sbt tuner/compile` — compile tuner module
2. `sbt format/compile` — compile format module
3. `sbt "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeZoneTest"` — MpeZones tests
4. `sbt "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest"` — MpeTuner tests
5. `sbt "format/testOnly org.calinburloiu.music.microtonalist.format.JsonTunerPluginFormatTest"` — format tests
6. `sbt test` — full test suite
