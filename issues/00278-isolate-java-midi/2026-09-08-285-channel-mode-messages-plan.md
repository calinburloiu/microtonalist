# Channel Mode Messages as Their Own Types — Implementation Plan (#285)

- **Date**: 2026-09-08
- **Issue**: [#285](https://github.com/calinburloiu/microtonalist/issues/285) — "Model MIDI Channel Mode messages as
  their own types instead of CcMidiMsg", sub-issue 6 of parent
  [#278](https://github.com/calinburloiu/microtonalist/issues/278)
- **Base commit**: `564998ea596189936538df21609594268165f979` — "[#278/#282] Correct the device-lifecycle ScalaDocs
  and record the manager's ownership in app", the tip of `refactoring/282-midi-manager-traits`. Every file path, line
  number and code excerpt below was read at that commit.
- **Branch**: create `refactoring/285-channel-mode-messages` **off `refactoring/282-midi-manager-traits`** and target
  its PR at that branch — #285 continues the #278 branch stack. Before starting, run
  `git log --oneline main..refactoring/282-midi-manager-traits`; if it is empty the lower stack has merged, so base
  the work on `main` instead. Do not merge, rebase, retarget or force-push the branches below it.
- **Spec**: [`2026-09-07-isolate-java-midi-design.md`](2026-09-07-isolate-java-midi-design.md) — the approved design
  for the whole of #278. The scope of this plan is **decision D10** and the **#285 row of Section 3**; Sections 4
  (testing) and 5 (documentation) apply where they mention the Channel Mode messages. No separate design document
  exists for #285.
- **Relation to the rest of the stack**: D10 depends only on #279 (the `MidiMsg` names), which is already in the
  stack, so this plan uses the current names throughout — `MidiMsg`, `CcMidiMsg`, `MidiReceiver`,
  `JavaMidiConverters` in `org.calinburloiu.music.scmidi.javamidi`. Nothing here touches the transmitter family, the
  pipeline or the device layer.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Model MIDI 1.0's eight Channel Mode messages as their own `ChannelModeMidiMsg` subtypes instead of
`CcMidiMsg` values with controller numbers 120–127, restrict `CcMidiMsg.number` to 0–119, switch every consumer
— the Java Sound converters, `MidiChannelStateTracker` and the MPE message routing — from matching on numbers to
matching on types, and tighten the pedal-trigger JSON format and JSON Schemas to the same 0–119 range.

**Architecture:** A new `sealed abstract class ChannelModeMidiMsg(channel) extends ChannelMidiMsg(channel)` sits
beside the Channel Voice messages in `MidiMsg.scala` (it must live in that file: `ChannelMidiMsg` is `sealed`), with
one `case class` per message. Only Local Control and Mono Mode On carry a field; the other six emit data byte `0` and
ignore whatever arrived, the one deliberate loss of the byte-level round trip. Each companion owns its controller
number, and `ChannelModeMidiMsg.NumberRange` is the range those numbers span, defined as the range immediately above
`MidiRequirements.MaxControllerNumber` (119) so that the two constants cannot drift. `JavaMidiConverters` dispatches
a `CONTROL_CHANGE` on its controller number, inbound and outbound. Consumers match on the new types.

The work is sequenced so the suite is green at every commit: the new types and the converters land first (nothing
constructs a Channel Mode `CcMidiMsg` any more once each consumer has migrated), then the `CcMidiMsg` restriction and
the deletion of the `MidiCc` Channel Mode constants as the enforcement step, then the `format` module and the JSON
Schemas, which turn a now-dead pedal-trigger configuration into a load-time error.

**Tech Stack:** Scala 3, sbt 1 (via `sbtn` on the BSP server), ScalaTest 3 (`AnyFlatSpec` + `Matchers`,
`TableDrivenPropertyChecks`), `javax.sound.midi` (inside `scmidi.javamidi` only), Metals MCP for compilation,
scoverage for coverage.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Strict TDD.** Red (a failing test, compiling — stub with `???` bodies if the compiler demands it), green (the
  minimum production code), refactor. Never commit red production code; never mix a refactor with a behaviour change.
- **Coding conventions** (`docs/development/coding-conventions.md`): brace syntax, never the Scala 3 indentation
  syntax; 2-space indent; 120-column lines; no `new` when instantiating a class (a Java class such as
  `new ShortMessage(...)` keeps `new`, as the existing converter code does); no `return`; ScalaDoc on **every** public
  identifier. `Msg` is the suffix of message *types* only — helpers and prose keep the full word *message*.
- **Test conventions** (`docs/development/test-conventions.md`): `AnyFlatSpec` + `Matchers`, `behavior of` sections,
  `// Given` / `// When` / `// Then` comments, no `if` in tests, fixtures for repeated setup. Check a test class's own
  ScalaDoc first — `MpeMessageRoutingTest` and `MpeTunerTest` carry class-specific conventions that take precedence.
- **License headers**: the `.githooks/pre-commit` hook adds them to `.scala` files; do not write them by hand. A
  `PreToolUse` hook hides the ~16-line header when reading, so files appear to start at line 17 with **real line
  numbers preserved**.
- **Compiling**: `mcp__metals__compile-module` with `module = "sc-midi"` or `"tuner"`, or `mcp__metals__compile-full`.
  Fall back to `sbtn` only if the Metals MCP is unavailable. Warm up once per session per the root `CLAUDE.md`.
- **Testing**: always through `sbtn`, always with the reporter flags. A single class:
  `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.message.MidiMsgTest -- -oNCXEHLOPQRMWS"`. A module:
  `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`. Everything: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`.
- **Coverage floors** (`build.sbt`): `sc-midi` 67% statement / 52% branch, `tuner` 80% / 80%, `format` 66% / 59%.
  New files target 80%.
  Floors must never drop. **`sc-midi` branch coverage is a pre-existing 51.22% against the floor of 52 on this branch
  stack, so `coverageCheck` already fails for reasons unrelated to this work** — compare against that baseline rather
  than expecting green, and do not lower the floor.
- **Exactly one message type per controller number.** Numbers 120–127 belong to `ChannelModeMidiMsg` subtypes;
  0–119 to `CcMidiMsg`. After Task 5 no `CcMidiMsg` can carry 120–127 at all, and after Task 6 no composition can ask for one.
- **Commit per task**, message prefixed `[#278/#285]`, ending with the two attribution lines the session's
  instructions specify. Do not push and do not open a PR in the implementation session unless the user asks.

## Decisions this plan settles

D10 leaves three small points open. This plan settles them; they are within D10's latitude and need no design
amendment.

1. **Local Control decoding.** MIDI 1.0 defines only data byte `0` (off) and `127` (on) for Local Control. Anything
   else follows the specification's general switch-controller convention — 0–63 off, 64–127 on — exposed as
   `LocalControlMidiMsg.OnThreshold = 64`. Encoding always emits `OffValue = 0` or `OnValue = 127`.
2. **A malformed Mono Mode On.** `MonoModeOnMidiMsg.channelCount` is validated to 0–16 (D10), but a device is free to
   put any of 0–127 on the wire, and `asScala` runs on the device's own Java Sound callback thread
   (`JavaMidiDeviceHandle.scala:68` calls it with no `try`/`catch`), where a thrown `IllegalArgumentException` would
   take down the inbound stream. A Mono Mode On with a count above 16 therefore decodes to `UnsupportedMidiMsg`,
   which is exactly the lossless escape hatch the model documents, rather than throwing.
3. **Pedal triggers on 120–127.** `PedalTuningChanger` matches `CcMidiMsg`, so once 120–127 can no longer be a
   `CcMidiMsg` a trigger configured on one of those numbers would load fine and then never fire. Rather than leave
   that silent, Task 6 tightens the JSON format and the JSON Schemas to reject those numbers at load time, and the
   class ScalaDoc records the constraint (Task 5). `uint7Format` and `uint7.schema.json` stay 0–127: the Pitch Bend
   Sensitivity fields of the Tuner plugin share them and are right to.

## File Structure

Production files, all modifications (no new files):

| File | Responsibility after this plan |
|---|---|
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala` | Gains a "Channel Mode Messages" section: `ChannelModeMidiMsg`, its companion, and the eight case classes with their companions. `CcMidiMsg` validates its number with `requireControllerNumber`. |
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiRequirements.scala` | Gains `MaxControllerNumber` and `requireControllerNumber`. |
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiCc.scala` | Loses the eight Channel Mode constants; its ScalaDoc says where those numbers went. |
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala` | Dispatches `CONTROL_CHANGE` by controller number inbound; renders one `ToJavaMap` entry per Channel Mode case class outbound. |
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiChannelStateTracker.scala` | Handles `ChannelModeMidiMsg` in its own `send` branch; no longer records 120–127 in `ccValues`. |
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala` | Gains `routeChannelMode`; `routeCc` loses its MIDI Mode arm; `deselectsOnRelay` matches a type. |
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` | Comment only — its dispatch is unchanged, because Channel Mode messages reach it through the generic `ChannelMidiMsg` path. |
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/PedalTuningChanger.scala` | ScalaDoc only — records that 120–127 can no longer trigger a pedal. |
| `format/src/main/scala/org/calinburloiu/music/microtonalist/format/package.scala` | Gains `JsonError_CcNumber` and `ccNumberFormat` (0–119) beside the untouched `uint7Format` (0–127). |
| `format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonTuningChangerPluginFormat.scala` | Its three pedal-trigger fields read `ccNumberFormat` instead of `uint7Format`. |
| `json-schemas/v1/track/ccNumber.schema.json` | **New.** The 0–119 counterpart of `uint7.schema.json`. |
| `json-schemas/v1/track/tuningChanger.schema.json` | Its three pedal-trigger `$ref`s point at `ccNumber.schema.json`. |

Test files, all modifications:

| File | Change |
|---|---|
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala` | New `ChannelModeMidiMsg` behaviour section; hierarchy and `CcMidiMsg` validation cases. |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala` | Ten new rows in the round-trip `cases` table plus a Channel Mode behaviour section. |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiChannelStateTrackerTest.scala` | Its Channel Mode section moves to the new types; two new cases. |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala` | The paper's table rows move to the new types; a `deselectsOnRelay` behaviour section. |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` | Every Channel Mode case moves to the new types; an `extractChannelModes` helper. |
| `format/src/test/scala/org/calinburloiu/music/microtonalist/format/FormatPackageObjectTest.scala` | A `ccNumberFormat` behaviour section. |
| `format/src/test/scala/org/calinburloiu/music/microtonalist/format/JsonTuningChangerPluginFormatTest.scala` | All three trigger paths reject 120–127. |

Documentation: `docs/architecture/sc-midi/README.md`, `docs/architecture/tuner/README.md`,
`docs/architecture/format/README.md` (Task 7).

---

## Task 1: The `ChannelModeMidiMsg` family

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiRequirements.scala` (add
  `MaxControllerNumber` after `MaxSigned14BitValue`, around line 26)
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala` (insert a new section between
  the `PitchBendMidiMsg` companion, which closes at line 314, and the `// System Common Messages` banner at line 316)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala`

**Interfaces:**
- Consumes: `ChannelMidiMsg` (sealed, `val channel: Int`, abstract `mapChannel(map: Int => Int): ChannelMidiMsg`),
  `MidiRequirements.requireChannel`.
- Produces, for Tasks 2–7:
  * `sealed abstract class ChannelModeMidiMsg(channel: Int) extends ChannelMidiMsg(channel)` with
    `override def mapChannel(map: Int => Int): ChannelModeMidiMsg`
  * `object ChannelModeMidiMsg { val NumberRange: Range }` (`120 to 127`)
  * `case class AllSoundOffMidiMsg(channel: Int)`, `ResetAllControllersMidiMsg(channel: Int)`,
    `LocalControlMidiMsg(channel: Int, isOn: Boolean)`, `AllNotesOffMidiMsg(channel: Int)`,
    `OmniModeOffMidiMsg(channel: Int)`, `OmniModeOnMidiMsg(channel: Int)`,
    `MonoModeOnMidiMsg(channel: Int, channelCount: Int)`, `PolyModeOnMidiMsg(channel: Int)`
  * companions: `AllSoundOffMidiMsg.Number = 120`, `ResetAllControllersMidiMsg.Number = 121`,
    `LocalControlMidiMsg.Number = 122` with `OffValue = 0`, `OnValue = 127`, `OnThreshold = 64`,
    `AllNotesOffMidiMsg.Number = 123`, `OmniModeOffMidiMsg.Number = 124`, `OmniModeOnMidiMsg.Number = 125`,
    `MonoModeOnMidiMsg.Number = 126` with `ChannelCountRange: Range = 0 to 16`, `PolyModeOnMidiMsg.Number = 127`
  * `MidiRequirements.MaxControllerNumber: Int = 119`

- [ ] **Step 1: Write the failing tests**

In `MidiMsgTest.scala`, add `TableDrivenPropertyChecks` to the class header:

```scala
import org.scalatest.prop.TableDrivenPropertyChecks
```

```scala
class MidiMsgTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {
```

Insert a new behaviour section **after** the `behavior of "PitchBendMidiMsg"` block (it ends just before
`behavior of "MidiTimeCodeMidiMsg"` at line 214), mirroring the order the types have in the model:

```scala
  behavior of "ChannelModeMidiMsg"

  private val channelModeMessages = Table(
    ("number", "message"),
    (AllSoundOffMidiMsg.Number, AllSoundOffMidiMsg(3)),
    (ResetAllControllersMidiMsg.Number, ResetAllControllersMidiMsg(3)),
    (LocalControlMidiMsg.Number, LocalControlMidiMsg(3, isOn = true)),
    (AllNotesOffMidiMsg.Number, AllNotesOffMidiMsg(3)),
    (OmniModeOffMidiMsg.Number, OmniModeOffMidiMsg(3)),
    (OmniModeOnMidiMsg.Number, OmniModeOnMidiMsg(3)),
    (MonoModeOnMidiMsg.Number, MonoModeOnMidiMsg(3, channelCount = 4)),
    (PolyModeOnMidiMsg.Number, PolyModeOnMidiMsg(3))
  )

  it should "assign each subtype the number MIDI 1.0 reserves for it" in {
    // When / Then
    AllSoundOffMidiMsg.Number shouldEqual 120
    ResetAllControllersMidiMsg.Number shouldEqual 121
    LocalControlMidiMsg.Number shouldEqual 122
    AllNotesOffMidiMsg.Number shouldEqual 123
    OmniModeOffMidiMsg.Number shouldEqual 124
    OmniModeOnMidiMsg.Number shouldEqual 125
    MonoModeOnMidiMsg.Number shouldEqual 126
    PolyModeOnMidiMsg.Number shouldEqual 127
  }

  it should "cover the Channel Mode number range exactly once, starting above the last controller number" in {
    // Given
    val numbers = channelModeMessages.map { case (number, _) => number }.toSeq

    // Then
    MidiRequirements.MaxControllerNumber shouldEqual 119
    ChannelModeMidiMsg.NumberRange shouldEqual (120 to 127)
    numbers should contain theSameElementsAs ChannelModeMidiMsg.NumberRange
  }

  it should "rewrite the channel via mapChannel, preserving the concrete subtype" in {
    forAll(channelModeMessages) { (_, message) =>
      // When
      val mapped = message.mapChannel(_ + 5)

      // Then
      mapped.channel shouldBe 8
      mapped.getClass shouldBe message.getClass
    }
  }

  it should "reject an invalid channel" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy AllSoundOffMidiMsg(16)
    an[IllegalArgumentException] should be thrownBy PolyModeOnMidiMsg(-1)
    an[IllegalArgumentException] should be thrownBy LocalControlMidiMsg(16, isOn = true)
    an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(16, channelCount = 0)
  }

  it should "carry the Local Control switch and its wire values" in {
    // When / Then
    LocalControlMidiMsg(0, isOn = true).isOn shouldBe true
    LocalControlMidiMsg(0, isOn = false).isOn shouldBe false
    LocalControlMidiMsg.OffValue shouldEqual 0
    LocalControlMidiMsg.OnValue shouldEqual 127
    LocalControlMidiMsg.OnThreshold shouldEqual 64
  }

  it should "accept every Mono Mode On channel count MIDI 1.0 allows" in {
    // Given
    val channelCounts = Table("channelCount", MonoModeOnMidiMsg.ChannelCountRange.toSeq*)

    forAll(channelCounts) { channelCount =>
      // When / Then
      MonoModeOnMidiMsg(0, channelCount).channelCount shouldEqual channelCount
    }
  }

  it should "reject a Mono Mode On channel count outside 0 to 16" in {
    // When / Then
    MonoModeOnMidiMsg.ChannelCountRange shouldEqual (0 to 16)
    an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(0, channelCount = -1)
    an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(0, channelCount = 17)
  }
```

Then extend the existing `behavior of "MidiMsg hierarchy"` section at the end of the file with:

```scala
  it should "place every Channel Mode message under ChannelModeMidiMsg, apart from the Control Changes" in {
    // When / Then
    typeChecks("val m: ChannelModeMidiMsg = AllSoundOffMidiMsg(0)") shouldBe true
    typeChecks("val m: ChannelMidiMsg = ResetAllControllersMidiMsg(0)") shouldBe true
    typeChecks("val m: Midi1Msg = MonoModeOnMidiMsg(0, 4)") shouldBe true
    typeChecks("val m: CcMidiMsg = LocalControlMidiMsg(0, true)") shouldBe false
  }
```

- [ ] **Step 2: Add the thinnest stubs so the tests compile**

In `MidiRequirements.scala`, after `val MaxSigned14BitValue` (line 26):

```scala
  /** The highest MIDI 1.0 Control Change controller number (119). */
  val MaxControllerNumber: Int = 119
```

In `MidiMsg.scala`, insert between the `PitchBendMidiMsg` companion (closes at line 314) and the
`// System Common Messages` banner (line 316) — stubs only, so the tests compile and fail on behaviour:

```scala
// ============================================================================
// Channel Mode Messages
// ============================================================================

sealed abstract class ChannelModeMidiMsg(channel: Int) extends ChannelMidiMsg(channel) {
  override def mapChannel(map: Int => Int): ChannelModeMidiMsg
}

object ChannelModeMidiMsg {
  val NumberRange: Range = 0 to 0
}

case class AllSoundOffMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): AllSoundOffMidiMsg = ???
}

object AllSoundOffMidiMsg {
  val Number: Int = 0
}

case class ResetAllControllersMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): ResetAllControllersMidiMsg = ???
}

object ResetAllControllersMidiMsg {
  val Number: Int = 0
}

case class LocalControlMidiMsg(override val channel: Int, isOn: Boolean) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): LocalControlMidiMsg = ???
}

object LocalControlMidiMsg {
  val Number: Int = 0
  val OffValue: Int = 0
  val OnValue: Int = 0
  val OnThreshold: Int = 0
}

case class AllNotesOffMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): AllNotesOffMidiMsg = ???
}

object AllNotesOffMidiMsg {
  val Number: Int = 0
}

case class OmniModeOffMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): OmniModeOffMidiMsg = ???
}

object OmniModeOffMidiMsg {
  val Number: Int = 0
}

case class OmniModeOnMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): OmniModeOnMidiMsg = ???
}

object OmniModeOnMidiMsg {
  val Number: Int = 0
}

case class MonoModeOnMidiMsg(override val channel: Int, channelCount: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): MonoModeOnMidiMsg = ???
}

object MonoModeOnMidiMsg {
  val Number: Int = 0
  val ChannelCountRange: Range = 0 to 0
}

case class PolyModeOnMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): PolyModeOnMidiMsg = ???
}

object PolyModeOnMidiMsg {
  val Number: Int = 0
}
```

- [ ] **Step 3: Run the tests to verify they fail for the right reason**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.message.MidiMsgTest -- -oNCXEHLOPQRMWS"`
Expected: compiles; the new cases FAIL on assertions (`120 was not equal to 0`, `NotImplementedError` from
`mapChannel`), **not** on compile errors. The `typeChecks` case already passes — that is fine, it is a compile-time
guard, not a behaviour under construction.

- [ ] **Step 4: Write the real implementation**

Replace the stub section in `MidiMsg.scala` with the final code, ScalaDoc included:

```scala
// ============================================================================
// Channel Mode Messages
// ============================================================================

/**
 * Base class of the eight MIDI 1.0 Channel Mode messages.
 *
 * MIDI 1.0 puts them on the wire with the Control Change status byte (`0xBn`) and controller numbers 120-127, but
 * defines them as a category of their own: they are not controllers, and a receiver must not treat them as such.
 * They are therefore modelled as their own types rather than as [[CcMidiMsg]] values, and `CcMidiMsg` refuses their
 * numbers; see [[MidiRequirements.requireControllerNumber]].
 *
 * Only Local Control and Mono Mode On give their data byte a meaning, so only those two carry a field. The other six
 * send `0` and ignore whatever arrived: this is the one place where the byte-level round trip is deliberately not
 * preserved, the message's meaning being what the model keeps.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
sealed abstract class ChannelModeMidiMsg(channel: Int) extends ChannelMidiMsg(channel) {
  override def mapChannel(map: Int => Int): ChannelModeMidiMsg
}

/** Companion object for [[ChannelModeMidiMsg]]. */
object ChannelModeMidiMsg {
  /**
   * The controller numbers MIDI 1.0 reserves for the Channel Mode messages: 120 to 127, the range immediately above
   * [[MidiRequirements.MaxControllerNumber]], where the Control Change controller numbers stop.
   */
  val NumberRange: Range = (MidiRequirements.MaxControllerNumber + 1) to 127
}

/**
 * Represents an All Sound Off Channel Mode message (number 120), which silences the channel's voices immediately,
 * ignoring their release phase.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
case class AllSoundOffMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): AllSoundOffMidiMsg = copy(channel = map(channel))
}

/** Companion object for [[AllSoundOffMidiMsg]]. */
object AllSoundOffMidiMsg {
  /** The Channel Mode message number of All Sound Off (120). */
  val Number: Int = 120
}

/**
 * Represents a Reset All Controllers Channel Mode message (number 121), which returns the channel's controllers to
 * their default values.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
case class ResetAllControllersMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): ResetAllControllersMidiMsg = copy(channel = map(channel))
}

/** Companion object for [[ResetAllControllersMidiMsg]]. */
object ResetAllControllersMidiMsg {
  /** The Channel Mode message number of Reset All Controllers (121). */
  val Number: Int = 121
}

/**
 * Represents a Local Control Channel Mode message (number 122), which connects or disconnects a receiver's own
 * keyboard from its sound generator.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param isOn    Whether local control is switched on.
 */
case class LocalControlMidiMsg(override val channel: Int, isOn: Boolean) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): LocalControlMidiMsg = copy(channel = map(channel))
}

/** Companion object for [[LocalControlMidiMsg]]. */
object LocalControlMidiMsg {
  /** The Channel Mode message number of Local Control (122). */
  val Number: Int = 122

  /** The data byte that switches local control off (`0`). */
  val OffValue: Int = 0

  /** The data byte that switches local control on (`127`). */
  val OnValue: Int = 127

  /**
   * The lowest data byte that reads as on (`64`). MIDI 1.0 defines only [[OffValue]] and [[OnValue]] for this
   * message, so any other value follows the specification's general switch-controller convention: 0-63 off,
   * 64-127 on.
   */
  val OnThreshold: Int = 64
}

/**
 * Represents an All Notes Off Channel Mode message (number 123), which releases the channel's notes as if each had
 * received its own Note Off.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
case class AllNotesOffMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): AllNotesOffMidiMsg = copy(channel = map(channel))
}

/** Companion object for [[AllNotesOffMidiMsg]]. */
object AllNotesOffMidiMsg {
  /** The Channel Mode message number of All Notes Off (123). */
  val Number: Int = 123
}

/**
 * Represents an Omni Mode Off Channel Mode message (number 124), which restricts the receiver to the channels it is
 * assigned to.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
case class OmniModeOffMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): OmniModeOffMidiMsg = copy(channel = map(channel))
}

/** Companion object for [[OmniModeOffMidiMsg]]. */
object OmniModeOffMidiMsg {
  /** The Channel Mode message number of Omni Mode Off (124). */
  val Number: Int = 124
}

/**
 * Represents an Omni Mode On Channel Mode message (number 125), which makes the receiver recognise Channel Voice
 * messages on every channel.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
case class OmniModeOnMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): OmniModeOnMidiMsg = copy(channel = map(channel))
}

/** Companion object for [[OmniModeOnMidiMsg]]. */
object OmniModeOnMidiMsg {
  /** The Channel Mode message number of Omni Mode On (125). */
  val Number: Int = 125
}

/**
 * Represents a Mono Mode On Channel Mode message (number 126), which makes the receiver monophonic, one voice per
 * channel.
 *
 * @param channel      The 0-indexed MIDI channel (0-15).
 * @param channelCount The number of channels the receiver is asked to use, `0` meaning as many as it has voices.
 */
case class MonoModeOnMidiMsg(override val channel: Int, channelCount: Int) extends ChannelModeMidiMsg(channel) {

  import MonoModeOnMidiMsg.ChannelCountRange

  require(ChannelCountRange.contains(channelCount),
    s"channelCount must be between ${ChannelCountRange.start} and ${ChannelCountRange.end}; got $channelCount")

  override def mapChannel(map: Int => Int): MonoModeOnMidiMsg = copy(channel = map(channel))
}

/** Companion object for [[MonoModeOnMidiMsg]]. */
object MonoModeOnMidiMsg {
  /** The Channel Mode message number of Mono Mode On (126). */
  val Number: Int = 126

  /**
   * The channel counts the message may carry: 0 to 16. `0` asks the receiver to use as many channels as it has
   * voices; any other value is the exact number of channels.
   */
  val ChannelCountRange: Range = 0 to 16
}

/**
 * Represents a Poly Mode On Channel Mode message (number 127), which returns the receiver to full polyphony on a
 * single channel.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
case class PolyModeOnMidiMsg(override val channel: Int) extends ChannelModeMidiMsg(channel) {
  override def mapChannel(map: Int => Int): PolyModeOnMidiMsg = copy(channel = map(channel))
}

/** Companion object for [[PolyModeOnMidiMsg]]. */
object PolyModeOnMidiMsg {
  /** The Channel Mode message number of Poly Mode On (127). */
  val Number: Int = 127
}
```

And give `MidiRequirements.MaxControllerNumber` its final ScalaDoc:

```scala
  /**
   * The highest MIDI 1.0 Control Change controller number (119). Numbers 120-127 are the Channel Mode messages,
   * modelled by [[ChannelModeMidiMsg]] and its subtypes.
   */
  val MaxControllerNumber: Int = 119
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.message.MidiMsgTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 6: Run the whole `sc-midi` module**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS. Nothing else references the new types yet.

- [ ] **Step 7: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiRequirements.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala
git commit -m "[#278/#285] Add the eight ChannelModeMidiMsg types under ChannelMidiMsg"
```

---

## Task 2: Java Sound conversion for the Channel Mode messages

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala` (the `ToJavaMap`
  `CcMidiMsg` entry is at line 136; the `FromShortMap` `CONTROL_CHANGE` entry at lines 244–246; helpers at the end)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala`

**Interfaces:**
- Consumes: everything Task 1 produced; `ShortMessage.CONTROL_CHANGE`, the private `entry`, `toUnsupported` and
  `FromShortMap` machinery of `JavaMidiConverters`.
- Produces: `asScala` maps a `CONTROL_CHANGE` with controller number in `ChannelModeMidiMsg.NumberRange` to the
  matching Channel Mode type (a Mono Mode On above 16 channels to `UnsupportedMidiMsg`); `asJava` renders each of the
  eight types as `0xBn`, its number, its data byte. No new public members.

- [ ] **Step 1: Write the failing tests**

In `JavaMidiConvertersTest.scala`, add ten rows to the `cases` table, immediately after the Channel Voice rows (after
the four `PitchBendMidiMsg` rows, before the `// System Common` comment):

```scala
    // Channel Mode
    (AllSoundOffMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 120, 0)),
    (ResetAllControllersMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 121, 0)),
    (LocalControlMidiMsg(4, isOn = false), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 122, 0)),
    (LocalControlMidiMsg(4, isOn = true), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 122, 127)),
    (AllNotesOffMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 123, 0)),
    (OmniModeOffMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 124, 0)),
    (OmniModeOnMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 125, 0)),
    (MonoModeOnMidiMsg(4, channelCount = 4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 126, 4)),
    (MonoModeOnMidiMsg(4, channelCount = 0), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 126, 0)),
    (PolyModeOnMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 127, 0)),
```

These rows are exercised in both directions by the existing `asJava` and `asScala` table tests. Then add a new
behaviour section **after** `behavior of "JavaMidiConverters.asScala"`'s last case (the null-message case, which ends
just before `behavior of "UnsupportedMidiMsg round-trip"` at line 154):

```scala
  behavior of "JavaMidiConverters Channel Mode messages"

  it should "decode a valueless Channel Mode message whatever data byte it carries" in {
    // Given
    val dataBytes = Table("dataByte", 0, 1, 64, 127)

    forAll(dataBytes) { dataByte =>
      // When / Then
      shortMsgC(ShortMessage.CONTROL_CHANGE, 2, AllSoundOffMidiMsg.Number, dataByte).asScala shouldEqual
        AllSoundOffMidiMsg(2)
      shortMsgC(ShortMessage.CONTROL_CHANGE, 2, PolyModeOnMidiMsg.Number, dataByte).asScala shouldEqual
        PolyModeOnMidiMsg(2)
    }
  }

  it should "decode Local Control by the switch convention: 0-63 off, 64-127 on" in {
    // Given
    val dataBytes = Table(
      ("dataByte", "isOn"),
      (0, false),
      (63, false),
      (64, true),
      (127, true)
    )

    forAll(dataBytes) { (dataByte, isOn) =>
      // When / Then
      shortMsgC(ShortMessage.CONTROL_CHANGE, 2, LocalControlMidiMsg.Number, dataByte).asScala shouldEqual
        LocalControlMidiMsg(2, isOn)
    }
  }

  it should "keep a Control Change below the Channel Mode range a CcMidiMsg" in {
    // When / Then
    shortMsgC(ShortMessage.CONTROL_CHANGE, 2, MidiRequirements.MaxControllerNumber, 100).asScala shouldEqual
      CcMidiMsg(2, MidiRequirements.MaxControllerNumber, 100)
  }

  it should "wrap a Mono Mode On carrying more channels than MIDI 1.0 allows in an UnsupportedMidiMsg" in {
    // Given
    val javaMessage = shortMsgC(ShortMessage.CONTROL_CHANGE, 2, MonoModeOnMidiMsg.Number, 17)

    // When
    val actual = javaMessage.asScala

    // Then — asScala runs on the device's own thread, so a malformed message must not throw out of it
    actual shouldEqual UnsupportedMidiMsg(ArraySeq.unsafeWrapArray(javaMessage.getMessage))
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL. The `asJava` table test fails with
`IllegalStateException: No Java MIDI message builder registered for class …AllSoundOffMidiMsg`; the `asScala` cases
fail with `CcMidiMsg(4,120,0) was not equal to AllSoundOffMidiMsg(4)`.

- [ ] **Step 3: Write the implementation**

In `JavaMidiConverters.scala`, add the eight outbound entries to `ToJavaMap` immediately after the `CcMidiMsg` entry
(line 136–138):

```scala
    entry(classOf[AllSoundOffMidiMsg]) { m => channelModeMessage(m.channel, AllSoundOffMidiMsg.Number) },
    entry(classOf[ResetAllControllersMidiMsg]) { m =>
      channelModeMessage(m.channel, ResetAllControllersMidiMsg.Number)
    },
    entry(classOf[LocalControlMidiMsg]) { m =>
      val dataByte = if (m.isOn) LocalControlMidiMsg.OnValue else LocalControlMidiMsg.OffValue
      channelModeMessage(m.channel, LocalControlMidiMsg.Number, dataByte)
    },
    entry(classOf[AllNotesOffMidiMsg]) { m => channelModeMessage(m.channel, AllNotesOffMidiMsg.Number) },
    entry(classOf[OmniModeOffMidiMsg]) { m => channelModeMessage(m.channel, OmniModeOffMidiMsg.Number) },
    entry(classOf[OmniModeOnMidiMsg]) { m => channelModeMessage(m.channel, OmniModeOnMidiMsg.Number) },
    entry(classOf[MonoModeOnMidiMsg]) { m =>
      channelModeMessage(m.channel, MonoModeOnMidiMsg.Number, m.channelCount)
    },
    entry(classOf[PolyModeOnMidiMsg]) { m => channelModeMessage(m.channel, PolyModeOnMidiMsg.Number) },
```

Replace the inbound `CONTROL_CHANGE` entry (lines 244–246) with:

```scala
    ShortMessage.CONTROL_CHANGE -> { s => fromControlChange(s) },
```

Add the two private helpers next to `toUnsupported`, in the "Helpers" section:

```scala
  /**
   * The message a Control Change status byte carries: an ordinary [[CcMidiMsg]] below
   * [[ChannelModeMidiMsg.NumberRange]], and the matching [[ChannelModeMidiMsg]] subtype inside it.
   *
   * A Mono Mode On asking for more channels than MIDI 1.0 allows is malformed; it is wrapped in an
   * [[UnsupportedMidiMsg]] rather than rejected, because `asScala` runs on the device's own thread, where a thrown
   * exception would take the inbound stream down with it.
   */
  private def fromControlChange(shortMessage: ShortMessage): MidiMsg = {
    val channel = shortMessage.getChannel
    val number = shortMessage.getData1
    val dataByte = shortMessage.getData2

    number match {
      case AllSoundOffMidiMsg.Number => AllSoundOffMidiMsg(channel)
      case ResetAllControllersMidiMsg.Number => ResetAllControllersMidiMsg(channel)
      case LocalControlMidiMsg.Number => LocalControlMidiMsg(channel, dataByte >= LocalControlMidiMsg.OnThreshold)
      case AllNotesOffMidiMsg.Number => AllNotesOffMidiMsg(channel)
      case OmniModeOffMidiMsg.Number => OmniModeOffMidiMsg(channel)
      case OmniModeOnMidiMsg.Number => OmniModeOnMidiMsg(channel)
      case MonoModeOnMidiMsg.Number if MonoModeOnMidiMsg.ChannelCountRange.contains(dataByte) =>
        MonoModeOnMidiMsg(channel, dataByte)
      case MonoModeOnMidiMsg.Number => toUnsupported(shortMessage)
      case PolyModeOnMidiMsg.Number => PolyModeOnMidiMsg(channel)
      case _ => CcMidiMsg(channel, number, dataByte)
    }
  }

  /** Renders a Channel Mode message: the Control Change status byte, the message's number, and its data byte. */
  private def channelModeMessage(channel: Int, number: Int, dataByte: Int = 0): ShortMessage =
    new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number, dataByte)
```

Finally, update the object's ScalaDoc sentence about dispatch (line 42–44 area, "Both directions dispatch through
lookup tables …") by appending:

```
 * A Control Change is the one status byte both tables split further: its controller number decides between a
 * [[CcMidiMsg]] and a [[ChannelModeMidiMsg]] subtype.
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Run the whole `sc-midi` module**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS. `MidiChannelStateTrackerTest` still sends `CcMidiMsg(_, MidiCc.AllSoundOff, 0)` values it builds
itself — it never goes through the converters, so it is unaffected.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala
git commit -m "[#278/#285] Convert Channel Mode messages at the Java Sound boundary"
```

---

## Task 3: `MidiChannelStateTracker` handles Channel Mode messages by type

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiChannelStateTracker.scala` (the `send` match at
  lines 76–92, `handleChannelModeCc` at lines 403–415, the class ScalaDoc at lines 39–48)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiChannelStateTrackerTest.scala` (the
  `behavior of "MidiChannelStateTracker Channel Mode messages"` section, lines 1349–1619)

**Interfaces:**
- Consumes: the Task 1 types; the tracker's existing `ChannelState`, `ResetAllControllersCcNumbers`,
  `shallRespondToResetMessages` and `PartialRpnSelector`.
- Produces: no signature change. Behaviour change — `ccOption(channel, n)` is `None` for every `n` in
  `ChannelModeMidiMsg.NumberRange`, whatever arrived.

- [ ] **Step 1: Rewrite the existing tests onto the new types (red)**

In `MidiChannelStateTrackerTest.scala`, in the `behavior of "MidiChannelStateTracker Channel Mode messages"` section,
replace every `tracker.send(CcMidiMsg(<ch>, MidiCc.<X>, value = 0))` with the corresponding typed message:

| Was | Becomes |
|---|---|
| `CcMidiMsg(Channel, MidiCc.AllSoundOff, value = 0)` | `AllSoundOffMidiMsg(Channel)` |
| `CcMidiMsg(Channel, MidiCc.AllNotesOff, value = 0)` | `AllNotesOffMidiMsg(Channel)` |
| `CcMidiMsg(Channel, MidiCc.ResetAllControllers, value = 0)` | `ResetAllControllersMidiMsg(Channel)` |

There are 18 such sends, at lines 1358, 1371, 1385, 1399, 1418, 1440, 1457, 1476, 1489, 1506, 1525, 1547, 1563, 1578,
1593 and 1609. Nothing else in those tests changes: the assertions stay exactly as they are.

Then append two new cases at the end of the same section, after
`it should "not clear controller state on Reset All Controllers by default"`:

```scala
  it should "not record a Channel Mode message as a Control Change value" in new ResettableTrackerFixture {
    // When
    tracker.send(AllSoundOffMidiMsg(Channel))
    tracker.send(ResetAllControllersMidiMsg(Channel))
    tracker.send(LocalControlMidiMsg(Channel, isOn = false))
    tracker.send(AllNotesOffMidiMsg(Channel))
    tracker.send(OmniModeOffMidiMsg(Channel))
    tracker.send(OmniModeOnMidiMsg(Channel))
    tracker.send(MonoModeOnMidiMsg(Channel, channelCount = 4))
    tracker.send(PolyModeOnMidiMsg(Channel))

    // Then — Channel Mode messages are not controllers, so none of their numbers holds a CC value
    ChannelModeMidiMsg.NumberRange.foreach { number =>
      tracker.ccOption(Channel, number) shouldBe None
    }
  }

  it should "leave tracked state untouched for the Channel Mode messages that are not resets" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
      tracker.send(CcMidiMsg(Channel, MidiCc.ModulationMsb, value = 64))

      // When
      tracker.send(LocalControlMidiMsg(Channel, isOn = false))
      tracker.send(OmniModeOffMidiMsg(Channel))
      tracker.send(OmniModeOnMidiMsg(Channel))
      tracker.send(MonoModeOnMidiMsg(Channel, channelCount = 4))
      tracker.send(PolyModeOnMidiMsg(Channel))

      // Then
      tracker.activeNotes(Channel) should contain only C4
      tracker.ccOption(Channel, MidiCc.ModulationMsb) should equal(Some(64))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL. The tracker's `send` has no arm for `ChannelModeMidiMsg`, so it falls through to `case _ =>` and
does nothing: `Set() was not equal to Set()`-style failures on the active-note cases, and
`Some(0) was not None`-style failures do **not** appear yet (nothing is recorded either) — the failures to expect are
the `ResettableTrackerFixture` cases asserting cleared state, e.g. "cancel active notes on the channel when All Sound
Off is received".

- [ ] **Step 3: Write the implementation**

In `MidiChannelStateTracker.scala`, in `send`, drop the `handleChannelModeCc(state, ccNumber)` call from the
`CcMidiMsg` arm and add an arm of its own. The `CcMidiMsg` arm becomes:

```scala
    case CcMidiMsg(channel, ccNumber, ccValue) =>
      val state = channelStates(channel)
      state.ccValues(ccNumber) = ccValue
      handleParameterCc(state, ccNumber, ccValue)
    case message: ChannelModeMidiMsg =>
      handleChannelMode(channelStates(message.channel), message)
```

Replace `handleChannelModeCc` (lines 403–415) with:

```scala
  private def handleChannelMode(state: ChannelState, message: ChannelModeMidiMsg): Unit =
    if (shallRespondToResetMessages) message match {
      case _: AllSoundOffMidiMsg | _: AllNotesOffMidiMsg =>
        state.activeNotes.clear()
      case _: ResetAllControllersMidiMsg =>
        ResetAllControllersCcNumbers.foreach(state.ccValues.remove)
        state.activeNotes.valuesIterator.foreach(_.polyPressure = 0)
        state.channelPressure = None
        state.pitchBend = None
        state.partialRpnSelector = PartialRpnSelector.None
      case _ =>
    }
```

Update the class ScalaDoc. In the opening paragraph (line 22 area), after "Control Change values", add a sentence:

```
 * Channel Mode messages are not Control Changes and are never recorded as such: no
 * [[org.calinburloiu.music.scmidi.message.ChannelModeMidiMsg]] number appears in the tracked CC values.
```

And reword the `shallRespondToResetMessages` `@param` so it names the types rather than the numbers:

```
 * @param shallRespondToResetMessages whether the reset Channel Mode messages — All Sound Off, Reset All Controllers
 *                                    and All Notes Off — mutate the tracked state. Defaults to `false`, which
 *                                    leaves the state untouched. Set to `true` when the tracker models a receiver
 *                                    that is known to act on these messages. Independent of this flag, [[reset]]
 *                                    always clears everything.
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Run the whole `sc-midi` module**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiChannelStateTracker.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiChannelStateTrackerTest.scala
git commit -m "[#278/#285] Track Channel Mode messages by type, never as CC values"
```

---

## Task 4: The `tuner` module routes Channel Mode messages by type

`MpeMessageRouting` and `MpeTuner`'s tests move together: the moment `route` gains its `ChannelModeMidiMsg` arm,
every `MpeTunerTest` case that still builds a Channel Mode message as a `CcMidiMsg` takes the ordinary Zone-level
path instead of the MIDI-Mode discard. There is no ordering that keeps the `tuner` suite green in between, so this is
one task with one commit.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala` (`route` at lines
  122–139, `routeCc` at lines 163–182, `deselectsOnRelay` at lines 260–263)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` (the `interpret` fallback
  comment at lines 249–252 — a comment only; its dispatch does not change)
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

**Interfaces:**
- Consumes: the Task 1 types; `MpeChannelRole`, `MpeRoutingVerdict`, the private `routeZoneLevel`.
- Produces: `route` accepts every `ChannelModeMidiMsg` (its match stays exhaustive over the sealed `ChannelMidiMsg`);
  `deselectsOnRelay(msg)` is `true` for `ResetAllControllersMidiMsg` only. No signature changes. `MpeTuner` needs no
  production change: a Channel Mode message reaches it through the generic `ChannelMidiMsg` path (`route`, then
  `ForwardOn`/`Discard` and `deselectsOnRelay`), and `route` never returns `Interpret` for one.

- [ ] **Step 1: Move `MpeMessageRoutingTest` onto the new types and add a `deselectsOnRelay` block**

In `MpeMessageRoutingTest.scala`, replace the eight Channel Mode rows of the `route` table (lines 167–190) with:

```scala
      ("All Sound Off",
        AllSoundOffMidiMsg(inputChannel),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Reset All Controllers",
        ResetAllControllersMidiMsg(inputChannel),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Local Control",
        LocalControlMidiMsg(inputChannel, isOn = false),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("All Notes Off",
        AllNotesOffMidiMsg(inputChannel),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Omni Mode Off",
        OmniModeOffMidiMsg(inputChannel),
        Discard, Discard, Discard, Discard),
      ("Omni Mode On",
        OmniModeOnMidiMsg(inputChannel),
        Discard, Discard, Discard, Discard),
      ("Mono Mode On",
        MonoModeOnMidiMsg(inputChannel, channelCount = 1),
        Discard, Discard, Discard, Discard),
      ("Poly Mode On",
        PolyModeOnMidiMsg(inputChannel),
        Discard, Discard, Discard, Discard)
```

Add a behaviour block after the last `route` case, immediately before `behavior of "MpeMessageRouting.rpnSequence"`
(line 396):

```scala
  behavior of "MpeMessageRouting.deselectsOnRelay"

  it should "hold for Reset All Controllers alone" in {
    // When / Then
    MpeMessageRouting.deselectsOnRelay(ResetAllControllersMidiMsg(inputChannel)) shouldBe true
    MpeMessageRouting.deselectsOnRelay(AllSoundOffMidiMsg(inputChannel)) shouldBe false
    MpeMessageRouting.deselectsOnRelay(AllNotesOffMidiMsg(inputChannel)) shouldBe false
    MpeMessageRouting.deselectsOnRelay(LocalControlMidiMsg(inputChannel, isOn = true)) shouldBe false
    MpeMessageRouting.deselectsOnRelay(CcMidiMsg(inputChannel, MidiCc.SustainPedal, 127)) shouldBe false
    MpeMessageRouting.deselectsOnRelay(NoteOnMidiMsg(inputChannel, MidiNote.C4, 100)) shouldBe false
  }
```

- [ ] **Step 2: Move `MpeTunerTest` onto the new types**

In `MpeTunerTest.scala`, add an extractor next to `extractCc` (line 196):

```scala
  private def extractChannelModes(output: Seq[MidiMsg]): Seq[ChannelModeMidiMsg] =
    output.collect { case m: ChannelModeMidiMsg => m }
```

Then make these seven edits.

**(a)** In `it should "forward CCs on Master Channel"` (lines ~2247–2266), delete the four Channel Mode rows and the
comment above them, so the table ends at `("Soft Pedal", MidiCc.SoftPedal, 127)`. Add a case of its own right after
that test:

```scala
  it should "forward the Channel Mode messages that are not MIDI Mode messages on Master Channel" in new Fixture {
    // Given
    private val messages = Table(
      ("description", "message", "expected"),
      ("All Sound Off", AllSoundOffMidiMsg(nonMpeInputChannel), AllSoundOffMidiMsg(0)),
      ("Reset All Controllers", ResetAllControllersMidiMsg(nonMpeInputChannel), ResetAllControllersMidiMsg(0)),
      ("Local Control", LocalControlMidiMsg(nonMpeInputChannel, isOn = false), LocalControlMidiMsg(0, isOn = false)),
      ("All Notes Off", AllNotesOffMidiMsg(nonMpeInputChannel), AllNotesOffMidiMsg(0))
    )
    forAll(messages) { (_, message, expected) =>
      // When
      val output = tuner.process(message)

      // Then
      extractChannelModes(output) should contain(expected)
    }
  }
```

**(b)** In `it should "discard the MIDI Mode messages 124-127"` (lines ~2288–2295), rename and retype:

```scala
  it should "discard the MIDI Mode messages" in new Fixture {
    // Given
    private val messages = Table("message",
      OmniModeOffMidiMsg(nonMpeInputChannel),
      OmniModeOnMidiMsg(nonMpeInputChannel),
      MonoModeOnMidiMsg(nonMpeInputChannel, channelCount = 1),
      PolyModeOnMidiMsg(nonMpeInputChannel))
    forAll(messages) { message =>
      // When / Then
      tuner.process(message) shouldBe empty
    }
  }
```

**(c)** In `it should "discard every Channel Voice and Channel Mode message when no Zone is enabled"` (line 2300),
add one line inside the `forAll` body so the test's name becomes true:

```scala
      tuner.process(AllNotesOffMidiMsg(channel)) shouldBe empty
```

**(d)** In `it should "re-emit the selector after a forwarded Reset All Controllers"` (lines ~2444–2467), replace the
two Reset All Controllers lines:

```scala
    // When a Reset All Controllers is redirected onto that same Master Channel
    private val resetOutput = tuner.process(ResetAllControllersMidiMsg(nonMpeInputChannel))
    // Then it reaches the receiver, which deselects its parameter in response
    extractChannelModes(resetOutput) shouldEqual Seq(ResetAllControllersMidiMsg(0))
```

**(e)** In `it should "discard zone-level CCs received on a Member Channel"` (lines ~2557–2576), delete the
`("Reset All Controllers", MidiCc.ResetAllControllers, 0)` row and add a case of its own after that test:

```scala
  it should "discard Channel Mode messages received on a Member Channel" in new Fixture(tuner7MpeInput) {
    // Given
    private val messages = Table("message",
      AllSoundOffMidiMsg(mpeInputChannel),
      ResetAllControllersMidiMsg(mpeInputChannel),
      LocalControlMidiMsg(mpeInputChannel, isOn = false),
      AllNotesOffMidiMsg(mpeInputChannel))
    forAll(messages) { message =>
      // When / Then
      tuner.process(message) shouldBe empty
    }
  }
```

**(f)** In `it should "discard the MIDI Mode messages 124-127 at every level"` (lines ~2775–2789), retype:

```scala
  it should "discard the MIDI Mode messages at every level" in new Fixture(tuner7MpeInput) {
    // Given
    private val channels = Table("channel", 0, mpeInputChannel, 10)
    forAll(channels) { channel =>
      // When / Then
      tuner.process(OmniModeOffMidiMsg(channel)) shouldBe empty
      tuner.process(OmniModeOnMidiMsg(channel)) shouldBe empty
      tuner.process(MonoModeOnMidiMsg(channel, channelCount = 1)) shouldBe empty
      tuner.process(PolyModeOnMidiMsg(channel)) shouldBe empty
    }
  }
```

**(g)** In `it should "still forward the Channel Mode messages 120-123 received on a Master Channel"` (lines
~2791–2802), retype:

```scala
  it should "still forward the Channel Mode messages that are not MIDI Mode messages on a Master Channel" in
    new Fixture(tuner7MpeInput) {
      // Given
      private val messages = Table("message",
        AllSoundOffMidiMsg(0),
        ResetAllControllersMidiMsg(0),
        LocalControlMidiMsg(0, isOn = false),
        AllNotesOffMidiMsg(0))
      forAll(messages) { message =>
        // When
        val output = tuner.process(message)

        // Then
        extractChannelModes(output) shouldEqual Seq(message)
      }
    }
```

- [ ] **Step 3: Run both test classes to verify they fail for the right reason**

Run:

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeMessageRoutingTest org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
```

Expected: the module compiles with a **non-exhaustive match warning** on `MpeMessageRouting.route`, and the run FAILS
with `scala.MatchError: AllSoundOffMidiMsg(3)` raised from `route` for every migrated case, plus
`false was not true` on the Reset All Controllers case of `deselectsOnRelay`.

- [ ] **Step 4: Write the implementation**

In `MpeMessageRouting.scala`, add an arm to `route` right after the `CcMidiMsg` arm (line 125):

```scala
    case msg: ChannelModeMidiMsg => routeChannelMode(role, msg)
```

Add `routeChannelMode` immediately after `routeCc`:

```scala
  /**
   * Routes a Channel Mode message.
   *
   * The four MIDI Mode messages — Omni Mode Off and On, Mono Mode On and Poly Mode On — are discarded at every role
   * in both input modes: the Tuner is fixed-mode on both sides, and a Mono Mode On reaching an output Member Channel
   * would turn every shared allocation into a note drop. The other four — All Sound Off, Reset All Controllers,
   * Local Control and All Notes Off — are ordinary Zone-level traffic.
   */
  private def routeChannelMode(role: MpeChannelRole, msg: ChannelModeMidiMsg): MpeRoutingVerdict = msg match {
    case _: OmniModeOffMidiMsg | _: OmniModeOnMidiMsg | _: MonoModeOnMidiMsg | _: PolyModeOnMidiMsg =>
      MpeRoutingVerdict.Discard
    case _ => routeZoneLevel(role)
  }
```

Delete the first arm of `routeCc` (lines 166–169: the `MidiCc.OmniModeOff | … | MidiCc.PolyModeOn` case together
with the two comment lines above it) — `routeChannelMode` now carries that rule and its rationale.

Rewrite `deselectsOnRelay`'s body (line 261):

```scala
  private[tuner] def deselectsOnRelay(msg: ChannelMidiMsg): Boolean = msg match {
    case _: ResetAllControllersMidiMsg => true
    case _ => false
  }
```

Its ScalaDoc already names the messages rather than the numbers, so it stays as it is.

In `MpeTuner.scala`, the `interpret` fallback (lines 249–252) claims Program Change is "the only other concrete
channel message class". Replace its comment:

```scala
    case m =>
      // `route` never asks for a Program Change or a Channel Mode message — the only other concrete channel
      // message classes — to be interpreted; they are forwarded or discarded.
      logger.error(s"Unexpected request to interpret $m")
```

- [ ] **Step 5: Run both test classes to verify they pass**

Run:

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeMessageRoutingTest org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
```

Expected: PASS, and no non-exhaustive match warning on `route`.

- [ ] **Step 6: Run the whole `tuner` module**

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#278/#285] Route Channel Mode messages by type in the MPE Tuner"
```

---

## Task 5: Restrict `CcMidiMsg` to controller numbers 0–119

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiRequirements.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala` (`CcMidiMsg`, lines 168–181)
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiCc.scala` (delete lines 39–54)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/PedalTuningChanger.scala` (class ScalaDoc)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala`

**Interfaces:**
- Consumes: `ChannelModeMidiMsg.NumberRange`, `MidiRequirements.MaxControllerNumber`.
- Produces: `MidiRequirements.requireControllerNumber(number: Int): Unit`; `CcMidiMsg(ch, n, v)` throws
  `IllegalArgumentException` for `n` outside 0–119. `MidiCc` no longer has `AllSoundOff`, `ResetAllControllers`,
  `AllNotesOff`, `LocalControl`, `OmniModeOff`, `OmniModeOn`, `MonoModeOn`, `PolyModeOn`.

- [ ] **Step 1: Write the failing test**

In `MidiMsgTest.scala`, add two cases to the existing `behavior of "CcMidiMsg"` section (after "preserve the concrete
subtype when mapping the channel", around line 129):

```scala
  it should "accept every controller number MIDI 1.0 defines" in {
    // Given
    val numbers = Table("number", 0, 1, 64, MidiRequirements.MaxControllerNumber)

    forAll(numbers) { number =>
      // When / Then
      CcMidiMsg(0, number, 0).number shouldEqual number
    }
  }

  it should "reject the Channel Mode numbers and anything outside 0 to 119" in {
    // Given
    val numbers = Table("number", ChannelModeMidiMsg.NumberRange.toSeq*)

    forAll(numbers) { number =>
      // When / Then
      an[IllegalArgumentException] should be thrownBy CcMidiMsg(0, number, 0)
    }
    an[IllegalArgumentException] should be thrownBy CcMidiMsg(0, -1, 0)
    an[IllegalArgumentException] should be thrownBy CcMidiMsg(0, 128, 0)
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.message.MidiMsgTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL with `Expected exception java.lang.IllegalArgumentException to be thrown, but no exception was thrown`
for numbers 120–127.

- [ ] **Step 3: Write the implementation**

In `MidiRequirements.scala`, add after `requireUnsigned7BitValue` (line 49–50):

```scala
  /**
   * Requires that the given Control Change controller number is between 0 and [[MaxControllerNumber]]. Numbers
   * 120-127 are Channel Mode messages, which have their own types under
   * [[org.calinburloiu.music.scmidi.message.ChannelModeMidiMsg]] and are not controllers.
   */
  def requireControllerNumber(number: Int): Unit =
    require(number >= 0 && number <= MaxControllerNumber,
      s"number must be between 0 and $MaxControllerNumber; got $number")
```

In `MidiMsg.scala`, change `CcMidiMsg`'s validation (line 176) from `requireUnsigned7BitValue("number", number)` to:

```scala
  MidiRequirements.requireControllerNumber(number)
```

and extend its ScalaDoc (lines 168–174) with:

```
 * The controller number stops at [[MidiRequirements.MaxControllerNumber]]: MIDI 1.0 reserves 120-127 for the Channel
 * Mode messages, which are [[ChannelModeMidiMsg]] subtypes rather than Control Changes.
```

In `MidiCc.scala`, delete lines 39–54 — the eight Channel Mode constants and their ScalaDoc, from
`/** All Sound Off controller number (#120). */` through `val PolyModeOn: Int = 127` — leaving the blank line before
`/** Bank Select MSB controller number (#0). */`. Extend the object's ScalaDoc:

```scala
/**
 * Constants for MIDI Control Change (CC) controller numbers.
 *
 * The numbers stop at [[MidiRequirements.MaxControllerNumber]]: MIDI 1.0 reserves 120-127 for the Channel Mode
 * messages, whose numbers live in the companions of the [[ChannelModeMidiMsg]] subtypes.
 */
```

In `PedalTuningChanger.scala`, append a paragraph to the class ScalaDoc (after the paragraph ending "...trigger the
change again."):

```
 * Only Control Change numbers 0-119 can trigger a change. MIDI 1.0 reserves 120-127 for the Channel Mode messages,
 * which arrive as [[org.calinburloiu.music.scmidi.message.ChannelModeMidiMsg]] values rather than
 * [[org.calinburloiu.music.scmidi.message.CcMidiMsg]] ones, so a trigger configured on one of those numbers would
 * never fire. The JSON format rejects them when a composition is read, so only a programmatically constructed
 * instance can hold one.
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.message.MidiMsgTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Compile everything, to prove no consumer still names a deleted constant**

Run: `mcp__metals__compile-full` (or `sbtn "root/Test/compile"` if the Metals MCP is unavailable).
Expected: compiles with no errors. A `value AllSoundOff is not a member of object MidiCc` error means a consumer was
missed in Tasks 3–4 — fix it there rather than restoring the constant.

- [ ] **Step 6: Run the full suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiRequirements.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiCc.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/PedalTuningChanger.scala
git commit -m "[#278/#285] Restrict CcMidiMsg to controller numbers 0-119"
```

---

## Task 6: Reject the Channel Mode numbers as pedal triggers in the JSON format and schemas

A composition may configure a `PedalTuningChanger` trigger on any controller number 0–127. After Task 5 the numbers
120–127 can no longer reach `PedalTuningChanger.decide` as a `CcMidiMsg` at all, so such a trigger would load
successfully and then silently never fire. The format rejects it at load time instead, with an error naming the
constraint, and the hand-maintained JSON Schemas say the same thing to an editor.

`uint7Format` and `uint7.schema.json` are shared with the Pitch Bend Sensitivity fields of the Tuner plugin
(`JsonCommonMidiFormat.scala:33-34`, `json-schemas/v1/track/tuner.schema.json:18,28`), where 0–127 is correct.
**Neither may be narrowed.** A controller-number counterpart is added beside each.

**Files:**
- Modify: `format/src/main/scala/org/calinburloiu/music/microtonalist/format/package.scala` (the error constants at
  lines 28–29; `uint7Format` at line 81)
- Modify: `format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonTuningChangerPluginFormat.scala`
  (the three `uint7Format` uses at lines 40, 48 and 49)
- Create: `json-schemas/v1/track/ccNumber.schema.json`
- Modify: `json-schemas/v1/track/tuningChanger.schema.json` (the three `{ "$ref": "uint7.schema.json" }` references
  under `pedalSettings`)
- Test: `format/src/test/scala/org/calinburloiu/music/microtonalist/format/FormatPackageObjectTest.scala`
- Test: `format/src/test/scala/org/calinburloiu/music/microtonalist/format/JsonTuningChangerPluginFormatTest.scala`

**Interfaces:**
- Consumes: `MidiRequirements.MaxControllerNumber` (Task 1). The `format` module has `sc-midi` on its compile
  classpath transitively through `tuner`, and `JsonTuningChangerPluginFormat` already imports from
  `org.calinburloiu.music.scmidi.message`.
- Produces: `format.JsonError_CcNumber: String = "error.expected.ccNumber"` and `format.ccNumberFormat: Format[Int]`
  in the package object; a `ccNumber.schema.json` sibling of `uint7.schema.json`.

**No license header** on `ccNumber.schema.json`: `addlicense` does not cover `.json`, and the existing schemas under
`json-schemas/` carry none. Do not add one by hand.

- [ ] **Step 1: Write the failing tests**

In `FormatPackageObjectTest.scala`, add a section after the `uint7Format` write case (it ends just before
`"resolveLibraryUrl" should …`):

```scala
  "ccNumberFormat" should "read a Control Change controller number (between 0 and 119)" in {
    ccNumberFormat.reads(JsNumber(0)) shouldEqual JsSuccess(0)
    ccNumberFormat.reads(JsNumber(64)) shouldEqual JsSuccess(64)
    ccNumberFormat.reads(JsNumber(119)) shouldEqual JsSuccess(119)
    // 120-127 are Channel Mode messages, not controllers
    ccNumberFormat.reads(JsNumber(120)) shouldEqual JsError("error.expected.ccNumber")
    ccNumberFormat.reads(JsNumber(127)) shouldEqual JsError("error.expected.ccNumber")
    ccNumberFormat.reads(JsNumber(128)) shouldEqual JsError("error.expected.ccNumber")
    ccNumberFormat.reads(JsNumber(-1)) shouldEqual JsError("error.expected.ccNumber")
  }

  it should "write an integer" in {
    ccNumberFormat.writes(0) shouldEqual JsNumber(0)
    ccNumberFormat.writes(119) shouldEqual JsNumber(119)
    // No validation on write
  }
```

In `JsonTuningChangerPluginFormatTest.scala`, replace the single `index` row of
`pedalTuningChangerFailureTable` (line ~57) with three rows covering all three trigger paths:

```scala
    (__ \ "triggers" \ "previous", DisallowedValues(JsNumber(-1), JsNumber(120), JsNumber(128)),
      "error.expected.ccNumber"),
    (__ \ "triggers" \ "next", DisallowedValues(JsNumber(-1), JsNumber(127), JsNumber(128)),
      "error.expected.ccNumber"),
    (__ \ "triggers" \ "index" \ "2", DisallowedValues(JsNumber(-1), JsNumber(120), JsNumber(128)),
      "error.expected.ccNumber"),
```

The baseline `pedalTuningChangerJson` uses 100, 101, 10 and 20, all valid controller numbers, so it still
deserializes and the table's precondition still holds.

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
sbtn "format/testOnly org.calinburloiu.music.microtonalist.format.FormatPackageObjectTest org.calinburloiu.music.microtonalist.format.JsonTuningChangerPluginFormatTest -- -oNCXEHLOPQRMWS"
```

Expected: `FormatPackageObjectTest` FAILS to compile (`Not found: ccNumberFormat`). Add the stub below to get past
that, then re-run and expect assertion failures: `JsSuccess(120,) was not equal to JsError(error.expected.ccNumber)`
and, in `JsonTuningChangerPluginFormatTest`, the failure table reporting that `120` was accepted at
`triggers/previous`.

Stub for the package object, so the tests compile:

```scala
  lazy val ccNumberFormat: Format[Int] = uint7Format
```

- [ ] **Step 3: Write the implementation**

In `format/package.scala`, add the import:

```scala
import org.calinburloiu.music.scmidi.message.MidiRequirements
```

Add the error constant beside the existing two (lines 28–29):

```scala
  val JsonError_CcNumber: String = "error.expected.ccNumber"
```

Replace the stub with the real format, next to `uint7Format` (line 81):

```scala
  /**
   * Format for a MIDI Control Change controller number, between 0 and
   * [[org.calinburloiu.music.scmidi.message.MidiRequirements.MaxControllerNumber]] (119). MIDI 1.0 reserves 120-127
   * for the Channel Mode messages, which are not controllers, so they are rejected. Do not use it for other 7-bit
   * MIDI values — [[uint7Format]] covers the full 0-127 range.
   */
  lazy val ccNumberFormat: Format[Int] = {
    val reads = __.read[Int](min(0) keepAnd max(MidiRequirements.MaxControllerNumber)) orElse
      Reads.failed(JsonError_CcNumber)
    Format(reads, Writes.IntWrites)
  }
```

In `JsonTuningChangerPluginFormat.scala`, switch the three trigger fields from `uint7Format` to `ccNumberFormat`:

```scala
  private val indexTriggersReads: Reads[Map[Int, Int]] =
    Reads.mapReads[Int, CcNumber](tuningIndexKeyReads)(ccNumberFormat)
```

```scala
    (__ \ "previous").formatNullable[CcNumber](ccNumberFormat) and
    (__ \ "next").formatNullable[CcNumber](ccNumberFormat) and
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```bash
sbtn "format/testOnly org.calinburloiu.music.microtonalist.format.FormatPackageObjectTest org.calinburloiu.music.microtonalist.format.JsonTuningChangerPluginFormatTest -- -oNCXEHLOPQRMWS"
```

Expected: PASS.

- [ ] **Step 5: Add the JSON Schema**

Create `json-schemas/v1/track/ccNumber.schema.json`, mirroring `uint7.schema.json`'s shape (4-space indent, a
`$comment` rather than a title):

```json
{
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$comment": "A MIDI Control Change controller number, between 0 and 119, inclusive. MIDI 1.0 reserves the numbers 120 to 127 for the Channel Mode messages, which are not controllers.",
    "type": "integer",
    "minimum": 0,
    "maximum": 119
}
```

In `json-schemas/v1/track/tuningChanger.schema.json`, under `$defs/pedalSettings`, change all three
`{ "$ref": "uint7.schema.json" }` references — the ones inside `triggers/properties/previous`,
`triggers/properties/next`, and `triggers/properties/index/patternProperties/^[0-9]+$` — to:

```json
                                { "$ref": "ccNumber.schema.json" }
```

(keep each one's existing indentation). Leave `uint7.schema.json` itself untouched: `tuner.schema.json` still uses it
for `semitoneCount` and `centCount`, where 0–127 is correct.

Extend the three trigger descriptions in the same file so an editor sees the reason, appending to each:

- `previous`: `"Configures the MIDI CC that triggers a tuning change to the previous tuning. Only controller numbers 0-119 may be used; 120-127 are MIDI Channel Mode messages, not controllers."`
- `next`: `"Configures the MIDI CC that triggers a tuning change to the next tuning. Only controller numbers 0-119 may be used; 120-127 are MIDI Channel Mode messages, not controllers."`
- the `index` pattern property: `"The value configures the MIDI CC that triggers a tuning change to the tuning index from the key. Only controller numbers 0-119 may be used; 120-127 are MIDI Channel Mode messages, not controllers."`

- [ ] **Step 6: Verify the schemas are still well-formed JSON**

Run:

```bash
python3 -c "import json,sys; [json.load(open(f)) for f in sys.argv[1:]]; print('valid JSON')" \
  json-schemas/v1/track/ccNumber.schema.json json-schemas/v1/track/tuningChanger.schema.json
grep -c 'uint7.schema.json' json-schemas/v1/track/tuningChanger.schema.json
```

Expected: `valid JSON`, and the `grep -c` reports `0` — every `uint7` reference under `pedalSettings` has moved.

- [ ] **Step 7: Run the whole `format` module**

Run: `sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS. `JsonCompositionFormatTest` and `JsonTrackFormatTest` load fixture JSON; if one of them configures a
pedal trigger on 120–127 it will now fail — fix the fixture, not the format.

- [ ] **Step 8: Commit**

```bash
git add format/src/main/scala/org/calinburloiu/music/microtonalist/format/package.scala \
        format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonTuningChangerPluginFormat.scala \
        format/src/test/scala/org/calinburloiu/music/microtonalist/format/FormatPackageObjectTest.scala \
        format/src/test/scala/org/calinburloiu/music/microtonalist/format/JsonTuningChangerPluginFormatTest.scala \
        json-schemas/v1/track/ccNumber.schema.json \
        json-schemas/v1/track/tuningChanger.schema.json
git commit -m "[#278/#285] Reject Channel Mode numbers as pedal triggers in the JSON format"
```

---

## Task 7: Documentation and final checks

**Files:**
- Modify: `docs/architecture/sc-midi/README.md`
- Modify: `docs/architecture/tuner/README.md`
- Modify: `docs/architecture/format/README.md`

**Interfaces:**
- Consumes: everything Tasks 1–6 produced.
- Produces: nothing in code.

- [ ] **Step 1: Update the `sc-midi` architecture document**

In the "MIDI message model (`message` sub-package)" section (lines 84–93), the sentence listing the sub-hierarchies
currently reads "Its sub-hierarchies cover channel voice/mode messages (`ChannelMidiMsg`, with a `mapChannel` that
rewrites the channel), …". Replace that clause with:

```
Its sub-hierarchies cover Channel Voice messages and, under `ChannelModeMidiMsg`, the eight Channel Mode messages
(both are `ChannelMidiMsg`s, with a `mapChannel` that rewrites the channel),
```

and append to the same paragraph:

```
MIDI 1.0 puts the Channel Mode messages on the wire as Control Changes with numbers 120–127, but defines them as a
category of their own, so the model does too: `AllSoundOffMidiMsg`, `ResetAllControllersMidiMsg`,
`LocalControlMidiMsg`, `AllNotesOffMidiMsg`, `OmniModeOffMidiMsg`, `OmniModeOnMidiMsg`, `MonoModeOnMidiMsg` and
`PolyModeOnMidiMsg` each own their number in their companion, `ChannelModeMidiMsg.NumberRange` spans them, and
`CcMidiMsg.number` is restricted to 0–119 by `MidiRequirements.requireControllerNumber`. Only Local Control and Mono
Mode On carry a field; the other six emit data byte `0` and ignore whatever arrived — the one place the model
deliberately drops the byte-level round trip.
```

In the `JavaMidiConverters` paragraph (lines 95–102), after "Both directions dispatch through lookup tables …", add:

```
A Control Change is the one status byte both tables split further, by controller number, into a `CcMidiMsg` and a
Channel Mode message; a Mono Mode On asking for more channels than MIDI 1.0 allows decodes to `UnsupportedMidiMsg`
rather than throwing on the device's own thread.
```

In the `MidiChannelStateTracker` bullet (lines 134–144), change "implementing the RPN/NRPN Data Entry protocol and
the relevant Channel Mode messages" to:

```
implementing the RPN/NRPN Data Entry protocol and, in a branch of its own over `ChannelModeMidiMsg`, the reset
Channel Mode messages (their numbers are never recorded as CC values)
```

In "Notes / subject to change", rewrite the last bullet (lines 226–232) so it no longer says #285 has no code:

```
- The `Sc` prefix is gone (#279), the `MidiTransmitter` family replaced `MultiTransmitter` (#280, #281), the
  pipeline carries `MidiMsg` end to end (#281), the device layer is a pair of traits with a Java Sound
  implementation under `javamidi` (#282), the MIDI 2.0 outlook that the empty `Midi2Msg` stands in for has been
  written (#283, `issues/00278-isolate-java-midi/2026-09-07-midi2-outlook.md`), and the Channel Mode messages are
  their own types (#285). Every #278 sub-issue nonetheless remains open until the branch stack that implements them
  merges — see `issues/00278-isolate-java-midi/`.
```

- [ ] **Step 2: Update the `tuner` architecture document**

In the `MpeMessageRouting.scala` bullet (lines 62–75), after the clause describing `route`, add:

```
The Channel Mode messages are routed by type rather than by controller number: the four MIDI Mode messages (Omni Mode
Off/On, Mono Mode On, Poly Mode On) are discarded at every role, the other four are ordinary Zone-level traffic.
```

In the "Tuning-change detection" paragraph (lines 83–89), after "`PedalTuningChanger` triggers on a pedal-like CC
crossing a threshold", add "(controller numbers 0–119 only — 120–127 are Channel Mode messages, and `format` rejects
them as triggers)".

- [ ] **Step 3: Update the `format` architecture document**

At the end of the "Plugin (de)serialization" section of `docs/architecture/format/README.md` (it closes at line 93
with "…keeping serialization concerns out of the domain modules."), add a paragraph:

```
The shared value formats in the `format` package object encode which MIDI range a field may take: `uint7Format` for a
full 7-bit value (0–127, e.g. Pitch Bend Sensitivity's semitone and cent counts) and `ccNumberFormat` for a Control
Change controller number (0–119, the `PedalTuningChanger` triggers), MIDI 1.0 reserving 120–127 for the Channel Mode
messages. The hand-maintained JSON Schemas under `json-schemas/v1/` mirror the pair as `uint7.schema.json` and
`ccNumber.schema.json`; they are not validated by any test, so they must be updated alongside the Play formats.
```

- [ ] **Step 4: Verify the MPE Tuner paper needs no edit**

Run: `grep -n "CC #12\|CC 120\|CC 121\|CC 122\|CC 123" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: no matches. The paper already uses the specification's vocabulary throughout — "Channel Mode message 121",
"MIDI Mode messages (124–127)", "All other Channel Mode messages — All Sound Off (120), Local Control (122), All
Notes Off (123)" — so its routing table needs no change. If the grep does match, reword those cells to name the
message rather than the CC number.

- [ ] **Step 5: Check coverage**

Invoke the `scoverage-inspector` skill and follow its policy. Inspect `MidiMsg.scala`, `MidiRequirements.scala`,
`JavaMidiConverters.scala`, `MidiChannelStateTracker.scala` and `MpeMessageRouting.scala`, and the `sc-midi` and
`tuner` module totals.

Also inspect `format/package.scala` and `JsonTuningChangerPluginFormat.scala` and the `format` module total.

Expected: `tuner` stays at or above 80%/80% and `format` at or above 66%/59%. `sc-midi` statement coverage stays at
or above 67%; its branch total is a **pre-existing** 51.22% against a floor of 52 on this stack — it must not go
below that baseline, and the new code should push it up rather than down. If a new branch is uncovered, add the test
rather than lowering the floor.

- [ ] **Step 6: Run the full suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 7: Verify nothing still refers to the deleted constants**

Run:

```bash
grep -rn "MidiCc.AllSoundOff\|MidiCc.ResetAllControllers\|MidiCc.AllNotesOff\|MidiCc.LocalControl\|MidiCc.OmniMode\|MidiCc.MonoModeOn\|MidiCc.PolyModeOn" --include='*.scala' .
grep -n "uint7Format" format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonTuningChangerPluginFormat.scala
grep -rn "uint7.schema.json" json-schemas/v1/track/tuningChanger.schema.json
```

Expected: no matches from any of the three. (`uint7Format` and `uint7.schema.json` still exist and are still used by
`JsonCommonMidiFormat` and `tuner.schema.json` — only the pedal triggers moved off them.)

- [ ] **Step 8: Commit**

```bash
git add docs/architecture/sc-midi/README.md docs/architecture/tuner/README.md \
        docs/architecture/format/README.md
git commit -m "[#278/#285] Document the Channel Mode message types in the architecture docs"
```

- [ ] **Step 9: Report and ask about the PR**

Report the final state: commits made, suite result, coverage numbers against the floors. Then ask the user whether to
open the PR (use the `contributing` skill; base it on `refactoring/282-midi-manager-traits`, draft, title prefix
`[#278/#285]`). Do not push without being asked.
