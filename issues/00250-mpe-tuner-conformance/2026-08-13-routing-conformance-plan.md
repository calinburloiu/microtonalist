# MPE Tuner Routing and Filtering Conformance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date**: 2026-08-13
- **Issue**: [#250](https://github.com/calinburloiu/microtonalist/issues/250) — "Make MPE Tuner MIDI message routing and
  filtering conform to the paper"
- **Base commit**: `32853d2cfd53e0a0b2e0e2ca73ae7e96c5ca9de7` (branch `feature/mpe-tuner-conformance`)
- **Design this plan implements**:
  [`2026-08-12-routing-conformance-design.md`](2026-08-12-routing-conformance-design.md)
- **Prompt the design answers**:
  [`2026-08-03-routing-conformance-prompt.md`](2026-08-03-routing-conformance-prompt.md)
- **Source of truth**: the MPE Tuner paper,
  [`docs/architecture/tuner/mpe-tuner-paper.md`](../../docs/architecture/tuner/mpe-tuner-paper.md), except for the
  amendment the design's Section 6 mandates (Task 13 below)

**Goal:** Make `MpeTuner`'s MIDI message routing and filtering conform to the MPE Tuner paper, closing gaps P7, I1–I3,
C3–C6 and N4 across four phased pull requests.

**Architecture:** A new pure `MpeMessageRouting` component turns the channel's role in the Zone structure into an
explicit total value (`MpeChannelRole`) and encodes §3.5's table as a single `route` function returning an
`MpeRoutingVerdict`. `MpeTuner.processShortMessage` becomes classify-then-act: it asks for the role, asks for the
verdict, and either discards, forwards (possibly on another channel), re-emits a complete RPN/NRPN sequence, or hands
the message to one of today's interpreting handlers. The MCM state reset is rescoped from "everything" to "the channels
entering or leaving MPE control", which needs a per-channel `ScMidiChannelStateTracker.reset` and an
`MpeChannelAllocator.retaining` factory that transplants unaffected channel state into the rebuilt allocator.

**Tech Stack:** Scala 3, sbt 1 (driven through `sbtn` against the running BSP server), ScalaTest 3 (`AnyFlatSpec` +
`Matchers` + `TableDrivenPropertyChecks`), scoverage. Modules touched: `tuner`, `sc-midi`.

---

## Global Constraints

- **Strict TDD.** Red (failing test, failing for the right reason — never a compile error) → green (minimum production
  code) → refactor. Never write logic without a preceding failing test; never mix refactoring with behavioural change.
- **Coding conventions**: brace syntax (never Scala 3 indentation syntax), 2-space indent, 120-column lines, no `new`,
  no `return` (use `scala.util.boundary` when an early exit is needed), Scala 3 `enum` over `sealed trait` for
  enumerations, ScalaDoc on every public identifier, `// TODO #<issue>` for every TODO.
- **Test conventions**: Given/When/Then comments, no `if` in tests, fixtures for repeated setup. New `MpeTunerTest`
  cases go in the `behavior of` block matching their category *and input mode*, inside a
  `// ---- <subgroup name> ----` subgroup — see that class's ScalaDoc at `MpeTunerTest.scala:31-55`.
- **Visibility**: everything new in the `tuner` module that is implementation detail shared between `MpeTuner` and its
  collaborators must be `private[tuner]` — bare top-level `private` is not reliably package-scoped under Scala 3. Only
  `MpeTuner`, `MpeZone*` and `MpeInputMode` stay public (`format` depends on them).
- **License headers**: do not write them by hand for `.scala` files; the `.githooks/pre-commit` hook adds them. `Read`
  skips them, so files appear to start around line 17 with real line numbers preserved.
- **Compile** with `mcp__metals__compile-module` (`module = "tuner"` / `"sc-midi"`) or `mcp__metals__compile-full`.
- **Test** through `sbtn` only, always with the reporter flags:
  `sbtn "<module>/testOnly <FQCN> -- -oNCXEHLOPQRMWS"`; whole suite: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`.
- **Coverage** is checked with the `scoverage-inspector` skill at the end of every phase. Module floors at the base
  commit: `tuner` stmt 80 / branch 75, `sc-midi` stmt 62 / branch 44. Floors must never drop; new files target 80%.
- **`rg` is not installed** in this environment. Use `grep`, and route it through `rtk proxy grep …` when you need
  unfiltered output.
- **Out of scope** (do not absorb, do not delete): `TODO #253` at `MpeTuner.scala:261-263`, `TODO #254` at
  `MpeTuner.scala:566-568`. `TODO #254` sits inside the very method Phase 4 rescopes; it must survive and stay accurate.
- **Decisions settled before design** (design Section 2) are not to be re-opened: §2.2(f) grouping applies in both input
  modes and the paper is amended for it; MSB-before-LSB selector order is normalized across all three emitters; the
  tracker gains a per-channel `reset`; the work ships as four phased PRs.

---

## Phases, pull requests and sub-issues

Four sub-issues of #250, one pull request each. **All four sub-issues must exist before any phase is executed.** No
PR resolves #250 — each resolves its own sub-issue only, and #250 is closed by hand once all four have merged.

| Phase | Sub-issue | Tasks | Scope | Gaps | Modules |
|---|---|---|---|---|---|
| 1 | [#259](https://github.com/calinburloiu/microtonalist/issues/259) | 1–4 | PBS sequence closure and RPN selector order | P7 | `sc-midi`, `tuner` |
| 2 | [#260](https://github.com/calinburloiu/microtonalist/issues/260) | 5–8 | Channel role and the routing table | I2, C4, I3, C5, N4 | `tuner`, `sc-midi` |
| 3 | [#261](https://github.com/calinburloiu/microtonalist/issues/261) | 9–11 | RPN/NRPN sequencing, MCM validity, paper amendment | C6, §2.2(f) | `tuner`, docs |
| 4 | [#262](https://github.com/calinburloiu/microtonalist/issues/262) | 12–16 | MCM reset scoping and Tuning survival | C3, I1 | `tuner`, `sc-midi` |

**Ordering.** Only one hard dependency exists: **phase 3 needs phase 2**, because it adds a verdict case to
`MpeRoutingVerdict` and rows to `route`, both of which phase 2 creates. Phases **1, 2 and 4 can be branched from
`main` and developed in parallel**; phase 3 branches from phase 2 (or waits for it to merge).

Two things to know when running them in parallel:

1. **Phase 4 must not reach for `MpeMessageRouting.roleOf`.** Its Zone-assignment comparison uses the existing
   `findChannelRole` (`MpeTuner.scala:538-543`), which already takes the Zone as a parameter and is exactly the right
   shape. Phase 2 *deletes* `findChannelRole`, so whichever of phases 2 and 4 merges second must reconcile: point
   `assignmentOf` at `MpeMessageRouting.roleOf` as Task 15 shows in its second variant. Git will merge these two
   changes cleanly and the result will not compile — so compile after merging, do not trust a clean merge.
2. **`MpeTunerTest.scala` is edited by phases 1, 2 and 4.** They work in different `behavior of` blocks, so the
   conflicts are textual rather than semantic, but expect to resolve some.

---

## Two refinements to the design

Both were found while writing this plan, approved by the author, and folded into the design's 2026-08-13 revision —
Section 5, gaps C3 and C6. They are restated here because they are the two places where a reader of the original
design would expect something different. Neither reverses a Section 2 decision.

1. **The MCM state reset must also drop notes whose *input* channel left MPE control** (Phase 4, Task 15). Scoping
   `retaining` to output Member Channels alone is not sufficient: a note that arrived on input channel 7
   and was allocated to output channel 2 survives a reconfiguration that shrinks the Lower Zone from 10 to 4 Member
   Channels, because output channel 2 is retained — but input channel 7 is now `Outside`, so the performer's Note Off
   is discarded and the note hangs forever. That is exactly the failure class #250 exists to remove (see the prompt's
   **C4** hanging-note analysis). The plan therefore drops a note when **either** its output channel **or** its input
   channel is in the affected set, and `MpeChannelAllocator.retaining` takes a `droppedInputChannels` parameter.
2. **A data-value CC is discarded when the tracked selector is incomplete** (Phase 3, Task 9). The design's table has a
   "Data value, no selector ever set → Discard" row for `RpnSelector.None`. The tracker also produces half-set
   selectors — `Rpn(msb, 0x7F)` after a lone CC #101, `Rpn(0x7F, lsb)` after a lone CC #100 — for which
   `ScMidiChannelStateTracker.writeDataEntry` (`ScMidiChannelStateTracker.scala:316-330`) itself refuses to record a
   value. The plan extends the `None` row to those: if the tracker will not record it, the Tuner will not relay it.

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala` | `MpeChannelRole`, `MpeRoutingVerdict`, and the pure `MpeMessageRouting` object holding `roleOf`, `route` (§3.5's table) and `rpnSequence`. No mutable state, no MIDI plumbing. |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala` | §3.5's table as a table-driven test: one row per cell of the design's Section 4 matrix, plus `roleOf` and `rpnSequence` cases. |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/PitchBendSensitivityTest.scala` | Direct tests for `PitchBendSensitivity` and `PitchBendSensitivityMessages.create` (none exist today). |

**Modified**

| File | Change |
|---|---|
| `tuner/src/main/scala/…/tuner/MpeTuner.scala` | Classify-then-act dispatch; handlers take an `MpeChannelRole`; `resolveZoneMasterChannel`, `isMasterChannel`, `forwardOnZoneMasterChannel`, `findZoneForChannel`, `findChannelRole`, `routingZoneForNonMpeInput` and `processMemberNoteOn`'s `case None` deleted; RPN Null appended in `applyPbsUpdate`; MSB-first ordering; `processMcm` reset rescoped; `_tuning` reset moved to `reset()`. Expected to shrink from 838 to ~700 lines. |
| `tuner/src/main/scala/…/tuner/MpeChannelAllocator.scala` | Seedable channel states; `MpeChannelAllocator.retaining` companion factory. |
| `sc-midi/src/main/scala/…/scmidi/message/ScMidiCc.scala` | `LocalControl` (122), `OmniModeOff` (124), `OmniModeOn` (125), `MonoModeOn` (126), `PolyModeOn` (127). |
| `sc-midi/src/main/scala/…/scmidi/PitchBendSensitivity.scala` | MSB-before-LSB selector and Null in `PitchBendSensitivityMessages.create`. |
| `sc-midi/src/main/scala/…/scmidi/ScMidiChannelStateTracker.scala` | `reset(channel: Int)` overload. |
| `tuner/src/test/scala/…/tuner/MpeTunerTest.scala` | Reordered RPN expectations; inverted Member-Channel Zone-level tests; new subgroups for out-of-zone discard, Master Channel CC #74 / Channel Pressure, MIDI Mode messages, RPN/NRPN sequencing and scoped MCM reset. |
| `tuner/src/test/scala/…/tuner/MonophonicPitchBendTunerTest.scala` | Reordered RPN expectation. |
| `tuner/src/test/scala/…/tuner/MpeChannelAllocatorTest.scala` | `retaining` cases. |
| `sc-midi/src/test/scala/…/scmidi/ScMidiChannelStateTrackerTest.scala` | `reset(channel)` cases. |
| `docs/architecture/tuner/mpe-tuner-paper.md` | The three surgical edits of design Section 6. |
| `docs/architecture/tuner/README.md` | `MpeMessageRouting` in "Key types"; the #250 "Subject to change" bullet narrowed, then removed. |

---

# Phase 1 — PBS sequence closure and RPN selector order (P7)

**Sub-issue**: [#259](https://github.com/calinburloiu/microtonalist/issues/259). **PR must not close #250.**

Three emitters produce RPN sequences today and they disagree on byte order: `mcmMessages`
(`MpeTuner.scala:754-763`) and `PitchBendSensitivityMessages.create` (`PitchBendSensitivity.scala:71-81`) send LSB
before MSB, `applyPbsUpdate` (`MpeTuner.scala:504-527`) sends MSB before LSB and omits the closing Null altogether.
All three become **CC #101 (MSB), CC #100 (LSB), Data Entry…, CC #101 = 0x7F, CC #100 = 0x7F** — the order the MIDI 1.0
RPN procedure is conventionally written in.

### Task 1: MSB-first RPN selector order in `sc-midi`

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/PitchBendSensitivity.scala:71-81`
- Test (create): `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/PitchBendSensitivityTest.scala`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTunerTest.scala:117-124`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala:281-292, 343-354`

**Interfaces:**
- Consumes: nothing.
- Produces: `PitchBendSensitivityMessages.create(channel: Int, pitchBendSensitivity: PitchBendSensitivity):
  Seq[MidiMessage]` — unchanged signature, new message order.

- [ ] **Step 1: Write the failing test**

Create `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/PitchBendSensitivityTest.scala`:

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{CcScMidiMessage, ScMidiCc, ScMidiRpn}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PitchBendSensitivityTest extends AnyFlatSpec with Matchers {

  behavior of "PitchBendSensitivity"

  it should "compute the total range in cents" in {
    // Given / When / Then
    PitchBendSensitivity(2).totalCents shouldEqual 200
    PitchBendSensitivity(3, 37).totalCents shouldEqual 337
  }

  it should "reject values outside the 7-bit MIDI range" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy PitchBendSensitivity(128)
    an[IllegalArgumentException] should be thrownBy PitchBendSensitivity(2, -1)
  }

  behavior of "PitchBendSensitivityMessages"

  it should "emit the RPN sequence with the selector MSB before its LSB, closed by an RPN Null" in {
    // Given
    val pbs = PitchBendSensitivity(3, 37)

    // When
    val messages = PitchBendSensitivityMessages.create(channel = 5, pbs).map(_.asScala)

    // Then
    messages shouldEqual Seq(
      CcScMidiMessage(5, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(5, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(5, ScMidiCc.DataEntryMsb, 3),
      CcScMidiMessage(5, ScMidiCc.DataEntryLsb, 37),
      CcScMidiMessage(5, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb),
      CcScMidiMessage(5, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb)
    )
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.PitchBendSensitivityTest -- -oNCXEHLOPQRMWS"`
Expected: the ordering case FAILS on the first two elements (LSB emitted before MSB); the other two cases PASS.

- [ ] **Step 3: Reorder the emitter**

In `PitchBendSensitivity.scala`, replace the body of `create`:

```scala
  def create(channel: Int, pitchBendSensitivity: PitchBendSensitivity): Seq[MidiMessage] = {
    Seq(
      CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(channel, ScMidiCc.DataEntryMsb, pitchBendSensitivity.semitones),
      CcScMidiMessage(channel, ScMidiCc.DataEntryLsb, pitchBendSensitivity.cents),
      // Selecting the Null RPN prevents a later stray Data Entry from changing this parameter.
      CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb),
      CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb)
    ).map(_.asJava)
  }
```

Also update the ScalaDoc of `create` to state the order explicitly: *"The RPN selector and the closing RPN Null are
emitted MSB (CC #101) before LSB (CC #100), the order the MIDI 1.0 RPN procedure is conventionally written in."*

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.PitchBendSensitivityTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Update the two dependent expectations in `tuner`**

`MonophonicPitchBendTunerTest.scala:117-124` — swap each selector pair so MSB precedes LSB:

```scala
    ccMessages should contain inOrderOnly(
      (ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      (ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      (ScMidiCc.DataEntryMsb, customPitchBendSensitivity.semitones),
      (ScMidiCc.DataEntryLsb, customPitchBendSensitivity.cents),
      (ScMidiCc.RpnMsb, ScMidiRpn.NullMsb),
      (ScMidiCc.RpnLsb, ScMidiRpn.NullLsb)
    )
```

`MpeTunerTest.scala` — both `"output Pitch Bend Sensitivity on all channels"` tests (the Non-MPE one at 276-293 and
the MPE one at 338-355). In each, swap the first two elements of every `contain inOrder(...)` block, e.g.:

```scala
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 2)
    )
```

and likewise inside the `(1 to 7).foreach { ch => … }` block, with `CcScMidiMessage(ch, …)` and `DataEntryMsb, 48`.

- [ ] **Step 6: Run both module suites**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"` then `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS. (The `tuner` suite may still fail on the MCM expectations — those belong to Task 2. If it does, note
which tests and continue; they must be green by the end of Task 2.)

- [ ] **Step 7: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/PitchBendSensitivity.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/PitchBendSensitivityTest.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTunerTest.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#250] Emit the PBS RPN selector MSB before LSB"
```

### Task 2: MSB-first RPN selector order in `MpeTuner.mcmMessages`

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:754-763`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` — the MCM
  expectations at 2360-2364, 2379-2383, 2394-2398, 2409-2413, 2436-2440, 2528-2532 and 2534-2538

**Interfaces:**
- Consumes: nothing.
- Produces: `MpeTuner.mcmMessages(zone: MpeZone): Seq[MidiMessage]` — unchanged signature, new message order.

- [ ] **Step 1: Write the failing test**

In `MpeTunerTest.scala`, in `behavior of "MpeTuner - MCM Processing - MPE Input"`, subgroup
`// ---- MCM emission on reset ----`, add after the existing `"output MPE Configuration Message (MCM) for the
configured zone"` test:

```scala
  it should "emit the MCM RPN selector MSB before its LSB, closed by an RPN Null" in new Fixture(mpeTunerMpeInput) {
    // When
    private val output = tuner.reset()
    // Then
    private val ccs = extractCc(output).filter(_.channel == 0)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 15),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb)
    )
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: the new case FAILS — the selector arrives LSB-first.

- [ ] **Step 3: Reorder `mcmMessages`**

```scala
  private def mcmMessages(zone: MpeZone): Seq[MidiMessage] = {
    // MCM: RPN 00 06 on the Master Channel with Data Entry MSB = memberCount, closed by an RPN Null.
    // Selector and Null are emitted MSB before LSB, matching `PitchBendSensitivityMessages.create`.
    Seq(
      CcScMidiMessage(zone.masterChannel, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(zone.masterChannel, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(zone.masterChannel, ScMidiCc.DataEntryMsb, zone.memberCount),
      CcScMidiMessage(zone.masterChannel, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb),
      CcScMidiMessage(zone.masterChannel, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb)
    ).map(_.asJava)
  }
```

- [ ] **Step 4: Update the existing MCM expectations**

In every `contain inOrder(...)` block listed under **Files** above, swap the `RpnLsb`/`RpnMsb` lines so the MSB comes
first, e.g. at 2360-2364:

```scala
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 7)
    )
```

Leave `sendMcm` / `sendPbsMsb` (the *input*-side helpers at `MpeTunerTest.scala:253-265`) untouched: they simulate a
sender, and a sender is free to use either order. Leave the `not contain inOrder(...)` block at 2491-2495 as it is —
it asserts an MCM is *not* emitted, and inverting its order would weaken it; instead swap its two selector lines too,
so it keeps matching the shape the Tuner now emits.

- [ ] **Step 5: Run the test to verify it passes**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#250] Emit the MCM RPN selector MSB before LSB"
```

### Task 3: Close the forwarded PBS sequence with an RPN Null (P7)

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:504-527` (`applyPbsUpdate`)
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` — new cases in
  `behavior of "MpeTuner - PBS Processing - Non-MPE Input"` and `… - MPE Input"`

**Interfaces:**
- Consumes: nothing.
- Produces: `applyPbsUpdate` emits, on the destination channel: CC #101 = 0x00, CC #100 = 0x00, the Data Entry CC,
  CC #101 = 0x7F, CC #100 = 0x7F.

- [ ] **Step 1: Write the failing tests**

In `MpeTunerTest.scala`, `behavior of "MpeTuner - PBS Processing - Non-MPE Input"`, subgroup
`// ---- Master-channel PBS update ----`, at the end of the subgroup:

```scala
  it should "close the forwarded PBS sequence with an RPN Null" in new Fixture(tuner7) {
    // When
    private val output = sendPbsMsb(tuner, channel = 5, semitones = 12)
    // Then
    extractCc(output) should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 12),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb)
    )
  }
```

In `behavior of "MpeTuner - PBS Processing - MPE Input"`, subgroup
`// ---- Member-channel PBS update & forwarding ----`, at the end of the subgroup:

```scala
  it should "close the forwarded PBS sequence with an RPN Null on the receiving Member Channel" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = sendPbsMsb(tuner, channel = 3, semitones = 24)
      // Then
      extractCc(output).filter(_.channel == 3) should contain inOrder(
        CcScMidiMessage(3, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
        CcScMidiMessage(3, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
        CcScMidiMessage(3, ScMidiCc.DataEntryMsb, 24),
        CcScMidiMessage(3, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb),
        CcScMidiMessage(3, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb)
      )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: both new cases FAIL — no Null CCs are emitted, and the selector arrives MSB-first only by accident of the
current code (the Non-MPE case additionally fails because today's `applyPbsUpdate` already emits MSB then LSB but no
Null).

- [ ] **Step 3: Append the Null in `applyPbsUpdate`**

Replace lines 515-520 of `MpeTuner.scala` with:

```scala
    // Forward a complete RPN sequence on the destination channel only: selector, Data Entry, and the closing
    // RPN Null that protects Pitch Bend Sensitivity from a later stray Data Entry (paper, "Configuration"
    // preamble). The selector is re-sent rather than relayed, guarding against another device having changed
    // the active RPN on this channel between the sender's selector and its Data Entry; the sender's own
    // selector CCs are consumed upstream.
    buffer += CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava
    buffer += CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava
    buffer += CcScMidiMessage(channel, ccNumber, ccValue).asJava
    buffer += CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb).asJava
    buffer += CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb).asJava
```

Update the method's ScalaDoc (lines 491-503) to describe the closing Null and the MSB-first order.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS, whole class green.

- [ ] **Step 5: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#250] Close a forwarded PBS sequence with an RPN Null"
```

### Task 4: Phase 1 completion

- [ ] **Step 1: Run the full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 2: Verify coverage**

Invoke the `scoverage-inspector` skill. Confirm `tuner` ≥ 80 stmt / 75 branch and `sc-midi` ≥ 62 stmt / 44 branch, and
that the new `PitchBendSensitivityTest` lifts `PitchBendSensitivity.scala` toward the 80% target for touched files.
Iterate until met.

- [ ] **Step 3: Open the pull request**

Use the `contributing` skill's script, which derives the `[#250/#259]` title prefix from the issue spec:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 250/259 \
  "Close the forwarded PBS sequence with an RPN Null and normalize RPN selector order"
```

The script writes `Resolves #259`, which closes the sub-issue but not #250. Say in the body that it closes gap P7 and
normalizes RPN selector order across all three emitters.

---

# Phase 2 — Channel role and the routing table (I2, C4, I3, C5, N4)

**Sub-issue**: [#260](https://github.com/calinburloiu/microtonalist/issues/260). **PR must not close #250.**

### Task 5: `MpeChannelRole` and `roleOf`

**Files:**
- Create: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala`
- Test (create): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala`

**Interfaces:**
- Consumes: `MpeInputMode`, `MpeZone`, `MpeZones` (`MpeZone.scala`).
- Produces:
  - `private[tuner] enum MpeChannelRole { case NonMpeInput(routingZone: MpeZone); case Master(zone: MpeZone);
    case Member(zone: MpeZone); case Outside }`.
  - `private[tuner] object MpeMessageRouting { def roleOf(inputMode: MpeInputMode, zones: MpeZones, channel: Int):
    MpeChannelRole }`.

- [ ] **Step 1: Write the failing test**

Create `MpeMessageRoutingTest.scala`:

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

/**
 * Tests for [[MpeMessageRouting]].
 *
 * == Test Organization ==
 *
 * Three `behavior of` blocks, one per public function: `roleOf`, `route`, and `rpnSequence`. The `route` block is
 * the paper's message-handling table ("How the MPE Tuner Handles Common MIDI Messages in MPE Input Mode") expressed
 * as table-driven checks, one `Table` row per cell: the role supplies the column and the message the row. Add a new
 * case by adding a row, not a new test, unless the cell needs a rule the table cannot express.
 */
class MpeMessageRoutingTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  private val lower7: MpeZone = MpeZone(MpeZoneType.Lower, 7)
  private val upper7: MpeZone = MpeZone(MpeZoneType.Upper, 7)
  private val disabledLower: MpeZone = MpeZone(MpeZoneType.Lower, 0)
  private val disabledUpper: MpeZone = MpeZone(MpeZoneType.Upper, 0)

  private val dualZones: MpeZones = MpeZones(lower7, upper7)
  private val lowerOnlyZones: MpeZones = MpeZones(lower7, disabledUpper)
  private val upperOnlyZones: MpeZones = MpeZones(disabledLower, upper7)
  private val noZones: MpeZones = MpeZones(disabledLower, disabledUpper)

  behavior of "MpeMessageRouting.roleOf"

  // ---- MPE Input Mode ----

  it should "classify every channel of a dual-Zone configuration" in {
    // Given
    val expectations = Table(
      ("channel", "role"),
      (0, MpeChannelRole.Master(lower7)),
      (1, MpeChannelRole.Member(lower7)),
      (7, MpeChannelRole.Member(lower7)),
      (8, MpeChannelRole.Member(upper7)),
      (14, MpeChannelRole.Member(upper7)),
      (15, MpeChannelRole.Master(upper7))
    )
    forAll(expectations) { (channel, role) =>
      // When / Then
      MpeMessageRouting.roleOf(MpeInputMode.Mpe, dualZones, channel) shouldEqual role
    }
  }

  it should "classify a channel of no enabled Zone as Outside" in {
    // Given
    val channels = Table("channel", 8, 12, 14, 15)
    forAll(channels) { channel =>
      // When / Then
      MpeMessageRouting.roleOf(MpeInputMode.Mpe, lowerOnlyZones, channel) shouldEqual MpeChannelRole.Outside
    }
  }

  it should "classify every channel as Outside when no Zone is enabled" in {
    // Given
    val channels = Table("channel", 0, 1, 8, 15)
    forAll(channels) { channel =>
      // When / Then
      MpeMessageRouting.roleOf(MpeInputMode.Mpe, noZones, channel) shouldEqual MpeChannelRole.Outside
    }
  }

  // ---- Non-MPE Input Mode ----

  it should "classify every channel as NonMpeInput of the Lower Zone when it is enabled" in {
    // Given
    val channels = Table("channel", 0, 3, 15)
    forAll(channels) { channel =>
      // When / Then
      MpeMessageRouting.roleOf(MpeInputMode.NonMpe, dualZones, channel) shouldEqual
        MpeChannelRole.NonMpeInput(lower7)
    }
  }

  it should "fall back to the Upper Zone when only it is enabled" in {
    // When / Then
    MpeMessageRouting.roleOf(MpeInputMode.NonMpe, upperOnlyZones, 3) shouldEqual MpeChannelRole.NonMpeInput(upper7)
  }

  it should "classify every channel as Outside when no Zone is enabled" in {
    // When / Then
    MpeMessageRouting.roleOf(MpeInputMode.NonMpe, noZones, 3) shouldEqual MpeChannelRole.Outside
  }
}
```

- [ ] **Step 2: Create the thinnest stub that compiles**

Create `MpeMessageRouting.scala` with the enum and a `roleOf` whose body is `???`. Compile with
`mcp__metals__compile-module` (`module = "tuner"`) until it compiles.

- [ ] **Step 3: Run the test to verify it fails for the right reason**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeMessageRoutingTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL with `scala.NotImplementedError` — not a compile error.

- [ ] **Step 4: Implement `roleOf`**

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.ScMidiChannelStateTracker.RpnSelector
import org.calinburloiu.music.scmidi.message.*
import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*

import javax.sound.midi.MidiMessage

/**
 * The part a MIDI channel plays in the Tuner's Zone structure, as seen by the message router.
 *
 * The role is a total classification: every channel has exactly one in either input mode. It carries the Zone it
 * belongs to, so that a routing decision needs nothing else to name its destination channel.
 */
private[tuner] enum MpeChannelRole {
  /** Non-MPE Input Mode, with a Zone enabled to route this input's Zone-level messages to. */
  case NonMpeInput(routingZone: MpeZone)

  /** MPE Input Mode, the Master Channel of an enabled Zone. */
  case Master(zone: MpeZone)

  /** MPE Input Mode, a Member Channel of an enabled Zone. */
  case Member(zone: MpeZone)

  /**
   * Under no Zone's control, in either input mode: an MPE input channel outside every enabled Zone, and — when no
   * Zone is enabled at all — every channel in both input modes. The paper's "Messages Outside the Zone Structure"
   * section discards everything received here, an MCM on MIDI Channel 1 or 16 excepted.
   */
  case Outside
}

/**
 * The MPE Tuner's MIDI message routing and filtering rules, as pure functions of the channel's role, the message
 * and the channel's currently selected Registered or Non-Registered Parameter.
 *
 * This object holds no state: everything it needs is passed in, which is what lets the paper's message-handling
 * table be read straight off [[route]].
 */
private[tuner] object MpeMessageRouting {

  /**
   * Classifies a channel within a Zone configuration.
   *
   * In Non-MPE Input Mode the input carries no Zone structure of its own, so every channel takes the same role,
   * naming the Zone its Zone-level messages are routed to: the Lower Zone when enabled, otherwise the Upper Zone.
   *
   * @param inputMode The Tuner's current input mode.
   * @param zones     The Tuner's current Zone configuration.
   * @param channel   The 0-indexed MIDI channel to classify.
   */
  def roleOf(inputMode: MpeInputMode, zones: MpeZones, channel: Int): MpeChannelRole = inputMode match {
    case MpeInputMode.NonMpe =>
      if (zones.lower.isEnabled) MpeChannelRole.NonMpeInput(zones.lower)
      else if (zones.upper.isEnabled) MpeChannelRole.NonMpeInput(zones.upper)
      else MpeChannelRole.Outside
    case MpeInputMode.Mpe =>
      roleInZone(zones.lower, channel)
        .orElse(roleInZone(zones.upper, channel))
        .getOrElse(MpeChannelRole.Outside)
  }

  private def roleInZone(zone: MpeZone, channel: Int): Option[MpeChannelRole] = {
    if (!zone.isEnabled) None
    else if (channel == zone.masterChannel) Some(MpeChannelRole.Master(zone))
    else if (zone.memberChannels.contains(channel)) Some(MpeChannelRole.Member(zone))
    else None
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeMessageRoutingTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala
git commit -m "[#250] Add MpeChannelRole and MpeMessageRouting.roleOf"
```

### Task 6: The routing table — `MpeRoutingVerdict` and `route`

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiCc.scala:39-44`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala`

**Interfaces:**
- Consumes: `MpeChannelRole` (Task 5); `ScMidiChannelStateTracker.RpnSelector`; `ChannelScMidiMessage` and its seven
  subtypes; `ScMidiCc`, `ScMidiRpn`.
- Produces:
  - `private[tuner] enum MpeRoutingVerdict { case Discard; case ForwardOn(channel: Int); case Interpret }`
    (`ForwardRpnSequenceOn` is added in Phase 3, Task 9.)
  - `def route(role: MpeChannelRole, message: ChannelScMidiMessage, rpnSelector: RpnSelector): MpeRoutingVerdict`
  - `ScMidiCc.LocalControl = 122`, `OmniModeOff = 124`, `OmniModeOn = 125`, `MonoModeOn = 126`, `PolyModeOn = 127`

**Scope note.** This task implements every row of the design's Section 4 table **except** the four RPN/NRPN rows, which
Phase 3 owns. Until then, uninterpreted RPN/NRPN selectors and data values fall through `route`'s ordinary-CC
catch-all, which preserves today's destinations (discarded at Member level by I3, same channel at Master level,
Master Channel in Non-MPE mode, discarded when Outside). In particular **do not** fix the invalid-MCM leak here — the
Data Entry of an MCM addressed to a channel other than 1 or 16 keeps escaping to the catch-all until Task 9 closes it,
and Task 9's red test depends on that.

- [ ] **Step 1: Write the failing tests**

Add to `MpeMessageRoutingTest.scala`:

```scala
  behavior of "MpeMessageRouting.route"

  private val memberRole: MpeChannelRole = MpeChannelRole.Member(lower7)
  private val masterRole: MpeChannelRole = MpeChannelRole.Master(lower7)
  private val nonMpeRole: MpeChannelRole = MpeChannelRole.NonMpeInput(lower7)
  private val outsideRole: MpeChannelRole = MpeChannelRole.Outside

  private val noSelector: RpnSelector = RpnSelector.None

  // The input channel every table row below uses: Member Channel 3 of the Lower Zone, so that a redirection to
  // the Zone's Master Channel (0) is visibly different from a forward on the arrival channel. The same message is
  // replayed against all four roles, so the `Master` column expects `ForwardOn(3)`: `route` forwards on the
  // channel the message carries, not on the role's Master Channel. The next test covers the realistic pairing.
  private val inputChannel: Int = 3

  // ---- Channel Voice messages ----

  it should "route the message classes of the paper's table" in {
    // Given
    val verdicts = Table(
      ("description", "message", "member", "master", "nonMpe", "outside"),
      ("Note On",
        NoteOnScMidiMessage(inputChannel, MidiNote.C4, 100),
        Interpret, ForwardOn(inputChannel), Interpret, Discard),
      ("Note Off",
        NoteOffScMidiMessage(inputChannel, MidiNote.C4),
        Interpret, ForwardOn(inputChannel), Interpret, Discard),
      ("Pitch Bend",
        PitchBendScMidiMessage(inputChannel, 1000),
        Interpret, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("Channel Pressure",
        ChannelPressureScMidiMessage(inputChannel, 90),
        Interpret, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("CC #74",
        CcScMidiMessage(inputChannel, ScMidiCc.MpeSlide, 100),
        Interpret, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("Polyphonic Key Pressure",
        PolyPressureScMidiMessage(inputChannel, MidiNote.C4, 80),
        Discard, ForwardOn(inputChannel), Interpret, Discard),
      ("Program Change",
        ProgramChangeScMidiMessage(inputChannel, 5),
        Discard, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("Bank Select MSB",
        CcScMidiMessage(inputChannel, ScMidiCc.BankSelectMsb, 1),
        Discard, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("Damper Pedal",
        CcScMidiMessage(inputChannel, ScMidiCc.SustainPedal, 127),
        Discard, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("All Sound Off (CC #120)",
        CcScMidiMessage(inputChannel, ScMidiCc.AllSoundOff, 0),
        Discard, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("Reset All Controllers (CC #121)",
        CcScMidiMessage(inputChannel, ScMidiCc.ResetAllControllers, 0),
        Discard, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("Local Control (CC #122)",
        CcScMidiMessage(inputChannel, ScMidiCc.LocalControl, 0),
        Discard, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("All Notes Off (CC #123)",
        CcScMidiMessage(inputChannel, ScMidiCc.AllNotesOff, 0),
        Discard, ForwardOn(inputChannel), ForwardOn(0), Discard),
      ("Omni Mode Off (CC #124)",
        CcScMidiMessage(inputChannel, ScMidiCc.OmniModeOff, 0),
        Discard, Discard, Discard, Discard),
      ("Omni Mode On (CC #125)",
        CcScMidiMessage(inputChannel, ScMidiCc.OmniModeOn, 0),
        Discard, Discard, Discard, Discard),
      ("Mono Mode On (CC #126)",
        CcScMidiMessage(inputChannel, ScMidiCc.MonoModeOn, 1),
        Discard, Discard, Discard, Discard),
      ("Poly Mode On (CC #127)",
        CcScMidiMessage(inputChannel, ScMidiCc.PolyModeOn, 0),
        Discard, Discard, Discard, Discard)
    )
    forAll(verdicts) { (_, message, member, master, nonMpe, outside) =>
      // When / Then
      MpeMessageRouting.route(memberRole, message, noSelector) shouldEqual member
      MpeMessageRouting.route(masterRole, message, noSelector) shouldEqual master
      MpeMessageRouting.route(nonMpeRole, message, noSelector) shouldEqual nonMpe
      MpeMessageRouting.route(outsideRole, message, noSelector) shouldEqual outside
    }
  }

  it should "forward a Master Channel message on its own channel" in {
    // Given
    val message = CcScMidiMessage(0, ScMidiCc.SustainPedal, 127)
    // When / Then
    MpeMessageRouting.route(masterRole, message, noSelector) shouldEqual ForwardOn(0)
  }

  it should "redirect a Non-MPE input message to the Upper Zone Master Channel when only it is enabled" in {
    // Given
    val message = CcScMidiMessage(inputChannel, ScMidiCc.SustainPedal, 127)
    // When / Then
    MpeMessageRouting.route(MpeChannelRole.NonMpeInput(upper7), message, noSelector) shouldEqual ForwardOn(15)
  }

  // ---- Interpreted parameters ----

  it should "interpret an MCM Data Entry MSB on MIDI Channel 1 or 16 whatever the role" in {
    // Given
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    val channels = Table("channel", 0, 15)
    forAll(channels) { channel =>
      val message = CcScMidiMessage(channel, ScMidiCc.DataEntryMsb, 7)
      forAll(roles) { role =>
        // When / Then
        MpeMessageRouting.route(role, message, mcmSelector) shouldEqual Interpret
      }
    }
  }

  it should "interpret a PBS Data Entry at every role but Outside" in {
    // Given
    val ccNumbers = Table("ccNumber", ScMidiCc.DataEntryMsb, ScMidiCc.DataEntryLsb)
    forAll(ccNumbers) { ccNumber =>
      val message = CcScMidiMessage(inputChannel, ccNumber, 24)
      // When / Then
      MpeMessageRouting.route(memberRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(masterRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(nonMpeRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(outsideRole, message, pbsSelector) shouldEqual Discard
    }
  }

  it should "consume the selector of an interpreted parameter" in {
    // Given
    val selectors = Table("selector", mcmSelector, pbsSelector)
    val ccNumbers = Table("ccNumber", ScMidiCc.RpnMsb, ScMidiCc.RpnLsb)
    forAll(selectors) { selector =>
      forAll(ccNumbers) { ccNumber =>
        // When / Then
        MpeMessageRouting.route(masterRole, CcScMidiMessage(0, ccNumber, 0), selector) shouldEqual Discard
      }
    }
  }
```

Add the imports and the two selector fixtures at the top of the class:

```scala
import org.calinburloiu.music.microtonalist.tuner.MpeRoutingVerdict.*
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.ScMidiChannelStateTracker.RpnSelector
import org.calinburloiu.music.scmidi.message.*
```

```scala
  private val mcmSelector: RpnSelector =
    RpnSelector.Rpn(ScMidiRpn.MpeConfigurationMessageMsb, ScMidiRpn.MpeConfigurationMessageLsb)
  private val pbsSelector: RpnSelector =
    RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
```

- [ ] **Step 2: Add the `ScMidiCc` constants and a `route` stub, then verify the tests fail**

In `ScMidiCc.scala`, after `AllNotesOff` (line 44):

```scala
  /** Local Control controller number (#122). */
  val LocalControl: Int = 122
  /** Omni Mode Off controller number (#124). */
  val OmniModeOff: Int = 124
  /** Omni Mode On controller number (#125). */
  val OmniModeOn: Int = 125
  /** Mono Mode On controller number (#126). */
  val MonoModeOn: Int = 126
  /** Poly Mode On controller number (#127). */
  val PolyModeOn: Int = 127
```

Add the `MpeRoutingVerdict` enum and `def route(...): MpeRoutingVerdict = ???` to `MpeMessageRouting.scala`. Compile
(`mcp__metals__compile-module`, `module = "tuner"`), then run:
`sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeMessageRoutingTest -- -oNCXEHLOPQRMWS"`
Expected: the new cases FAIL with `scala.NotImplementedError`.

- [ ] **Step 3: Implement `MpeRoutingVerdict` and `route`**

```scala
/**
 * What the Tuner does with a received channel message, as decided by [[MpeMessageRouting.route]].
 */
private[tuner] enum MpeRoutingVerdict {
  /** Emit nothing. The message is outside the Zone structure, or at the wrong level for its class. */
  case Discard

  /** Relay the message unmodified, on the given output channel — its own, or a Zone's Master Channel. */
  case ForwardOn(channel: Int)

  /** The Tuner acts on the message itself: note allocation, an Expression Value, the MCM, or Pitch Bend Sensitivity. */
  case Interpret
}
```

```scala
  /**
   * Decides what to do with a channel message, implementing the paper's message-handling table row by row: the
   * message supplies the row, the role the column.
   *
   * @param role        The role of the channel the message arrived on, from [[roleOf]].
   * @param message     The received message.
   * @param rpnSelector The parameter currently selected on the arrival channel, which is what distinguishes an MCM
   *                    Data Entry from a Pitch Bend Sensitivity one from uninterpreted parameter traffic. It must
   *                    already account for the message being routed — [[MpeTuner]] feeds every message to its
   *                    tracker before dispatching it.
   */
  def route(role: MpeChannelRole,
            message: ChannelScMidiMessage,
            rpnSelector: RpnSelector): MpeRoutingVerdict = message match {
    case msg: CcScMidiMessage => routeCc(role, msg, rpnSelector)
    case _: NoteOnScMidiMessage | _: NoteOffScMidiMessage => role match {
      case MpeChannelRole.Member(_) | MpeChannelRole.NonMpeInput(_) => MpeRoutingVerdict.Interpret
      case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardOn(message.channel)
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }
    // The first two of the three per-note control dimensions; CC #74 is the third, in `routeCc`.
    case _: PitchBendScMidiMessage | _: ChannelPressureScMidiMessage => routeControlDimension(role, message)
    case _: PolyPressureScMidiMessage => role match {
      // Forbidden on a Member Channel by the MPE Specification; converted to Channel Pressure for a non-MPE input.
      case MpeChannelRole.Member(_) => MpeRoutingVerdict.Discard
      case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardOn(message.channel)
      case MpeChannelRole.NonMpeInput(_) => MpeRoutingVerdict.Interpret
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }
    case _: ProgramChangeScMidiMessage => routeZoneLevel(role, message)
  }

  /** One of Pitch Bend, Channel Pressure and CC #74: the note's own Expression Value at Member level. */
  private def routeControlDimension(role: MpeChannelRole, message: ChannelScMidiMessage): MpeRoutingVerdict =
    role match {
      case MpeChannelRole.Member(_) => MpeRoutingVerdict.Interpret
      case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardOn(message.channel)
      case MpeChannelRole.NonMpeInput(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }

  /**
   * A Zone-level message: forwarded unmodified on a Master Channel, redirected to the routing Zone's Master Channel
   * for a non-MPE input, and discarded on a Member Channel — the receiver obligation the paper's message-handling
   * section states — or outside every Zone.
   */
  private def routeZoneLevel(role: MpeChannelRole, message: ChannelScMidiMessage): MpeRoutingVerdict = role match {
    case MpeChannelRole.Member(_) => MpeRoutingVerdict.Discard
    case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardOn(message.channel)
    case MpeChannelRole.NonMpeInput(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
    case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
  }

  private def routeCc(role: MpeChannelRole,
                      msg: CcScMidiMessage,
                      rpnSelector: RpnSelector): MpeRoutingVerdict = msg.number match {
    // The MIDI Mode messages are discarded at every role in both input modes: the Tuner is fixed-mode on both
    // sides, and a Mono On reaching an output Member Channel would turn every shared allocation into a note drop.
    case ScMidiCc.OmniModeOff | ScMidiCc.OmniModeOn | ScMidiCc.MonoModeOn | ScMidiCc.PolyModeOn =>
      MpeRoutingVerdict.Discard

    case ScMidiCc.MpeSlide => routeControlDimension(role, msg)

    // The selector of a parameter the Tuner interprets is consumed: `MpeTuner` re-emits a complete sequence of its
    // own for the MCM and for Pitch Bend Sensitivity, and relaying the sender's selector would duplicate it.
    case ScMidiCc.RpnMsb | ScMidiCc.RpnLsb if isInterpreted(rpnSelector) => MpeRoutingVerdict.Discard

    case ScMidiCc.DataEntryMsb if isMcm(rpnSelector) && isMcmChannel(msg.channel) => MpeRoutingVerdict.Interpret

    case ScMidiCc.DataEntryMsb | ScMidiCc.DataEntryLsb if isPbs(rpnSelector) => role match {
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
      case _ => MpeRoutingVerdict.Interpret
    }

    case _ => routeZoneLevel(role, msg)
  }

  /** Whether an MCM received on this channel is valid: MIDI Channel 1 or 16, whatever the channel's current role. */
  private def isMcmChannel(channel: Int): Boolean = channel == 0 || channel == 15

  private def isMcm(rpnSelector: RpnSelector): Boolean =
    rpnSelector == RpnSelector.Rpn(ScMidiRpn.MpeConfigurationMessageMsb, ScMidiRpn.MpeConfigurationMessageLsb)

  private def isPbs(rpnSelector: RpnSelector): Boolean =
    rpnSelector == RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

  private def isInterpreted(rpnSelector: RpnSelector): Boolean = isMcm(rpnSelector) || isPbs(rpnSelector)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeMessageRoutingTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiCc.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala
git commit -m "[#250] Encode the paper's message-handling table in MpeMessageRouting.route"
```

### Task 7: Drive `MpeTuner` from the routing table

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

**Interfaces:**
- Consumes: `MpeMessageRouting.roleOf`, `MpeMessageRouting.route`, `MpeChannelRole`, `MpeRoutingVerdict` (Tasks 5–6).
- Produces: `MpeTuner.process` behaviour only; no new public API.

**Deletions this task makes** (all become dead): `resolveZoneMasterChannel` (with the `TODO #250` above it),
`isMasterChannel`, `forwardOnZoneMasterChannel`, `findZoneForChannel`, `findChannelRole`, `routingZoneForNonMpeInput`,
`processMemberNoteOn`'s `case None` branch and its `TODO #250`, and the `TODO #250` markers at 372-373 and 583-587.

- [ ] **Step 1: Write the failing tests — invert the Member-level Zone-level cases (I3)**

In `MpeTunerTest.scala`, `behavior of "MpeTuner - process() - Zone-level Messages - MPE Input"`, replace the whole
subgroup `// ---- Forwarding to zone Master Channel (single-zone) ----` (lines 2274-2311) with:

```scala
  // ---- Discarding Zone-level messages received on a Member Channel ----

  it should "discard zone-level CCs received on a Member Channel" in new Fixture(tuner7MpeInput) {
    private val zoneLevelCcs = Table(
      ("ccName", "ccNumber", "ccValue"),
      ("Bank Select MSB", ScMidiCc.BankSelectMsb, 1),
      ("Bank Select LSB", ScMidiCc.BankSelectLsb, 0),
      ("Reset All Controllers", ScMidiCc.ResetAllControllers, 0),
      ("Modulation", ScMidiCc.ModulationMsb, 64),
      ("Sostenuto Pedal", ScMidiCc.SostenutoPedal, 127),
      ("Soft Pedal", ScMidiCc.SoftPedal, 127),
      ("Sustain Pedal", ScMidiCc.SustainPedal, 127)
    )
    forAll(zoneLevelCcs) { (_, ccNumber, ccValue) =>
      // When
      val output = tuner.process(CcScMidiMessage(mpeInputChannel, ccNumber, ccValue).asJava)
      // Then
      output shouldBe empty
    }
  }

  it should "discard Program Change received on a Member Channel" in new Fixture(tuner7MpeInput) {
    // When
    private val output = tuner.process(ProgramChangeScMidiMessage(mpeInputChannel, 5).asJava)
    // Then
    output shouldBe empty
  }

  // ---- Forwarding Zone-level messages received on a Master Channel ----

  it should "forward zone-level CCs received on a Master Channel unmodified" in
    new Fixture(dualZoneTunerMpeInput) {
      private val masterChannels = Table("masterChannel", 0, 15)
      forAll(masterChannels) { masterChannel =>
        // When
        val output = tuner.process(CcScMidiMessage(masterChannel, ScMidiCc.SustainPedal, 72).asJava)
        // Then
        extractCc(output) shouldEqual Seq(CcScMidiMessage(masterChannel, ScMidiCc.SustainPedal, 72))
      }
    }

  it should "forward Program Change received on a Master Channel unmodified" in
    new Fixture(dualZoneTunerMpeInput) {
      private val masterChannels = Table("masterChannel", 0, 15)
      forAll(masterChannels) { masterChannel =>
        // When
        val output = tuner.process(ProgramChangeScMidiMessage(masterChannel, 6).asJava)
        // Then
        output.map(_.asScala) shouldEqual Seq(ProgramChangeScMidiMessage(masterChannel, 6))
      }
    }
```

Then replace the subgroup `// ---- Routing to upper zone Master Channel (dual-zone) ----` (lines 2313-2343) with:

```scala
  // ---- Out-of-zone traffic (I2) ----

  it should "discard zone-level messages received on a channel outside every enabled Zone" in
    new Fixture(tuner7MpeInput) {
      // Given
      // tuner7MpeInput: Lower Zone master 0, members 1..7. Channels 8..15 are outside every Zone.
      private val outsideChannels = Table("channel", 8, 12, 15)
      forAll(outsideChannels) { channel =>
        // When / Then
        tuner.process(CcScMidiMessage(channel, ScMidiCc.SustainPedal, 127).asJava) shouldBe empty
        tuner.process(ProgramChangeScMidiMessage(channel, 5).asJava) shouldBe empty
        tuner.process(PitchBendScMidiMessage(channel, 1000).asJava) shouldBe empty
        tuner.process(ChannelPressureScMidiMessage(channel, 90).asJava) shouldBe empty
        tuner.process(CcScMidiMessage(channel, ScMidiCc.MpeSlide, 100).asJava) shouldBe empty
      }
    }

  it should "neither forward nor allocate a note received on a channel outside every enabled Zone" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // When
      private val onOutput = noteOn(10, C4)
      // Then
      onOutput shouldBe empty
      // When
      private val offOutput = noteOff(10, C4)
      // Then
      offOutput shouldBe empty
    }
```

- [ ] **Step 2: Write the failing tests — C4, C5 and N4**

Still in `behavior of "MpeTuner - process() - Zone-level Messages - MPE Input"`, append two subgroups:

```scala
  // ---- Master Channel Zone-level control dimensions (C5) ----

  it should "forward Master Channel CC #74 unmodified" in new Fixture(dualZoneTunerMpeInput) {
    private val masterChannels = Table("masterChannel", 0, 15)
    forAll(masterChannels) { masterChannel =>
      // When
      val output = tuner.process(CcScMidiMessage(masterChannel, ScMidiCc.MpeSlide, 100).asJava)
      // Then
      extractCc(output) shouldEqual Seq(CcScMidiMessage(masterChannel, ScMidiCc.MpeSlide, 100))
    }
  }

  it should "forward Master Channel Channel Pressure unmodified, with no note sounding" in
    new Fixture(dualZoneTunerMpeInput) {
      private val masterChannels = Table("masterChannel", 0, 15)
      forAll(masterChannels) { masterChannel =>
        // When
        val output = tuner.process(ChannelPressureScMidiMessage(masterChannel, 90).asJava)
        // Then
        extractChannelPressures(output) shouldEqual Seq(ChannelPressureScMidiMessage(masterChannel, 90))
      }
    }

  it should "not apply Master Channel CC #74 or Channel Pressure to Member Channel notes" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val noteOutput = noteOn(mpeInputChannel, C4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // When
      private val output = tuner.process(CcScMidiMessage(0, ScMidiCc.MpeSlide, 100).asJava) ++
        tuner.process(ChannelPressureScMidiMessage(0, 90).asJava)
      // Then
      extractCc(output).map(_.channel) should contain only 0
      extractChannelPressures(output).map(_.channel) should contain only 0
      extractCc(output).filter(_.channel == noteChannel) shouldBe empty
    }

  // ---- MIDI Mode messages (N4) ----

  it should "discard the MIDI Mode messages 124-127 at every level" in new Fixture(tuner7MpeInput) {
    private val cases = Table(
      ("ccNumber", "channel"),
      (ScMidiCc.OmniModeOff, 0), (ScMidiCc.OmniModeOff, mpeInputChannel), (ScMidiCc.OmniModeOff, 10),
      (ScMidiCc.OmniModeOn, 0), (ScMidiCc.OmniModeOn, mpeInputChannel), (ScMidiCc.OmniModeOn, 10),
      (ScMidiCc.MonoModeOn, 0), (ScMidiCc.MonoModeOn, mpeInputChannel), (ScMidiCc.MonoModeOn, 10),
      (ScMidiCc.PolyModeOn, 0), (ScMidiCc.PolyModeOn, mpeInputChannel), (ScMidiCc.PolyModeOn, 10)
    )
    forAll(cases) { (ccNumber, channel) =>
      // When / Then
      tuner.process(CcScMidiMessage(channel, ccNumber, 0).asJava) shouldBe empty
    }
  }

  it should "still forward the Channel Mode messages 120-123 received on a Master Channel" in
    new Fixture(tuner7MpeInput) {
      private val ccNumbers = Table("ccNumber",
        ScMidiCc.AllSoundOff, ScMidiCc.ResetAllControllers, ScMidiCc.LocalControl, ScMidiCc.AllNotesOff)
      forAll(ccNumbers) { ccNumber =>
        // When
        val output = tuner.process(CcScMidiMessage(0, ccNumber, 0).asJava)
        // Then
        extractCc(output) shouldEqual Seq(CcScMidiMessage(0, ccNumber, 0))
      }
    }
```

And in `behavior of "MpeTuner - process() - Zone-level Messages - Non-MPE Input"`, append:

```scala
  // ---- MIDI Mode messages (N4) ----

  it should "discard the MIDI Mode messages 124-127" in new Fixture {
    private val ccNumbers = Table("ccNumber",
      ScMidiCc.OmniModeOff, ScMidiCc.OmniModeOn, ScMidiCc.MonoModeOn, ScMidiCc.PolyModeOn)
    forAll(ccNumbers) { ccNumber =>
      // When / Then
      tuner.process(CcScMidiMessage(nonMpeInputChannel, ccNumber, 0).asJava) shouldBe empty
    }
  }

  // ---- No Zone enabled (C4) ----

  it should "discard every Channel Voice and Channel Mode message when no Zone is enabled" in {
    // Given
    val tuner = MpeTuner(initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 0), MpeZone(MpeZoneType.Upper, 0)))
    val channels = Table("channel", 0, 5, 15)
    forAll(channels) { channel =>
      // When / Then
      tuner.process(NoteOnScMidiMessage(channel, C4, 100).asJava) shouldBe empty
      tuner.process(NoteOffScMidiMessage(channel, C4).asJava) shouldBe empty
      tuner.process(PitchBendScMidiMessage(channel, 1000).asJava) shouldBe empty
      tuner.process(CcScMidiMessage(channel, ScMidiCc.SustainPedal, 127).asJava) shouldBe empty
      tuner.process(ProgramChangeScMidiMessage(channel, 5).asJava) shouldBe empty
    }
  }

  it should "still act on a valid MCM when no Zone is enabled" in {
    // Given
    val tuner = MpeTuner(initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 0), MpeZone(MpeZoneType.Upper, 0)))
    // When
    val output = sendMcm(tuner, channel = 0, memberCount = 7)
    // Then
    tuner.zones.lower.memberCount shouldEqual 7
    tuner.inputMode shouldBe MpeInputMode.Mpe
    output should not be empty
  }
```

- [ ] **Step 3: Run the tests to verify they fail for the right reason**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: the new/inverted cases FAIL on assertions (messages forwarded where a discard is expected, nothing emitted
where a forward is expected). No compile errors.

- [ ] **Step 4: Rewrite `processShortMessage` as classify-then-act**

```scala
  private def processShortMessage(message: ShortMessage): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()
    // A Note On with velocity 0 is a Note Off per the MIDI Specification. Normalizing it here, ahead of both the
    // tracker and the router, keeps every downstream decision — routing included — reading a single note-off shape.
    val scMessage = message.asScala match {
      case msg: NoteOnScMidiMessage if msg.velocity == NoteOnScMidiMessage.NoteOffVelocity =>
        NoteOffScMidiMessage(msg.channel, msg.midiNote)
      case other => other
    }
    tracker.send(scMessage)

    scMessage match {
      case msg: ChannelScMidiMessage =>
        val role = MpeMessageRouting.roleOf(_inputMode, _zones, msg.channel)
        val rpnSelector = tracker.rpnSelector(msg.channel)
        MpeMessageRouting.route(role, msg, rpnSelector) match {
          case MpeRoutingVerdict.Discard =>
            // Out-of-zone and wrong-level traffic is normal in a mixed rig, so this stays at trace level.
            logger.trace(s"Discarding $msg received on a channel with role $role")
          case MpeRoutingVerdict.ForwardOn(channel) =>
            buffer += msg.mapChannel(_ => channel).asJava
          case MpeRoutingVerdict.Interpret =>
            interpret(buffer, msg, role, rpnSelector)
        }
      case _ =>
        // System Exclusive, System Common and System Real-Time messages affect the whole system and pass through.
        buffer += message
    }

    buffer.toSeq
  }

  /**
   * Handles the messages [[MpeMessageRouting.route]] decided the Tuner acts upon itself: note allocation, the three
   * Expression Value dimensions at note level, the Non-MPE Polyphonic Key Pressure conversion, the MCM, and Pitch
   * Bend Sensitivity. Every handler receives the role rather than re-deriving it.
   */
  private def interpret(buffer: mutable.Buffer[MidiMessage], msg: ChannelScMidiMessage,
                        role: MpeChannelRole, rpnSelector: RpnSelector): Unit = msg match {
    case m: NoteOnScMidiMessage => processNoteOn(buffer, m, role)
    case m: NoteOffScMidiMessage => processNoteOff(buffer, m, role)
    case m: PitchBendScMidiMessage => processPitchBend(buffer, m, role)
    case m: ChannelPressureScMidiMessage => processChannelPressure(buffer, m, role)
    case m: PolyPressureScMidiMessage => processPolyPressure(buffer, m, role)
    case m: CcScMidiMessage => processCc(buffer, m, role, rpnSelector)
    case m: ProgramChangeScMidiMessage =>
      // `route` never asks for a Program Change to be interpreted; it is forwarded or discarded.
      logger.error(s"Unexpected request to interpret $m")
  }
```

Add `import org.calinburloiu.music.scmidi.ScMidiChannelStateTracker.RpnSelector` to the file's imports.

- [ ] **Step 5: Rework the handlers to take the role**

`processNoteOn` absorbs `processMemberNoteOn` (the Master Channel branch and the `case None` branch both disappear):

```scala
  private def processNoteOn(buffer: mutable.Buffer[MidiMessage], msg: NoteOnScMidiMessage,
                            role: MpeChannelRole): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    allocatorFor(role).foreach { alloc =>
      val zone = currentZone(alloc)
      val isMpeInput = role match {
        case MpeChannelRole.Member(_) => true
        case _ => false
      }
      // In MPE Input Mode the note's Expression Values are initialized from the state remembered for its
      // input Member Channel; in Non-MPE Input Mode there are none to take, and the allocator's defaults
      // apply — which is also what keeps CC #74 off the Member Channel in that mode.
      val expression = Option.when(isMpeInput)(inputExpressionOf(inputChannel, zone))
      val preferredChannel = Option.when(isMpeInput && zone.memberChannels.contains(inputChannel))(inputChannel)

      val result = alloc.allocate(MpeNoteIdentity(inputChannel, midiNote), expression, preferredChannel)
      val outChannel = result.channel

      // Dropped notes are released before every message emitted for the new note: emitting the setup
      // messages first would retune the notes being dropped on their way out.
      result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, DropReason.OnNoteOn))

      // Pitch Bend, CC #74, Channel Pressure, then the Note On. Pitch Bend is emitted unconditionally on
      // a fresh allocation: what goes on the wire is Tuning Pitch Bend + Expression Pitch Bend, and the
      // tuning half is invisible to the allocator — a channel that was unoccupied retains the bend of a
      // note of a different pitch class and has missed every tune() that ran while it was empty. A
      // duplicate Note On changes nothing at all, so it is emitted alone.
      if (!result.isDuplicate) {
        emitPitchBend(buffer, outChannel, alloc)
      }
      emitSlide(buffer, outChannel, result.update)
      emitPressure(buffer, outChannel, result.update)

      buffer += NoteOnScMidiMessage(outChannel, midiNote, velocity).asJava
    }
  }
```

`processNoteOff` loses its Master Channel branch and takes the role:

```scala
  private def processNoteOff(buffer: mutable.Buffer[MidiMessage], msg: NoteOffScMidiMessage,
                             role: MpeChannelRole): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    allocatorFor(role).foreach { alloc =>
      // The Channel Pressure reset applies in Non-MPE Input Mode only: there the Tuner is the controller
      // that synthesized the value, whereas in MPE Input Mode the dimension passes through from the
      // sender and a conforming sender's own pre-release reset reaches the output as an ordinary update.
      val resetPressureOnEmpty = role match {
        case MpeChannelRole.NonMpeInput(_) => true
        case _ => false
      }

      alloc.release(MpeNoteIdentity(inputChannel, midiNote), resetPressureOnEmpty) match {
        case Some(result) =>
          val outChannel = result.channel

          // The reset is the sole control message emitted before the Note Off; every other recomputed
          // value follows it, so that the released note's control state is final at the moment of release.
          if (result.pressureWasReset) emitPressure(buffer, outChannel, result.update)

          buffer += NoteOffScMidiMessage(outChannel, midiNote, velocity).asJava

          if (result.update.pitchBendCents.isDefined) emitPitchBend(buffer, outChannel, alloc)
          emitSlide(buffer, outChannel, result.update)
          if (!result.pressureWasReset) emitPressure(buffer, outChannel, result.update)

        case None =>
          // A `None` result means the identity holds no active count — chiefly after the Tuner dropped
          // the note itself, having already emitted its Note Offs, which is routine and already logged at
          // the drop site. But a stale Note Off after a mid-stream MCM, or a sender resuming after a MIDI
          // panic, would look identical here, so a trace line keeps those cases distinguishable from
          // normal operation.
          logger.trace(s"Discarding Note Off for $midiNote on input channel $inputChannel: " +
            "the identity holds no active count")
      }
    }
  }
```

`processPitchBend`, `processChannelPressure` and the CC #74 branch reach `interpret` only at `Member` level now, so
their mode branches disappear:

```scala
  private def processPitchBend(buffer: mutable.Buffer[MidiMessage], msg: PitchBendScMidiMessage,
                               role: MpeChannelRole): Unit = {
    // Per-note Pitch Bend on an input Member Channel: the note's Expression Pitch Bend. The allocator fans the
    // update out by itself to every output channel holding a note of this input channel.
    allocatorFor(role).foreach { alloc =>
      val pitchBendCents = PitchBendScMidiMessage.convertValueToCents(
        msg.value, currentZone(alloc).memberPitchBendSensitivity)
      emitExpressionUpdateResult(buffer, alloc.updateExpressionPitchBend(msg.channel, pitchBendCents),
        alloc, DropReason.OnPitchBend)
    }
  }

  private def processChannelPressure(buffer: mutable.Buffer[MidiMessage], msg: ChannelPressureScMidiMessage,
                                     role: MpeChannelRole): Unit = {
    // Per-note pressure on an input Member Channel: it belongs to every note active on that channel, wherever the
    // pitch-class invariant placed them.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer, alloc.updatePressure(msg.channel, msg.value), alloc, DropReason.NotExpected)
    }
  }

  private def processPolyPressure(buffer: mutable.Buffer[MidiMessage], msg: PolyPressureScMidiMessage,
                                  role: MpeChannelRole): Unit = {
    // Non-MPE input only: converted to Channel Pressure on the allocated Member Channel, since MPE forbids
    // Polyphonic Key Pressure there. The value is the addressed note's own Expression Value and is averaged with
    // those of the other notes on its output channel.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer,
        alloc.updatePressure(MpeNoteIdentity(msg.channel, msg.midiNote), msg.value), alloc, DropReason.NotExpected)
    }
  }

  private def processCc(buffer: mutable.Buffer[MidiMessage], msg: CcScMidiMessage,
                        role: MpeChannelRole, rpnSelector: RpnSelector): Unit = msg.number match {
    case ScMidiCc.MpeSlide =>
      allocatorFor(role).foreach { alloc =>
        emitExpressionUpdateResult(buffer, alloc.updateSlide(msg.channel, msg.value), alloc, DropReason.NotExpected)
      }
    case ScMidiCc.DataEntryMsb if rpnSelector ==
      RpnSelector.Rpn(ScMidiRpn.MpeConfigurationMessageMsb, ScMidiRpn.MpeConfigurationMessageLsb) =>
      processMcm(buffer, msg.channel, msg.value)
    case _ =>
      processPbs(buffer, msg.channel, msg.number, msg.value, role)
  }
```

`processPbs` branches on the role instead of the input mode:

```scala
  /**
   * Processes an incoming Pitch Bend Sensitivity RPN Data Entry MSB (semitones) or LSB (cents).
   *
   * The destination and the half of the Zone's Pitch Bend Sensitivity being written both follow from the role: a
   * non-MPE input has no Master Channel of its own, so its Pitch Bend Sensitivity configures the routing Zone's
   * Master Channel — the channel its Pitch Bend is redirected to.
   */
  private def processPbs(buffer: mutable.Buffer[MidiMessage], channel: Int, ccNumber: Int, ccValue: Int,
                         role: MpeChannelRole): Unit = role match {
    case MpeChannelRole.NonMpeInput(zone) =>
      val updatedZone = zone.copy(
        masterPitchBendSensitivity = patchPbs(zone.masterPitchBendSensitivity, ccNumber, ccValue))
      applyPbsUpdate(buffer, zone.masterChannel, ccNumber, ccValue, updatedZone, isMaster = true)
    case MpeChannelRole.Master(zone) =>
      val updatedZone = zone.copy(
        masterPitchBendSensitivity = patchPbs(zone.masterPitchBendSensitivity, ccNumber, ccValue))
      applyPbsUpdate(buffer, channel, ccNumber, ccValue, updatedZone, isMaster = true)
    case MpeChannelRole.Member(zone) =>
      val updatedZone = zone.copy(
        memberPitchBendSensitivity = patchPbs(zone.memberPitchBendSensitivity, ccNumber, ccValue))
      applyPbsUpdate(buffer, channel, ccNumber, ccValue, updatedZone, isMaster = false)
    case MpeChannelRole.Outside =>
      logger.error(s"Unexpected request to interpret Pitch Bend Sensitivity on out-of-zone channel $channel")
  }
```

And `allocatorFor` takes a role:

```scala
  /**
   * The allocator of the Zone whose Member Channels a note arriving with this role is allocated to.
   *
   * `Member` and `NonMpeInput` both carry an enabled Zone, and an enabled Zone always has an allocator, so the
   * result is `Some` for every role that [[MpeMessageRouting.route]] sends to an allocating handler. The `None`
   * cases emit nothing, which is the correct behaviour should the invariant ever be broken.
   */
  private def allocatorFor(role: MpeChannelRole): Option[MpeChannelAllocator] = role match {
    case MpeChannelRole.Member(zone) => allocatorOf(zone)
    case MpeChannelRole.NonMpeInput(zone) => allocatorOf(zone)
    case MpeChannelRole.Master(_) | MpeChannelRole.Outside => None
  }

  private def allocatorOf(zone: MpeZone): Option[MpeChannelAllocator] = zone.zoneType match {
    case MpeZoneType.Lower => lowerAllocator
    case MpeZoneType.Upper => upperAllocator
  }
```

- [ ] **Step 6: Delete the superseded helpers**

Remove `resolveZoneMasterChannel` (and the `TODO #250` block above it), `isMasterChannel`,
`forwardOnZoneMasterChannel`, `findZoneForChannel`, `findChannelRole` and `routingZoneForNonMpeInput`. Replace the
deleted `TODO #250` with a narrowed one directly above `processMcm`, covering only what remains open:

```scala
  // TODO #250 Uninterpreted RPN/NRPN traffic must be re-emitted as complete sequences and an invalid MCM ignored
  //  in its entirety; a Zone reconfiguration must reset state only for the channels entering or leaving MPE
  //  control and must not discard the active Tuning.
```

Compile: `mcp__metals__compile-module` with `module = "tuner"`. Fix every reference the deletions break.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

Two existing tests are expected to need no change but must be re-read if they fail, because they encode behaviour this
task deliberately keeps: `"forward Note On/Off on Master Channels without emitting member-channel setup messages"`
(1115-1142) and `"drop Polyphonic Key Pressure received on a Member Channel"` (1644-1653). If a test fails because it
asserted a *Member-level* forward that I3 now discards, invert it as in Step 1 rather than weakening the production
code.

- [ ] **Step 8: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#250] Route MpeTuner messages by channel role"
```

### Task 8: Phase 2 completion

- [ ] **Step 1: Update the architecture documentation**

In [`docs/architecture/tuner/README.md`](../../docs/architecture/tuner/README.md):
- Add `MpeMessageRouting` to the "Key types" MPE bullet: it holds the channel-role classification and the paper's
  message-handling table as a pure function, and `MpeTuner` is a client of it.
- Narrow the #250 "Subject to change" bullet to what is still open: RPN/NRPN sequence grouping with invalid-MCM
  handling, and the scope of the state reset on Zone reconfiguration. Remove the parts this phase closed.

- [ ] **Step 2: Run the full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 3: Verify coverage**

Invoke the `scoverage-inspector` skill. `MpeMessageRouting.scala` is a new file and must reach the 80% target;
confirm `tuner` and `sc-midi` floors hold.

- [ ] **Step 4: Open the pull request**

Use the `contributing` skill's script:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 250/260 "Route MPE Tuner MIDI messages by channel role"
```

`Resolves #260` closes the sub-issue but not #250. Say in the body that it closes gaps I2, C4, I3, C5 and N4, and that
the RPN/NRPN rows of the routing table are left to #261.

---

# Phase 3 — RPN/NRPN sequencing and MCM validity (C6, §2.2(f))

**Sub-issue**: [#261](https://github.com/calinburloiu/microtonalist/issues/261). **PR must not close #250.**

### Task 9: `ForwardRpnSequenceOn`, `rpnSequence`, and the RPN rows of the table

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala`

**Interfaces:**
- Consumes: `MpeChannelRole`, `MpeRoutingVerdict`, `RpnSelector` (Phase 2).
- Produces:
  - `MpeRoutingVerdict.ForwardRpnSequenceOn(channel: Int)`
  - `def rpnSequence(selector: RpnSelector, ccNumber: Int, ccValue: Int, outputChannel: Int): Seq[MidiMessage]`

- [ ] **Step 1: Write the failing tests**

Add to `MpeMessageRoutingTest.scala`, in the `route` block:

```scala
  // ---- Uninterpreted Registered and Non-Registered Parameters ----

  private val fineTuningSelector: RpnSelector =
    RpnSelector.Rpn(ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
  private val nrpnSelector: RpnSelector = RpnSelector.Nrpn(12, 34)

  it should "consume every RPN and NRPN selector CC" in {
    // Given
    val ccNumbers = Table("ccNumber", ScMidiCc.RpnMsb, ScMidiCc.RpnLsb, ScMidiCc.NrpnMsb, ScMidiCc.NrpnLsb)
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    forAll(ccNumbers) { ccNumber =>
      forAll(roles) { role =>
        // When / Then
        MpeMessageRouting.route(role, CcScMidiMessage(inputChannel, ccNumber, 0), fineTuningSelector) shouldEqual
          Discard
      }
    }
  }

  it should "re-emit a complete sequence for a data value of an uninterpreted parameter" in {
    // Given
    val selectors = Table("selector", fineTuningSelector, nrpnSelector)
    val ccNumbers = Table("ccNumber",
      ScMidiCc.DataEntryMsb, ScMidiCc.DataEntryLsb, ScMidiCc.DataIncrement, ScMidiCc.DataDecrement)
    forAll(selectors) { selector =>
      forAll(ccNumbers) { ccNumber =>
        val message = CcScMidiMessage(inputChannel, ccNumber, 64)
        // When / Then
        MpeMessageRouting.route(memberRole, message, selector) shouldEqual Discard
        MpeMessageRouting.route(masterRole, message, selector) shouldEqual ForwardRpnSequenceOn(inputChannel)
        MpeMessageRouting.route(nonMpeRole, message, selector) shouldEqual ForwardRpnSequenceOn(0)
        MpeMessageRouting.route(outsideRole, message, selector) shouldEqual Discard
      }
    }
  }

  it should "discard a data value when no complete parameter is selected" in {
    // Given
    val selectors = Table("selector",
      RpnSelector.None,
      RpnSelector.Rpn(ScMidiRpn.FineTuningMsb, ScMidiRpn.NullLsb),
      RpnSelector.Rpn(ScMidiRpn.NullMsb, ScMidiRpn.FineTuningLsb))
    forAll(selectors) { selector =>
      // When / Then
      MpeMessageRouting.route(masterRole, CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 64), selector) shouldEqual
        Discard
    }
  }

  it should "discard an MCM Data Entry received on a channel other than MIDI Channel 1 or 16" in {
    // Given
    val channels = Table("channel", 1, 5, 14)
    forAll(channels) { channel =>
      // When / Then
      MpeMessageRouting.route(memberRole, CcScMidiMessage(channel, ScMidiCc.DataEntryMsb, 7), mcmSelector) shouldEqual
        Discard
    }
  }

  it should "discard a Data Entry LSB and a Data Increment or Decrement of the MCM" in {
    // Given
    val ccNumbers = Table("ccNumber", ScMidiCc.DataEntryLsb, ScMidiCc.DataIncrement, ScMidiCc.DataDecrement)
    forAll(ccNumbers) { ccNumber =>
      // When / Then
      MpeMessageRouting.route(masterRole, CcScMidiMessage(0, ccNumber, 7), mcmSelector) shouldEqual Discard
    }
  }

  it should "discard a Data Increment or Decrement of Pitch Bend Sensitivity" in {
    // Given
    val ccNumbers = Table("ccNumber", ScMidiCc.DataIncrement, ScMidiCc.DataDecrement)
    forAll(ccNumbers) { ccNumber =>
      // When / Then
      MpeMessageRouting.route(masterRole, CcScMidiMessage(0, ccNumber, 1), pbsSelector) shouldEqual Discard
    }
  }

  behavior of "MpeMessageRouting.rpnSequence"

  it should "render an RPN selector ahead of its value message" in {
    // When
    val messages = MpeMessageRouting
      .rpnSequence(fineTuningSelector, ScMidiCc.DataEntryMsb, 64, outputChannel = 0).map(_.asScala)
    // Then
    messages shouldEqual Seq(
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 64)
    )
  }

  it should "render an NRPN selector ahead of its value message" in {
    // When
    val messages = MpeMessageRouting
      .rpnSequence(nrpnSelector, ScMidiCc.DataIncrement, 1, outputChannel = 15).map(_.asScala)
    // Then
    messages shouldEqual Seq(
      CcScMidiMessage(15, ScMidiCc.NrpnMsb, 12),
      CcScMidiMessage(15, ScMidiCc.NrpnLsb, 34),
      CcScMidiMessage(15, ScMidiCc.DataIncrement, 1)
    )
  }

  it should "render nothing when no parameter is selected" in {
    // When / Then
    MpeMessageRouting.rpnSequence(RpnSelector.None, ScMidiCc.DataEntryMsb, 64, outputChannel = 0) shouldBe empty
  }
```

Add `import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*` to the test's imports.

- [ ] **Step 2: Add the verdict case and an `rpnSequence` stub, then verify the tests fail**

Add `case ForwardRpnSequenceOn(channel: Int)` to `MpeRoutingVerdict` and `def rpnSequence(...): Seq[MidiMessage] = ???`
to `MpeMessageRouting`. Compile the `tuner` module; `MpeTuner`'s verdict match will now warn or fail on exhaustivity —
add a temporary `case MpeRoutingVerdict.ForwardRpnSequenceOn(_) =>` branch that does nothing, to be filled in Task 10.

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeMessageRoutingTest -- -oNCXEHLOPQRMWS"`
Expected: the new cases FAIL — on assertions for the `route` ones, with `scala.NotImplementedError` for the
`rpnSequence` ones.

- [ ] **Step 3: Implement the RPN rows and `rpnSequence`**

In `routeCc`, replace the interpreted-selector case and add the data-value case; the resulting order of cases is:

```scala
    case ScMidiCc.OmniModeOff | ScMidiCc.OmniModeOn | ScMidiCc.MonoModeOn | ScMidiCc.PolyModeOn =>
      MpeRoutingVerdict.Discard

    case ScMidiCc.MpeSlide => routeControlDimension(role, msg)

    // Every selector is consumed, never relayed: the Tuner re-emits a complete sequence of its own ahead of each
    // value message, which is what keeps interleaved RPN/NRPN streams from different input channels from being
    // merged into one another on a shared output channel.
    case ScMidiCc.RpnMsb | ScMidiCc.RpnLsb | ScMidiCc.NrpnMsb | ScMidiCc.NrpnLsb => MpeRoutingVerdict.Discard

    case ScMidiCc.DataEntryMsb | ScMidiCc.DataEntryLsb | ScMidiCc.DataIncrement | ScMidiCc.DataDecrement =>
      routeDataValue(role, msg, rpnSelector)

    case _ => routeZoneLevel(role, msg)
```

and add:

```scala
  /**
   * Routes a Data Entry, Data Increment or Data Decrement, which the currently selected parameter gives its meaning.
   *
   * Three parameters get special treatment. The MCM is accepted only as a Data Entry MSB on MIDI Channel 1 or 16 —
   * the MPE Specification does not use its LSB — and an MCM that fails either test is ignored in its entirety, its
   * selector having already been consumed above. Pitch Bend Sensitivity is accepted at every role but `Outside`. A
   * Data Increment or Decrement of either is discarded: neither the paper nor the MPE Specification covers it, and
   * relaying one would desync the Tuner's stored value from the receiver's, since the Tuner does not interpret the
   * increment.
   *
   * A value message is also discarded when no complete parameter is selected. `RpnSelector.None` is the plain case;
   * a half-set selector — one whose MSB or LSB is still Null — is treated the same way, because
   * [[ScMidiChannelStateTracker]] itself refuses to record a value for one, and relaying a value with no parameter
   * to apply it to is precisely what the closing RPN Null exists to prevent.
   */
  private def routeDataValue(role: MpeChannelRole,
                             msg: CcScMidiMessage,
                             rpnSelector: RpnSelector): MpeRoutingVerdict = rpnSelector match {
    case selector if isMcm(selector) =>
      if (msg.number == ScMidiCc.DataEntryMsb && isMcmChannel(msg.channel)) MpeRoutingVerdict.Interpret
      else MpeRoutingVerdict.Discard
    case selector if isPbs(selector) =>
      val isDataEntry = msg.number == ScMidiCc.DataEntryMsb || msg.number == ScMidiCc.DataEntryLsb
      role match {
        case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
        case _ => if (isDataEntry) MpeRoutingVerdict.Interpret else MpeRoutingVerdict.Discard
      }
    case selector if !isComplete(selector) => MpeRoutingVerdict.Discard
    case _ => role match {
      case MpeChannelRole.Member(_) => MpeRoutingVerdict.Discard
      case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardRpnSequenceOn(msg.channel)
      case MpeChannelRole.NonMpeInput(zone) => MpeRoutingVerdict.ForwardRpnSequenceOn(zone.masterChannel)
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }
  }

  /** Whether both halves of the selected parameter have been set, which is what a value message needs to apply. */
  private def isComplete(rpnSelector: RpnSelector): Boolean = rpnSelector match {
    case RpnSelector.None => false
    case RpnSelector.Rpn(msb, lsb) => msb != ScMidiRpn.NullMsb && lsb != ScMidiRpn.NullLsb
    case RpnSelector.Nrpn(msb, lsb) => msb != ScMidiNrpn.NullMsb && lsb != ScMidiNrpn.NullLsb
  }

  /**
   * Renders a complete Registered or Non-Registered Parameter sequence on an output channel: the selector, then the
   * value message.
   *
   * No closing RPN Null is appended. The paper's Null rule governs the sequences the Tuner ''originates''; appending
   * one to a relayed sequence would invent protocol the sender never sent, and would have to be an NRPN Null for
   * NRPN traffic.
   *
   * @param selector      The parameter selected on the input channel, from [[ScMidiChannelStateTracker.rpnSelector]].
   * @param ccNumber      The value CC number: Data Entry MSB or LSB, Data Increment or Data Decrement.
   * @param ccValue       The value CC value.
   * @param outputChannel The channel the whole sequence is emitted on.
   * @return the sequence, or empty when no parameter is selected and no sequence can be formed.
   */
  def rpnSequence(selector: RpnSelector, ccNumber: Int, ccValue: Int, outputChannel: Int): Seq[MidiMessage] = {
    val selectorCcs = selector match {
      case RpnSelector.Rpn(msb, lsb) => Seq(
        CcScMidiMessage(outputChannel, ScMidiCc.RpnMsb, msb),
        CcScMidiMessage(outputChannel, ScMidiCc.RpnLsb, lsb))
      case RpnSelector.Nrpn(msb, lsb) => Seq(
        CcScMidiMessage(outputChannel, ScMidiCc.NrpnMsb, msb),
        CcScMidiMessage(outputChannel, ScMidiCc.NrpnLsb, lsb))
      case RpnSelector.None => Seq.empty
    }
    if (selectorCcs.isEmpty) Seq.empty
    else (selectorCcs :+ CcScMidiMessage(outputChannel, ccNumber, ccValue)).map(_.asJava)
  }
```

Add `import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*` and `javax.sound.midi.MidiMessage` to the
production file's imports.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeMessageRoutingTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRoutingTest.scala
git commit -m "[#250] Route uninterpreted RPN/NRPN traffic as complete sequences"
```

### Task 10: Emit the sequences from `MpeTuner`

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

**Interfaces:**
- Consumes: `MpeRoutingVerdict.ForwardRpnSequenceOn`, `MpeMessageRouting.rpnSequence` (Task 9).
- Produces: `MpeTuner.process` behaviour only.

- [ ] **Step 1: Write the failing tests**

In `MpeTunerTest.scala`, `behavior of "MpeTuner - process() - Zone-level Messages - Non-MPE Input"`, add a subgroup:

```scala
  // ---- Uninterpreted RPN/NRPN sequences ----

  it should "hold back an uninterpreted RPN selector and re-emit it ahead of the Data Entry" in new Fixture(tuner7) {
    // Given
    private val selectorOutput =
      tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb).asJava) ++
        tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb).asJava)
    // Then
    selectorOutput shouldBe empty

    // When
    private val output = tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.DataEntryMsb, 70).asJava)
    // Then
    extractCc(output) shouldEqual Seq(
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 70)
    )
  }

  it should "re-emit the selector ahead of each value message of an NRPN sequence" in new Fixture(tuner7) {
    // Given
    tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.NrpnMsb, 12).asJava)
    tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.NrpnLsb, 34).asJava)
    // When
    private val output = tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.DataEntryMsb, 70).asJava) ++
      tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.DataEntryLsb, 5).asJava)
    // Then
    extractCc(output) shouldEqual Seq(
      CcScMidiMessage(0, ScMidiCc.NrpnMsb, 12),
      CcScMidiMessage(0, ScMidiCc.NrpnLsb, 34),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 70),
      CcScMidiMessage(0, ScMidiCc.NrpnMsb, 12),
      CcScMidiMessage(0, ScMidiCc.NrpnLsb, 34),
      CcScMidiMessage(0, ScMidiCc.DataEntryLsb, 5)
    )
  }

  it should "keep two interleaved input sequences apart on the output Master Channel" in new Fixture(tuner7) {
    // Given
    // Two senders on different input channels select different parameters, then interleave their Data Entries.
    tuner.process(CcScMidiMessage(2, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb).asJava)
    tuner.process(CcScMidiMessage(2, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb).asJava)
    tuner.process(CcScMidiMessage(3, ScMidiCc.RpnMsb, ScMidiRpn.CoarseTuningMsb).asJava)
    tuner.process(CcScMidiMessage(3, ScMidiCc.RpnLsb, ScMidiRpn.CoarseTuningLsb).asJava)
    // When
    private val output = tuner.process(CcScMidiMessage(2, ScMidiCc.DataEntryMsb, 70).asJava) ++
      tuner.process(CcScMidiMessage(3, ScMidiCc.DataEntryMsb, 60).asJava)
    // Then
    extractCc(output) shouldEqual Seq(
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 70),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.CoarseTuningMsb),
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.CoarseTuningLsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 60)
    )
  }
```

In `behavior of "MpeTuner - process() - Zone-level Messages - MPE Input"`, add:

```scala
  // ---- Uninterpreted RPN/NRPN sequences ----

  it should "re-emit an uninterpreted RPN sequence on the Master Channel it arrived on" in
    new Fixture(tuner7MpeInput) {
      // Given
      tuner.process(CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb).asJava)
      tuner.process(CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb).asJava)
      // When
      private val output = tuner.process(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 70).asJava)
      // Then
      extractCc(output) shouldEqual Seq(
        CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb),
        CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb),
        CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 70)
      )
    }

  it should "discard an uninterpreted RPN sequence received on a Member Channel" in new Fixture(tuner7MpeInput) {
    // Given / When
    private val output =
      tuner.process(CcScMidiMessage(mpeInputChannel, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb).asJava) ++
        tuner.process(CcScMidiMessage(mpeInputChannel, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb).asJava) ++
        tuner.process(CcScMidiMessage(mpeInputChannel, ScMidiCc.DataEntryMsb, 70).asJava)
    // Then
    output shouldBe empty
  }
```

In `behavior of "MpeTuner - MCM Processing - MPE Input"`, subgroup `// ---- Channel-of-receipt gating ----`, replace
the existing `"ignore MCM on non-master channel"` test body with a stronger assertion and add a companion:

```scala
  it should "ignore an MCM received on a channel other than 1 or 16, in its entirety" in
    new Fixture(mpeTunerMpeInput) {
      // When
      private val output = sendMcm(tuner, channel = 5, memberCount = 7)
      // Then
      // Neither the selector nor the Data Entry is relayed, and no reconfiguration happens.
      output shouldBe empty
      extractNoteOffs(output) shouldBe empty
      tuner.zones.lower.memberCount shouldEqual 15
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: the new cases FAIL — nothing is emitted where a sequence is expected (Task 9 left the
`ForwardRpnSequenceOn` branch empty), and the invalid MCM's Data Entry still escapes to the Master Channel.

- [ ] **Step 3: Fill in the `ForwardRpnSequenceOn` branch**

In `processShortMessage`'s verdict match, replace the temporary branch:

```scala
          case MpeRoutingVerdict.ForwardRpnSequenceOn(channel) => msg match {
            case cc: CcScMidiMessage =>
              buffer ++= MpeMessageRouting.rpnSequence(rpnSelector, cc.number, cc.value, channel)
            case _ =>
              // `route` returns this verdict only for a Data Entry, Data Increment or Data Decrement CC.
              logger.error(s"Unexpected RPN sequence verdict for $msg")
          }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

Two existing PBS tests set the selector with an LSB-first pair and previously saw the first selector CC leak to the
output (`"handle PBS LSB (cents) update by forwarding to master channel"` at 2588-2599 and its MPE counterpart at
2692-2704). They assert with `should contain`, so they stay green; if either fails, it is because the leaked CC was
being counted — update the expectation rather than the production code.

- [ ] **Step 5: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#250] Emit complete RPN/NRPN sequences from MpeTuner"
```

### Task 11: The paper amendment and Phase 3 completion

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:239-240, 314, 399, 401`
- Modify: `docs/architecture/tuner/README.md`

- [ ] **Step 1: Amend the paper**

Three surgical edits, in the paper's existing register, no other section touched.

**Edit 1 — §4 preamble, line 399.** After *"…re-emits complete sequences of its own — selector, Data Entry, and a
closing RPN Null (7F 7F) that protects the parameter from a later stray Data Entry — on the appropriate output channel
under the routing rules of Sections 4.2 and 4.3."*, add:

> The Tuner likewise re-emits the traffic of the parameters it does not interpret as complete sequences — the selector
> followed by the value message, on the output channel Section 3.5 assigns it — but without the closing RPN Null,
> which remains reserved for the parameters the Tuner originates. Holding a selector back until a value message
> arrives is what keeps the sequences of two senders from being merged into one another when the routing rules bring
> them onto a shared output channel.

**Edit 2 — §4 preamble, line 401.** Replace *"…is discarded on a Member Channel and forwarded unmodified on the same
Master Channel when received at Zone level…"* with *"…is discarded on a Member Channel and re-emitted as a complete
sequence on the same Master Channel when received at Zone level…"*.

**Edit 3 — §3.5 table row, line 314.** Replace the Zone-level cell of "All other RPN and all NRPN messages" —
*"Forwarded unmodified on the same Master Channel (Section 4)"* — with *"Re-emitted as a complete sequence on the same
Master Channel (Section 4)"*.

**Edit 4 — §3.3 item 4, lines 239-240.** Replace the final sentence *"The traffic of every Registered and
Non-Registered Parameter Number other than RPN 00 00 and RPN 00 06 is redirected like the rest, uninterpreted (Section
4)."* with *"The traffic of every Registered and Non-Registered Parameter Number other than RPN 00 00 and RPN 00 06 is
redirected like the rest, uninterpreted, and re-emitted there as a complete sequence (Section 4)."*

The note-level verdict of *Discarded* is unchanged.

- [ ] **Step 2: Narrow the architecture doc**

In `docs/architecture/tuner/README.md`, reduce the #250 "Subject to change" bullet to the last open item: the scope of
the state reset on Zone reconfiguration and the active Tuning surviving it.

- [ ] **Step 3: Narrow the in-code TODO**

In `MpeTuner.scala`, reduce the `TODO #250` above `processMcm` to:

```scala
  // TODO #250 A Zone reconfiguration must reset state only for the channels entering or leaving MPE control and
  //  must not discard the active Tuning.
```

- [ ] **Step 4: Run the full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Verify coverage**

Invoke the `scoverage-inspector` skill; confirm the floors hold and `MpeMessageRouting.scala` stays at or above 80%.

- [ ] **Step 6: Commit and open the pull request**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md docs/architecture/tuner/README.md \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala
git commit -m "[#250] Amend the paper for uninterpreted RPN/NRPN sequence re-emission"
```

Use the `contributing` skill's script:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 250/261 \
  "Re-emit uninterpreted RPN/NRPN traffic as complete sequences"
```

`Resolves #261` closes the sub-issue but not #250. Say in the body that it closes gap C6 and §2.2(f), and that it
carries the paper amendment mandated by the design's Section 2, decision 1.

---

# Phase 4 — MCM reset scoping and Tuning survival (C3, I1)

**Sub-issue**: [#262](https://github.com/calinburloiu/microtonalist/issues/262). **PR must not close #250.**

Branches from `main`; independent of phases 1–3. See the ordering note above for the one thing to reconcile if phase 2
merges first.

### Task 12: `ScMidiChannelStateTracker.reset(channel)`

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTracker.scala:84-96`
- Test (modify): `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTrackerTest.scala`

**Interfaces:**
- Consumes: nothing.
- Produces: `def reset(channel: Int): Unit` on `ScMidiChannelStateTracker`.

- [ ] **Step 1: Write the failing test**

In `ScMidiChannelStateTrackerTest.scala`, in the `behavior of` block that covers `reset()` (find it with
`grep -n "behavior of\|reset()" sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTrackerTest.scala`),
add:

```scala
  it should "clear the state of a single channel, leaving the other fifteen untouched" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(3, MidiNote.C4, 100))
    tracker.send(CcScMidiMessage(3, ScMidiCc.SustainPedal, 127))
    tracker.send(PitchBendScMidiMessage(3, 1000))
    tracker.send(NoteOnScMidiMessage(4, MidiNote.E4, 90))
    tracker.send(CcScMidiMessage(4, ScMidiCc.SustainPedal, 127))
    tracker.send(PitchBendScMidiMessage(4, 2000))

    // When
    tracker.reset(3)

    // Then
    tracker.activeNotes(3) shouldBe empty
    tracker.ccOption(3, ScMidiCc.SustainPedal) shouldBe None
    tracker.pitchBend(3) shouldEqual 0
    tracker.activeNotes(4) should contain(MidiNote.E4)
    tracker.ccOption(4, ScMidiCc.SustainPedal) shouldEqual Some(127)
    tracker.pitchBend(4) shouldEqual 2000
  }

  it should "reject a channel outside the MIDI range" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.reset(16)
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ScMidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`
Expected: compile error (`reset(Int)` does not exist). Add the stub `def reset(channel: Int): Unit = ???`, recompile,
and confirm the failure is `scala.NotImplementedError`.

- [ ] **Step 3: Implement it**

```scala
  /**
   * Clears the per-channel state of a single channel, returning it to the same state as on a freshly constructed
   * tracker and leaving every other channel untouched. Constructor-supplied defaults are preserved. No-op once
   * [[close]] has been called.
   *
   * @param channel The 0-indexed MIDI channel (0-15) to clear.
   */
  def reset(channel: Int): Unit = {
    MidiRequirements.requireChannel(channel)
    if (!_closed) {
      channelStates(channel) = ChannelState()
    }
  }
```

Place it directly after the no-argument `reset()` and add a cross-reference to it from that method's ScalaDoc.

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ScMidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTracker.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTrackerTest.scala
git commit -m "[#250] Add a per-channel ScMidiChannelStateTracker.reset"
```

### Task 13: `MpeChannelAllocator.retaining`

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala:121-136, 607-643`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala`

**Interfaces:**
- Consumes: `MpeChannelState`, `MpeNoteIdentity`, `MpeZoneStructure`.
- Produces:
  - `MpeChannelAllocator(zone: MpeZoneStructure, retainedStates: Map[Int, MpeChannelState] = Map.empty)` — the
    primary constructor gains a defaulted second parameter, so every existing `MpeChannelAllocator(zone)` call site
    is unchanged.
  - `MpeChannelAllocator.retaining(zone: MpeZoneStructure, from: MpeChannelAllocator, retainedChannels: Set[Int],
    droppedInputChannels: Set[Int]): MpeChannelAllocator`

- [ ] **Step 1: Write the failing tests**

In `MpeChannelAllocatorTest.scala`, add a new `behavior of "MpeChannelAllocator.retaining"` block at the end of the
file (match the class's existing fixture and helper style — read the top of the file first):

```scala
  behavior of "MpeChannelAllocator.retaining"

  // ---- Retained channels ----

  it should "keep the notes, reference counts, Expression Values, pitch class and group of a retained channel" in {
    // Given
    val zone = MpeZone(MpeZoneType.Lower, 7)
    val alloc = MpeChannelAllocator(zone)
    val identity = MpeNoteIdentity(1, MidiNote.C4)
    val expression = ImmutableMpeExpression(pitchBendCents = 20.0, pressure = 70, slide = 100)
    val channel = alloc.allocate(identity, Some(expression)).channel
    alloc.allocate(identity)
    val group = alloc.channelGroupOf(channel)

    // When
    val shrunk = MpeZone(MpeZoneType.Lower, 4)
    val rebuilt = MpeChannelAllocator.retaining(shrunk, alloc,
      retainedChannels = Set(channel), droppedInputChannels = Set.empty)

    // Then
    rebuilt.channelOf(identity) shouldEqual Some(channel)
    rebuilt.referenceCountOf(identity) shouldEqual 2
    rebuilt.channelExpression(channel).pressure shouldEqual 70
    rebuilt.channelExpression(channel).slide shouldEqual 100
    rebuilt.channelPitchClass(channel) shouldEqual Some(MidiNote.C4.pitchClass)
    rebuilt.channelGroupOf(channel) shouldEqual group
  }

  it should "drop the notes of a channel that is not retained" in {
    // Given
    val zone = MpeZone(MpeZoneType.Lower, 7)
    val alloc = MpeChannelAllocator(zone)
    val kept = MpeNoteIdentity(1, MidiNote.C4)
    val dropped = MpeNoteIdentity(2, MidiNote.E4)
    val keptChannel = alloc.allocate(kept).channel
    val droppedChannel = alloc.allocate(dropped).channel
    keptChannel should not equal droppedChannel

    // When
    val rebuilt = MpeChannelAllocator.retaining(zone, alloc,
      retainedChannels = Set(keptChannel), droppedInputChannels = Set.empty)

    // Then
    rebuilt.channelOf(kept) shouldEqual Some(keptChannel)
    rebuilt.channelOf(dropped) shouldEqual None
    rebuilt.isChannelOccupied(droppedChannel) shouldBe false
  }

  it should "drop a note whose input channel left MPE control even when its output channel is retained" in {
    // Given
    val zone = MpeZone(MpeZoneType.Lower, 7)
    val alloc = MpeChannelAllocator(zone)
    val identity = MpeNoteIdentity(6, MidiNote.C4)
    val channel = alloc.allocate(identity, preferredChannel = Some(1)).channel

    // When
    val rebuilt = MpeChannelAllocator.retaining(zone, alloc,
      retainedChannels = Set(channel), droppedInputChannels = Set(6))

    // Then
    rebuilt.channelOf(identity) shouldEqual None
    rebuilt.isChannelOccupied(channel) shouldBe false
  }

  it should "start every channel of the new Zone that is not retained empty" in {
    // Given
    val alloc = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 4))
    alloc.allocate(MpeNoteIdentity(1, MidiNote.C4))

    // When
    val grown = MpeZone(MpeZoneType.Lower, 7)
    val rebuilt = MpeChannelAllocator.retaining(grown, alloc,
      retainedChannels = Set.empty, droppedInputChannels = Set.empty)

    // Then
    rebuilt.activeChannelCount shouldEqual 0
    rebuilt.activeAllocations shouldBe empty
  }

  // ---- Over-subscribed groups ----

  it should "keep working when a retained channel's group is over-subscribed in the smaller Zone" in {
    // Given
    // A 10-Member Zone has an Expression Group of 3; a 3-Member Zone has one of 2. Three notes of the same pitch
    // class on different input channels cannot share the Pitch Class Group channel, so beyond the first they
    // occupy Expression Group channels — more of them than the smaller Zone's Expression Group can hold.
    val alloc = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 10))
    val identities = Seq(
      MpeNoteIdentity(1, MidiNote.C4), MpeNoteIdentity(2, MidiNote.C4), MpeNoteIdentity(3, MidiNote.C4))
    val channels = identities.map(alloc.allocate(_).channel).toSet

    // When
    val small = MpeZone(MpeZoneType.Lower, 3)
    val rebuilt = MpeChannelAllocator.retaining(small, alloc,
      retainedChannels = channels.intersect(small.memberChannels.toSet),
      droppedInputChannels = Set.empty)

    // Then
    // Nothing throws, and a fresh note still lands on a Member Channel of the new Zone.
    val result = rebuilt.allocate(MpeNoteIdentity(1, MidiNote.G4))
    small.memberChannels should contain(result.channel)
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"`
Expected: compile error (`retaining` does not exist). Add `def retaining(...): MpeChannelAllocator = ???` to the
companion, recompile, and confirm the failures are `scala.NotImplementedError`.

- [ ] **Step 3: Make the allocator's channel states seedable**

Replace lines 121-136 of `MpeChannelAllocator.scala`:

```scala
private[tuner] class MpeChannelAllocator(private val zone: MpeZoneStructure,
                                         retainedStates: Map[Int, MpeChannelState] = Map.empty) {

  import MpeChannelAllocator.*

  /**
   * Data structures with allocation information, keyed by output Member Channel. A channel present in
   * `retainedStates` adopts that state — notes, reference counts, Expression Values, pitch class and group — and
   * every other Member Channel of the Zone starts empty.
   */
  private val channelStates: Map[Int, MpeChannelState] =
    zone.memberChannels.map(ch => ch -> retainedStates.getOrElse(ch, MpeChannelState(ch))).toMap

  /**
   * The output Member Channel each active Note Identity is bound to, derived from the channel states so that a
   * transplanted state and its bindings can never disagree.
   */
  private val noteChannels: mutable.HashMap[MpeNoteIdentity, Int] = mutable.HashMap.from(
    for {
      (channel, state) <- channelStates
      noteIdentity <- state.noteIdentities
    } yield noteIdentity -> channel)

  /**
   * The logical clock, resumed past the newest event of any transplanted state so that timestamps stay
   * monotonic across a Zone reconfiguration.
   */
  private var _time: Long =
    channelStates.values.map(state => Math.max(state.lastNoteOnTime, state.lastNoteOffTime)).maxOption.getOrElse(0L)
```

Also extend the class ScalaDoc with a sentence on the reconfiguration path, pointing at
[[MpeChannelAllocator.retaining]].

- [ ] **Step 4: Implement `retaining`**

In the companion object (`MpeChannelAllocator.scala:608`):

```scala
  /**
   * Builds the allocator of a reconfigured Zone, transplanting the state of the channels that keep their role
   * across the reconfiguration, as the paper's Zone-configuration section requires: "Channels of a Zone untouched
   * by the reconfiguration keep their notes and state."
   *
   * A retained channel may end up in a group holding more occupied channels than the new Zone's group size allows.
   * No invariant breaks: the allocation algorithm reads the group counts only to decide whether a group has room,
   * so an over-subscribed group simply admits no new channel until notes are released.
   *
   * @param zone                 The reconfigured Zone.
   * @param from                 The allocator of the same Zone before the reconfiguration.
   * @param retainedChannels     The output Member Channels that keep their role. Any channel not listed — and any
   *                             listed channel that the new Zone no longer contains — starts empty, so its notes
   *                             are dropped.
   * @param droppedInputChannels Input channels that left or changed their MPE role. A note that arrived on one of
   *                             them is dropped even when its output channel is retained: the performer's Note Off
   *                             will arrive on a channel that is no longer under this Zone's control and would be
   *                             discarded, leaving the note hanging.
   */
  def retaining(zone: MpeZoneStructure,
                from: MpeChannelAllocator,
                retainedChannels: Set[Int],
                droppedInputChannels: Set[Int]): MpeChannelAllocator = {
    val retainedStates = from.statesOf(retainedChannels)
    for {
      state <- retainedStates.values
      noteIdentity <- state.noteIdentities if droppedInputChannels.contains(noteIdentity.inputChannel)
    } {
      state.removeNote(noteIdentity, from._time)
    }
    MpeChannelAllocator(zone, retainedStates)
  }
```

and, in the class, next to the other state-inspection accessors:

```scala
  /** The per-channel state of the given channels, for transplanting into a reconfigured Zone's allocator. */
  private def statesOf(channels: Set[Int]): Map[Int, MpeChannelState] =
    channelStates.view.filterKeys(channels.contains).toMap
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala
git commit -m "[#250] Add MpeChannelAllocator.retaining for Zone reconfiguration"
```

### Task 14: The active Tuning survives an MCM (I1)

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:100-111, 149-158`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

**Interfaces:**
- Consumes: nothing.
- Produces: `MpeTuner.tuning` behaviour only.

- [ ] **Step 1: Write the failing test**

In `MpeTunerTest.scala`, `behavior of "MpeTuner - MCM Processing - MPE Input"`, subgroup
`// ---- Effects on active notes / other state ----`, add:

```scala
  it should "keep the active Tuning when an MCM reconfigures the Zones" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      tuner.tuning shouldEqual quarterCommaMeantone
      // When
      sendMcm(tuner, channel = 0, memberCount = 7)
      // Then
      tuner.tuning shouldEqual quarterCommaMeantone
      // And a note sounded afterwards is still tuned by it: E is -14 cents in quarter-comma meantone.
      private val output = noteOn(2, E4)
      private val noteChannel = extractNoteOns(output).head.channel
      extractPitchBendsWithCents(output) should contain((noteChannel, -14))
    }

  it should "restore the Standard Tuning on reset()" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // When
      tuner.reset()
      // Then
      tuner.tuning shouldEqual Tuning.Standard
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: the first case FAILS — the Tuning is back to `Tuning.Standard` after the MCM. The second PASSES already;
it is a regression guard for the move.

- [ ] **Step 3: Move the Tuning reset into `reset()`**

Delete `_tuning = Tuning.Standard` from `resetState()` (line 153) and add it to `reset()`, next to the input-mode and
Zone restoration:

```scala
  override def reset(): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()
    // Emit Note Off for every active note before switching input mode / zone layout,
    // so downstream receivers are never left with hanging notes (MPE spec Section 2.1.4).
    stopAllNotes(buffer)
    _zones = initialZones
    _inputMode = initialInputMode
    // Full re-initialization restores the Standard Tuning; an in-band Zone reconfiguration must not, since
    // nothing in the paper sanctions discarding the performer's active Tuning.
    _tuning = Tuning.Standard
    resetState()
    warnOnNonMpeInputWithBothZones()
    buffer ++= configurationMessages()
    buffer.toSeq
  }
```

Update `resetState()`'s ScalaDoc so it no longer claims to clear the Tuning.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#250] Keep the active Tuning across an MCM reconfiguration"
```

### Task 15: Scope the MCM state reset to the affected channels (C3)

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:394-432, 545-576`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

**Interfaces:**
- Consumes: `findChannelRole` (existing, `MpeTuner.scala:538-543`) — or `MpeMessageRouting.roleOf` when phase 2 has
  already merged; `ScMidiChannelStateTracker.reset(channel)` (Task 12), `MpeChannelAllocator.retaining` (Task 13).
- Produces: `MpeTuner.process` behaviour only.

- [ ] **Step 1: Write the failing tests**

In `MpeTunerTest.scala`, `behavior of "MpeTuner - MCM Processing - MPE Input"`, replace the existing
`"stop all active notes when MCM is received"` test (2447-2456) with a scoped pair and add the rest of the subgroup:

```scala
  it should "stop the notes of channels leaving MPE control when an MCM shrinks a Zone" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      // Default zones: Lower Zone master 0, members 1..15. Occupy a channel that will survive the shrink and one
      // that will not, by sending each note on the input Member Channel it prefers.
      private val keptOutput = noteOn(2, C4)
      private val droppedOutput = noteOn(12, E4)
      private val keptChannel = extractNoteOns(keptOutput).head.channel
      private val droppedChannel = extractNoteOns(droppedOutput).head.channel
      keptChannel shouldEqual 2
      droppedChannel shouldEqual 12

      // When
      // The Lower Zone shrinks to members 1..7, so channels 8..15 leave MPE control.
      private val output = sendMcm(tuner, channel = 0, memberCount = 7)

      // Then
      private val noteOffs = extractNoteOffs(output)
      noteOffs should contain(NoteOffScMidiMessage(droppedChannel, E4))
      noteOffs.filter(_.channel == keptChannel) shouldBe empty
    }

  it should "keep the notes of channels untouched by the reconfiguration sounding and tunable" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      noteOn(2, C4)
      // When
      sendMcm(tuner, channel = 0, memberCount = 7)
      // Then
      // The retained note is still known: retuning emits a Pitch Bend for its channel.
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      extractPitchBends(tuneOutput).map(_.channel) should contain(2)
      // And its Note Off is still honoured, on the same output channel.
      extractNoteOffs(noteOff(2, C4)) should contain(NoteOffScMidiMessage(2, C4))
    }

  it should "drop a note whose input channel leaves MPE control even when its output channel is retained" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // Lower Zone master 0, members 1..7. A Member Channel note is allocated to its own input channel by
      // preference, so occupy Member Channel 6 first; the note under test then arrives on the same input channel
      // and is allocated elsewhere, on a channel the reconfiguration will keep.
      noteOn(6, C4)
      private val output = noteOn(6, D4)
      private val outChannel = extractNoteOns(output).head.channel
      outChannel should be <= 4

      // When
      // The Lower Zone shrinks to members 1..4: input channel 6 leaves MPE control while `outChannel` stays.
      private val mcmOutput = sendMcm(tuner, channel = 0, memberCount = 4)

      // Then
      // The note is stopped on its retained output channel, and its stale Note Off then produces nothing at all.
      extractNoteOffs(mcmOutput) should contain(NoteOffScMidiMessage(outChannel, D4))
      noteOff(6, D4) shouldBe empty
    }

  it should "reset the tracked control state of an affected channel only" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      // Seed the per-input-channel control state of a channel that survives and one that does not.
      slide(2, 100)
      slide(12, 100)
      // When
      sendMcm(tuner, channel = 0, memberCount = 7)
      // Then
      // A note on the surviving channel is seeded from the retained CC #74; the reconfigured channel is gone.
      private val output = noteOn(2, C4)
      extractSlides(output).map(_.value) should contain(100)
    }

  it should "reset the state of channels handed from one Zone to the other" in
    new Fixture(dualZoneTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      // Lower Zone master 0, members 1..7; Upper Zone master 15, members 8..14.
      private val output = noteOn(6, C4)
      extractNoteOns(output).head.channel shouldEqual 6

      // When
      // An MCM enlarging the Upper Zone to 10 Members shrinks the Lower Zone to 4 by overlap resolution, so
      // channels 5..7 pass from Lower Member to Upper Member: they leave and re-enter MPE control.
      private val mcmOutput = sendMcm(tuner, channel = 15, memberCount = 10)

      // Then
      extractNoteOffs(mcmOutput) should contain(NoteOffScMidiMessage(6, C4))
      tuner.zones.lower.memberCount shouldEqual 4
      tuner.zones.upper.memberCount shouldEqual 10
    }
```

**On the third test.** It is the one that proves refinement 1, so do not weaken it: it needs a note whose *input*
channel is affected while its *output* channel is not, which the "occupy the preferred channel first" setup produces.
If the allocator places `D4` somewhere other than a channel ≤ 4, read the allocation it actually made and adjust the
assertion to the observed channel — but keep the invariant the test is about: the output channel must be one the
reconfiguration retains.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL — today every note is stopped and every channel's state discarded, so the "kept" assertions fail.

- [ ] **Step 3: Add the affected-channel computation**

In `MpeTuner`:

```scala
  /**
   * The channels whose Zone assignment changes across a reconfiguration — the paper's channels "entering or leaving
   * MPE control".
   *
   * Assignments are compared rather than the sets of MPE-controlled channels differenced: a channel handed from one
   * Zone's Member Channels to the other's has both left and entered MPE control, and a set difference would miss it.
   */
  private def affectedChannels(before: MpeZones, after: MpeZones): Set[Int] =
    (0 until MidiChannelCount).filter(ch => assignmentOf(before, ch) != assignmentOf(after, ch)).toSet

  /** A channel's Zone assignment: its Zone's type and whether it is that Zone's Master Channel. */
  private def assignmentOf(zones: MpeZones, channel: Int): Option[(MpeZoneType, Boolean)] =
    findChannelRole(zones.lower, channel)
      .orElse(findChannelRole(zones.upper, channel))
      .map { case (zone, isMaster) => (zone.zoneType, isMaster) }
```

**If phase 2 has already merged**, `findChannelRole` no longer exists; write `assignmentOf` against the router
instead, which is equivalent:

```scala
  private def assignmentOf(zones: MpeZones, channel: Int): Option[(MpeZoneType, Boolean)] =
    MpeMessageRouting.roleOf(MpeInputMode.Mpe, zones, channel) match {
      case MpeChannelRole.Master(zone) => Some((zone.zoneType, true))
      case MpeChannelRole.Member(zone) => Some((zone.zoneType, false))
      case MpeChannelRole.NonMpeInput(_) | MpeChannelRole.Outside => None
    }
```

and in the `MpeTuner` companion:

```scala
  /** The number of MIDI channels. */
  private val MidiChannelCount: Int = 16

  /** Every MIDI channel, the scope of the state reset performed by a full re-initialization. */
  private val AllChannels: Set[Int] = (0 until MidiChannelCount).toSet
```

- [ ] **Step 4: Rescope `stopAllNotes` to `stopNotesOn`**

```scala
  /**
   * Emits a Note Off for every note the Tuner currently considers active on the given channels: the allocators' own
   * bindings for Member Channel notes, and — in MPE Input Mode — the Master Channel notes the tracker holds, which
   * are forwarded on the channel they arrived on.
   *
   * A Member Channel note counts as being on `channels` when either its output channel or the input channel it
   * arrived on is among them. A note whose input channel leaves MPE control must go even if its output channel
   * stays: the performer's Note Off would arrive on a channel that is no longer under any Zone's control and be
   * discarded, leaving the note hanging.
   *
   * Member Channel notes get one Note Off per Note On forwarded for them, as [[emitDroppedNoteOffs]] does,
   * discharging the one-Note-Off-per-Note-On obligation of the paper's "Note Identity and Reference Counting"
   * section. Master Channel notes get exactly one each: they bypass the allocator, and
   * [[ScMidiChannelStateTracker]] models a channel's active notes as a set, so no count is available for them.
   */
  private def stopNotesOn(buffer: mutable.Buffer[MidiMessage], channels: Set[Int]): Unit = {
    for {
      alloc <- Seq(lowerAllocator, upperAllocator).flatten
      (noteIdentity, outChannel) <- alloc.activeAllocations
      if channels.contains(outChannel) || channels.contains(noteIdentity.inputChannel)
      _ <- 1 to alloc.referenceCountOf(noteIdentity)
    } {
      buffer += NoteOffScMidiMessage(outChannel, noteIdentity.midiNote).asJava
    }

    if (_inputMode == MpeInputMode.Mpe) {
      // TODO #254 One Note Off per active note, not one per forwarded Note On: the tracker holds a set of
      //   active notes with no reference count, so a Master Channel note struck twice leaves one unmatched
      //   Note On downstream.
      for {
        zone <- Seq(lowerZone, upperZone) if zone.isEnabled && channels.contains(zone.masterChannel)
        midiNote <- tracker.activeNotes(zone.masterChannel)
      } {
        buffer += NoteOffScMidiMessage(zone.masterChannel, midiNote).asJava
      }
    }
  }
```

In `reset()`, replace `stopAllNotes(buffer)` with `stopNotesOn(buffer, AllChannels)`.

- [ ] **Step 5: Rescope `processMcm`**

```scala
  private def processMcm(buffer: mutable.Buffer[MidiMessage], channel: Int, memberCount: Int): Unit = {
    assert(channel == 0 || channel == 15, "MCM messages are only sent to channel 0 or 15!")
    // Per MPE spec Section 2.4, receiving MCM resets PBS to defaults
    val (zoneType, newZone) = if (channel == 0)
      (MpeZoneType.Lower, MpeZone(MpeZoneType.Lower, memberCount))
    else
      (MpeZoneType.Upper, MpeZone(MpeZoneType.Upper, memberCount))

    logger.info(s"MCM received on channel $channel: configuring $zoneType zone with $memberCount member channel(s)...")

    val zonesBefore = _zones
    val zonesAfter = _zones.update(newZone)
    // Only the channels entering or leaving MPE control are reset; a Zone untouched by the reconfiguration keeps
    // its notes and its state, as the paper's Zone-configuration section requires.
    val affected = affectedChannels(zonesBefore, zonesAfter)
    val otherZoneBefore = if (channel == 0) zonesBefore.upper else zonesBefore.lower

    // Stop the affected notes while the old Zone structure and allocators are still in place.
    stopNotesOn(buffer, affected)

    _zones = zonesAfter
    affected.foreach(tracker.reset)
    lowerAllocator = rebuildAllocator(lowerAllocator, lowerZone, affected)
    upperAllocator = rebuildAllocator(upperAllocator, upperZone, affected)

    // Forward MCM for the updated zone. PBS is not sent because the downstream receiver
    // resets PBS to defaults upon receiving MCM (MPE spec Section 2.4).
    val updatedZone = if (channel == 0) lowerZone else upperZone
    logger.info(s"$zoneType zone updated: $updatedZone")
    buffer ++= mcmMessages(updatedZone)

    // Forward MCM for the other zone only if it was changed by overlap resolution
    val otherZoneAfter = if (channel == 0) upperZone else lowerZone
    if (otherZoneAfter != otherZoneBefore) {
      val otherZoneType = if (channel == 0) MpeZoneType.Upper else MpeZoneType.Lower
      logger.info(s"$otherZoneType zone adjusted by overlap resolution: $otherZoneAfter")
      buffer ++= mcmMessages(otherZoneAfter)
    }

    // Switch to MPE input mode
    _inputMode = MpeInputMode.Mpe
  }

  /**
   * Rebuilds a Zone's allocator after a reconfiguration, transplanting the state of every Member Channel the
   * reconfiguration left untouched. A Zone that is now disabled loses its allocator altogether.
   */
  private def rebuildAllocator(previous: Option[MpeChannelAllocator],
                               zone: MpeZone,
                               affected: Set[Int]): Option[MpeChannelAllocator] = {
    if (!zone.isEnabled) None
    else previous match {
      case Some(alloc) => Some(MpeChannelAllocator.retaining(zone, alloc,
        retainedChannels = zone.memberChannels.toSet -- affected, droppedInputChannels = affected))
      case None => Some(MpeChannelAllocator(zone))
    }
  }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS. The `"reset PBS to defaults when MCM is received"` test (2458-2469) must stay green — the PBS half is
already conformant and this task must not disturb it.

- [ ] **Step 7: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#250] Reset only the channels entering or leaving MPE control on an MCM"
```

### Task 16: Phase 4 completion

- [ ] **Step 1: Remove this phase's `TODO #250` items and confirm the survivors**

Remove the C3 and I1 clauses from the `TODO #250` marker. **Whether the marker itself goes depends on merge order:**
if phases 2 and 3 have already merged, this phase removes its last clause and the marker with it; if they have not,
leave the marker in place carrying only their outstanding items. Whichever phase merges last owns the deletion.

Once all four have merged, verify:

```bash
rtk proxy grep -rn "TODO #250\|TODO #154" --include=*.scala .
rtk proxy grep -rn "TODO #253\|TODO #254" --include=*.scala tuner/src/main
```

Expected: no `TODO #250` and no `TODO #154` anywhere; `TODO #253` (`inputExpressionOf`) and `TODO #254`
(`stopNotesOn`) both still present. Re-read `TODO #254` and confirm its wording is still accurate after the
rescoping — it describes the Master Channel note branch, which this phase filtered by channel but did not otherwise
change.

- [ ] **Step 2: Update the architecture documentation**

In `docs/architecture/tuner/README.md`:
- Remove the C3/I1 items from the #250 "Subject to change" bullet — and the bullet itself if phases 2 and 3 have
  already merged, on the same last-one-out rule as the code marker.
- Mention that the allocator can be rebuilt for a reconfigured Zone through `MpeChannelAllocator.retaining`. (The
  `MpeMessageRouting` entry under "Key types" comes from Task 8, in phase 2.)
- Leave the `TODO #253` and `TODO #254` bullets in place.

- [ ] **Step 3: Run the full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 4: Verify coverage**

Invoke the `scoverage-inspector` skill. Confirm `tuner` ≥ 80 stmt / 75 branch and `sc-midi` ≥ 62 stmt / 44 branch, and
that `MpeMessageRouting.scala`, `MpeTuner.scala`, `MpeChannelAllocator.scala`, `ScMidiChannelStateTracker.scala` and
`PitchBendSensitivity.scala` are at or above where they started. Iterate until met.

- [ ] **Step 5: Commit and open the pull request**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        docs/architecture/tuner/README.md
git commit -m "[#250] Remove the routing conformance TODOs and update the tuner architecture doc"
```

Use the `contributing` skill's script:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 250/262 \
  "Reset only the channels entering or leaving MPE control on a Zone reconfiguration"
```

`Resolves #262` closes the sub-issue but not #250 — no phase PR closes the parent. Say in the body that it closes
gaps C3 and I1. Once all four sub-issues are closed, close #250 by hand.

---

## Spec coverage

| Design item | Task(s) |
|---|---|
| §3.1 `MpeMessageRouting` — `MpeChannelRole`, `MpeRoutingVerdict`, `roleOf`, `route`, `rpnSequence` | 5, 6, 9 |
| §3.2 No separate RPN sequencer (selector read from the tracker) | 9, 10 |
| §3.3 `MpeTuner` classify-then-act; three helpers deleted; `allocatorFor(role)`; `case None` deleted | 7 |
| §3.4 `ScMidiChannelStateTracker.reset(channel)` | 12 |
| §3.4 `ScMidiCc` Channel Mode constants 122, 124–127 | 6 |
| §3.4 `PitchBendSensitivityMessages.create` MSB-first | 1 |
| §4 routing table — Channel Voice rows, Zone-level rows, MIDI Mode row | 6 |
| §4 routing table — selector and data-value rows; rules 1–3 | 9 |
| §4 rule 4 (discards logged at `trace`) | 7 |
| §4 the three cells that become plain forwards (Master notes, Non-MPE Pitch Bend) | 7 |
| §5 I3 | 6, 7 |
| §5 I2 and C4 | 6, 7 |
| §5 C5 | 6, 7 |
| §5 N4 | 6, 7 |
| §5 C6 and §2.2(f) | 9, 10 |
| §5 P7 (Null + MSB-first across all three emitters) | 1, 2, 3 |
| §5 I1 | 14 |
| §5 C3 (affected channels, `stopNotesOn`, `tracker.reset`, `retaining`) | 13, 15 |
| §6 Paper amendment (three edits) | 11 |
| §7 Four phased PRs, four sub-issues (none resolving #250 — closed by hand) | 4, 8, 11, 16 |
| §8 `MpeMessageRoutingTest`, `MpeTunerTest`, `MpeChannelAllocatorTest`, `ScMidiChannelStateTrackerTest`, `PitchBendSensitivityTest`, `MonophonicPitchBendTunerTest` | 1, 5, 6, 7, 9, 10, 12, 13, 14, 15 |
| §7 `docs/architecture/tuner/README.md` — Key types and "Subject to change" | 8, 11, 16 |
| §9 Out of scope — `TODO #253` and `TODO #254` survive | 16 |
