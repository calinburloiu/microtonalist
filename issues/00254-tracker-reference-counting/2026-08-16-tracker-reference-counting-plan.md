# Note Reference Counting in `ScMidiChannelStateTracker` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date**: 2026-08-16
- **Issue**: [#254](https://github.com/calinburloiu/microtonalist/issues/254) — "ScMidiChannelStateTracker: track note
  reference counts so duplicate Note Ons get matching Note Offs"
- **Base commit**: `979a1d4` — "[#250] Reconcile the four MPE Tuner routing conformance phases (#270)"

**Goal:** Give `ScMidiChannelStateTracker` a per-note reference count so that a note struck twice needs two Note Offs to
go silent, and make its two consumers — `MonophonicPitchBendTuner` and `MpeTuner` — correct under that rule.

**Architecture:** The tracker's private `ActiveNote` gains a `referenceCount`; a Note On for an already-active note
increments it *and* moves the entry to the end of the `LinkedHashMap`, redefining `orderedActiveNotes` as "in order of
their most recent Note On". Both release paths (Note Off, and Note On with velocity 0) delegate to one `releaseNote`
helper that decrements and removes only at zero. `MonophonicPitchBendTuner` then drops the synthetic Note Off it used
to force that reordering and gains a count-aware guard in `turnNoteOff`; `MpeTuner.stopNotesOn` gains the per-reference
inner loop its Member Channel branch already has.

**Tech Stack:** Scala 3, sbt 1 (via `sbtn` on the BSP server), ScalaTest 3 (`AnyFlatSpec` + `Matchers`), Metals MCP for
compiling, scoverage for coverage.

**Spec:** [`2026-08-16-tracker-reference-counting-design.md`](2026-08-16-tracker-reference-counting-design.md) — the
approved design. Decisions D1–D4 are settled; Section 8 lists what is out of scope. Read it alongside this plan.

## Global Constraints

- **Do not commit anything, and do not create a branch.** Every task below ends with the changes left in the working
  tree. This deliberately replaces the "Commit" step the plan template normally ends each task with.
- **Do not touch `docs/architecture/tuner/mpe-tuner-paper.md`.** It has unstaged edits from a parallel session, and per
  design Section 7 the paper needs no change for this issue — the code is catching up to the paper, not the reverse.
- **Ignore every other directory under `issues/`.** They belong to unrelated work.
- **Strict TDD, red/green/refactor.** Never write logic without a preceding failing test. When the compiler forces it,
  add the thinnest possible stub (`???` body, no logic) so the tests *compile*, then confirm they fail for the right
  reason — a failing assertion or `NotImplementedError`, never a compile error.
- **Compile with the Metals MCP**: `mcp__metals__compile-module` with `module = "sc-midi"` / `"tuner"` (and
  `"sc-midi-test"` / `"tuner-test"` for test sources), or `mcp__metals__compile-full`. Fall back to `sbtn` only if
  Metals is unavailable.
- **Run tests with `sbtn`** (Metals MCP cannot run tests with this BSP setup), always with the reporter flags
  `-- -oNCXEHLOPQRMWS`.
- **Scala conventions**: brace syntax (not Scala 3 significant indentation), 2-space indent, 120-column lines, no
  `return`, no `new` when instantiating, plain `class` (not `case class`) for mutable data structures, ScalaDoc on every
  public identifier.
- **Test conventions**: `Given` / `When` / `Then` comments, no `if` statements in tests, new cases placed in the
  matching `behavior of` section near a similar test, fixtures reused rather than duplicated.
- **Coverage floors** (from `build.sbt`): `sc-midi` = 67% statement / 52% branch; `tuner` = 80% / 80%. Floors may never
  be lowered; changed files should meet the 80% target.
- **License headers**: the `.githooks/pre-commit` hook adds them for `.scala`; never add or edit them by hand. A
  `PreToolUse` hook on `Read` hides the ~16-line header, so files appear to start around line 17 with **real line
  numbers preserved**.

---

## File Structure

| File | Responsibility after this change |
|------|----------------------------------|
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTracker.scala` | Owns the reference count: `ActiveNote.referenceCount`, the reordering Note On branch, the `releaseNote` helper, the `referenceCount` accessor |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTrackerTest.scala` | Pins counting, ordering, clearing, and validation semantics |
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTuner.scala` | Reads the tracker's ordering instead of forcing it; releases a note only when its last reference is discharged |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTunerTest.scala` | Pins the duplicate-Note-On and partial-release traces |
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` | `stopNotesOn` emits one Note Off per forwarded Note On for Master Channel notes too |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` | Pins the twice-struck Master Channel note on Zone reconfiguration |
| `docs/architecture/sc-midi/README.md` | Tracker bullet mentions reference counting and the ordering rule |
| `docs/architecture/tuner/README.md` | Loses the TODO #254 "Subject to change" bullet |

**Why this order.** Task 1 is safe to land alone: while the tracker counts references, `MonophonicPitchBendTuner`'s
synthetic Note Off decrements the count straight back to zero and removes the entry, so its observable behaviour is
byte-for-byte what it is today, and `MpeTuner` reads the count nowhere yet. Tasks 2 and 3 are independent of each other
and both depend only on Task 1.

**Verified before planning** (`mcp__metals__get-usages`): outside the tracker and its own test, `orderedActiveNotes` is
used only in `MonophonicPitchBendTuner` (lines 100, 190, 192, 232), `isNoteActive` only in `MonophonicPitchBendTuner`
(line 137), and `activeNotes` only in `MpeTuner` (line 608). There is no third consumer to update.

---

## Task 1: Reference counting in `ScMidiChannelStateTracker`

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTracker.scala:58-79` (`send`),
  `:118-160` (accessors), `:503` (`ActiveNote`), `:24-46` (class ScalaDoc)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTrackerTest.scala`
- Modify: `docs/architecture/sc-midi/README.md:90-93`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `def referenceCount(channel: Int, midiNote: MidiNote): Int` on `ScMidiChannelStateTracker` — the number of Note On
    messages received for that note on that channel that no Note Off has yet discharged; `0` if the note is not active.
    Throws `IllegalArgumentException` for a channel outside 0–15.
  - `orderedActiveNotes(channel: Int): Seq[MidiNote]` — unchanged signature, redefined meaning: **in order of their most
    recent Note On**.
  - `activeNotes`, `isNoteActive`, `velocity`, `velocityOption`, `polyPressure`, `polyPressureOption` — signatures and
    meanings unchanged. A note with any positive count is active.

### Cycle A — counting semantics

- [ ] **Step 1: Add the `referenceCount` stub so the new tests compile**

In `ScMidiChannelStateTracker.scala`, immediately after `isNoteActive` (which ends at line 134), insert:

```scala
  def referenceCount(channel: Int, midiNote: MidiNote): Int = ???
```

No ScalaDoc yet — it arrives in Step 6 with the real implementation. This is the thinnest stub that lets the tests
compile; it contains no logic.

- [ ] **Step 2: Write the failing counting tests**

In `ScMidiChannelStateTrackerTest.scala`, **replace** the existing test at lines 140–151 (`"reset Polyphonic Key
Pressure to its default when a note is re-triggered with Note On"`) with its inverted form (D4):

```scala
  it should "preserve Polyphonic Key Pressure when a note is re-triggered with Note On" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(PolyPressureScMidiMessage(Channel, C4, value = 90))

    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))

    // Then — two voices sound for one key, so pressure addressed to that key belongs to both of them
    tracker.polyPressureOption(Channel, C4) should equal(Some(90))
    tracker.polyPressure(Channel, C4) should equal(90)
  }
```

**Extend** the existing test `"overwrite the velocity of an active note when a Note On is re-sent"` (lines 153–162) with
a count assertion — replace its `// Then` block with:

```scala
    // Then
    tracker.velocityOption(Channel, C4) should equal(Some(120))
    tracker.referenceCount(Channel, C4) should equal(2)
```

**Append** these cases to the end of the `behavior of "ScMidiChannelStateTracker per note tracking"` section (that is,
after the velocity test and before `behavior of "ScMidiChannelStateTracker Control Change tracking"` at line 164):

```scala
  it should "count a single Note On as one reference" in new TrackerFixture {
    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))

    // Then
    tracker.referenceCount(Channel, C4) should equal(1)
  }

  it should "increment the reference count when an already-active note receives another Note On" in
    new TrackerFixture {
      // Given
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))

      // When
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))

      // Then
      tracker.referenceCount(Channel, C4) should equal(2)
      tracker.activeNotes(Channel) should contain only C4
    }

  it should "decrement the reference count on Note Off while a reference remains, keeping the note active" in
    new TrackerFixture {
      // Given
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))

      // When
      tracker.send(NoteOffScMidiMessage(Channel, C4))

      // Then
      tracker.referenceCount(Channel, C4) should equal(1)
      tracker.isNoteActive(Channel, C4) shouldBe true
      tracker.activeNotes(Channel) should contain only C4
    }

  it should "remove the note when the last Note On is discharged" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))
    tracker.send(NoteOffScMidiMessage(Channel, C4))

    // When
    tracker.send(NoteOffScMidiMessage(Channel, C4))

    // Then
    tracker.referenceCount(Channel, C4) should equal(0)
    tracker.isNoteActive(Channel, C4) shouldBe false
    tracker.activeNotes(Channel) shouldBe empty
  }

  it should "decrement the reference count on a Note On with velocity 0 exactly as on a Note Off" in
    new TrackerFixture {
      // Given
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))

      // When
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = NoteOnScMidiMessage.NoteOffVelocity))

      // Then
      tracker.referenceCount(Channel, C4) should equal(1)
      tracker.isNoteActive(Channel, C4) shouldBe true
    }

  it should "leave the reference count at 0 when a Note Off arrives for an inactive note" in new TrackerFixture {
    // When
    tracker.send(NoteOffScMidiMessage(Channel, C4))

    // Then
    tracker.referenceCount(Channel, C4) should equal(0)
    tracker.activeNotes(Channel) shouldBe empty
  }

  it should "report a reference count of 0 for a note that was never played" in new TrackerFixture {
    // When / Then
    tracker.referenceCount(Channel, C4) should equal(0)
  }

  it should "track reference counts independently per channel and per note" in new TrackerFixture {
    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(Channel, E4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(OtherChannel, C4, velocity = 100))

    // Then
    tracker.referenceCount(Channel, C4) should equal(2)
    tracker.referenceCount(Channel, E4) should equal(1)
    tracker.referenceCount(OtherChannel, C4) should equal(1)
    tracker.referenceCount(OtherChannel, E4) should equal(0)
  }
```

**Append** to the `behavior of "ScMidiChannelStateTracker channel validation"` section, immediately after the
`"throw on isNoteActive with an invalid channel"` test (which ends at line 1185):

```scala
  it should "throw on referenceCount with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.referenceCount(-1, C4)
    an[IllegalArgumentException] should be thrownBy tracker.referenceCount(16, C4)
  }
```

**Append** to the `behavior of "ScMidiChannelStateTracker Channel Mode messages"` section, immediately after the
`"cancel active notes on the channel when All Notes Off is received"` test (which ends at line 1262):

```scala
  it should "clear the reference counts of the channel's notes when All Sound Off is received" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.AllSoundOff, value = 0))

      // Then
      tracker.referenceCount(Channel, C4) should equal(0)
      tracker.activeNotes(Channel) shouldBe empty
    }

  it should "clear the reference counts of the channel's notes when All Notes Off is received" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.AllNotesOff, value = 0))

      // Then
      tracker.referenceCount(Channel, C4) should equal(0)
      tracker.activeNotes(Channel) shouldBe empty
    }
```

Still in the Channel Mode messages section, **append** after the `"clear Channel Pressure, Pitch Bend, and the RPN/NRPN
selector when Reset All Controllers is received"` test (which ends at line 1324):

```scala
  it should "leave reference counts intact while zeroing Polyphonic Key Pressure on Reset All Controllers" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))
      tracker.send(PolyPressureScMidiMessage(Channel, C4, value = 90))

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))

      // Then — Reset All Controllers is not a note-off, so nothing is discharged
      tracker.referenceCount(Channel, C4) should equal(2)
      tracker.isNoteActive(Channel, C4) shouldBe true
      tracker.polyPressure(Channel, C4) should equal(0)
    }
```

**Append** to the `behavior of "ScMidiChannelStateTracker reset"` section, after the `"clear all per-channel state
across all channels"` test (which ends at line 1488):

```scala
  it should "clear reference counts across all channels" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))
    tracker.send(NoteOnScMidiMessage(OtherChannel, E4, velocity = 90))
    tracker.send(NoteOnScMidiMessage(OtherChannel, E4, velocity = 95))

    // When
    tracker.reset()

    // Then
    tracker.referenceCount(Channel, C4) should equal(0)
    tracker.referenceCount(OtherChannel, E4) should equal(0)
  }
```

And after the `"clear the state of a single channel, leaving the other fifteen untouched"` test (which ends at
line 1542):

```scala
  it should "clear the reference counts of a single channel, leaving the other fifteen untouched" in
    new TrackerFixture {
      // Given
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
      tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))
      tracker.send(NoteOnScMidiMessage(OtherChannel, E4, velocity = 90))
      tracker.send(NoteOnScMidiMessage(OtherChannel, E4, velocity = 95))

      // When
      tracker.reset(Channel)

      // Then
      tracker.referenceCount(Channel, C4) should equal(0)
      tracker.referenceCount(OtherChannel, E4) should equal(2)
    }
```

- [ ] **Step 3: Run the tests to verify they fail for the right reason**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ScMidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`

Expected: the suite **compiles** and fails. Every case that calls `referenceCount` fails with
`scala.NotImplementedError: an implementation is missing`; `"preserve Polyphonic Key Pressure when a note is re-triggered
with Note On"` fails with `Some(0) did not equal Some(90)`. **No compile errors** — if you see any, fix the test code
and re-run before continuing.

- [ ] **Step 4: Give `ActiveNote` a mutable velocity and a reference count**

In the companion object, replace line 503:

```scala
  private class ActiveNote(val velocity: Int, var polyPressure: Int = 0)
```

with:

```scala
  private class ActiveNote(var velocity: Int, var polyPressure: Int = 0, var referenceCount: Int = 1)
```

`velocity` becomes a `var` because a duplicate Note On overwrites it in place, retaining the object so `polyPressure`
survives (D4). `ActiveNote` stays a plain `class`, not a `case class`, per the repository's mutable-data-structure
convention.

- [ ] **Step 5: Make the two release branches decrement through one helper**

Replace the three note branches at the top of `send` (lines 59–64):

```scala
    case NoteOnScMidiMessage(channel, midiNote, NoteOnScMidiMessage.NoteOffVelocity) =>
      channelStates(channel).activeNotes -= midiNote
    case NoteOnScMidiMessage(channel, midiNote, velocity) =>
      channelStates(channel).activeNotes(midiNote) = ActiveNote(velocity)
    case NoteOffScMidiMessage(channel, midiNote, _) =>
      channelStates(channel).activeNotes -= midiNote
```

with:

```scala
    case NoteOnScMidiMessage(channel, midiNote, NoteOnScMidiMessage.NoteOffVelocity) =>
      releaseNote(channel, midiNote)
    case NoteOnScMidiMessage(channel, midiNote, velocity) =>
      val activeNotes = channelStates(channel).activeNotes
      activeNotes.get(midiNote) match {
        case Some(activeNote) =>
          activeNote.velocity = velocity
          activeNote.referenceCount += 1
        case None =>
          activeNotes(midiNote) = ActiveNote(velocity)
      }
    case NoteOffScMidiMessage(channel, midiNote, _) =>
      releaseNote(channel, midiNote)
```

The velocity-0 case must stay **above** the general Note On case, as it is today, or a note-off-by-velocity-0 would
match the increment branch instead.

Add `releaseNote` as the first private method of the class — immediately before `private def resolvedCcDefault`
(line 295):

```scala
  /**
   * Discharges one Note On for the given note, removing it from the channel's active notes when the last one is
   * discharged. A release for a note that holds no active count is a no-op.
   */
  private def releaseNote(channel: Int, midiNote: MidiNote): Unit = {
    val activeNotes = channelStates(channel).activeNotes
    activeNotes.get(midiNote).foreach { activeNote =>
      activeNote.referenceCount -= 1
      if (activeNote.referenceCount == 0) activeNotes -= midiNote
    }
  }
```

- [ ] **Step 6: Implement the `referenceCount` accessor**

Replace the Step 1 stub with the real implementation, ScalaDoc included:

```scala
  /**
   * @return the number of Note On messages received for the given note on the given channel that no Note Off has yet
   *         discharged, or `0` if the note is not active.
   */
  def referenceCount(channel: Int, midiNote: MidiNote): Int = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.get(midiNote).map(_.referenceCount).getOrElse(0)
  }
```

- [ ] **Step 7: Compile and run the tests to verify they pass**

Compile: `mcp__metals__compile-module` with `module = "sc-midi"`, then `module = "sc-midi-test"`.

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ScMidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`

Expected: PASS, except `"move a note to the end of the ordered active notes on a duplicate Note On"` — which does not
exist yet. Every case written in Step 2 must be green.

### Cycle B — most-recent-Note-On ordering

- [ ] **Step 8: Write the failing ordering test**

Append to the end of the `behavior of "ScMidiChannelStateTracker per note tracking"` section (after the last case added
in Step 2):

```scala
  it should "move a note to the end of the ordered active notes on a duplicate Note On" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(Channel, E4, velocity = 90))
    tracker.send(NoteOnScMidiMessage(Channel, G4, velocity = 80))

    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))

    // Then — active notes are ordered by their most recent Note On, not by first insertion
    tracker.orderedActiveNotes(Channel) should contain theSameElementsInOrderAs Seq(E4, G4, C4)
    tracker.referenceCount(Channel, C4) should equal(2)
  }
```

- [ ] **Step 9: Run the test to verify it fails**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ScMidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`

Expected: FAIL on that case only, with a message of the form `List(C4, E4, G4) did not contain the same elements in the
same order as List(E4, G4, C4)` (rendered as the underlying note numbers, `List(60, 64, 67)` vs `List(64, 67, 60)`) —
a `LinkedHashMap` keeps an updated key at its original position.

- [ ] **Step 10: Re-insert the note instead of updating it in place**

In the Note On branch of `send`, replace the `Some` case body from Step 5:

```scala
        case Some(activeNote) =>
          activeNote.velocity = velocity
          activeNote.referenceCount += 1
```

with:

```scala
        case Some(activeNote) =>
          activeNote.velocity = velocity
          activeNote.referenceCount += 1
          // Removed and re-inserted rather than updated in place: a LinkedHashMap keeps an updated key at its
          // original position, and active notes are ordered by their most recent Note On.
          activeNotes -= midiNote
          activeNotes(midiNote) = activeNote
```

The same `activeNote` object is put back, so its `polyPressure` survives the move (D4).

- [ ] **Step 11: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ScMidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`

Expected: PASS, all cases. In particular the pre-existing `"preserve insertion order of active notes"` (line 87) must
stay green — it plays three distinct notes, so no reordering applies to it.

- [ ] **Step 11b: Refactor the Note On branch to a single lookup**

Green now, so tighten the structure without changing behaviour. The two cycles left the branch doing a `get`, then a
`-=`, then an insert — three lookups where one `remove` suffices. Replace the whole Note On branch with the form design
Section 3.2 prescribes:

```scala
    case NoteOnScMidiMessage(channel, midiNote, velocity) =>
      val activeNotes = channelStates(channel).activeNotes
      activeNotes.remove(midiNote) match {
        case Some(activeNote) =>
          activeNote.velocity = velocity
          activeNote.referenceCount += 1
          // Removed and re-inserted rather than updated in place: a LinkedHashMap keeps an updated key at its
          // original position, and active notes are ordered by their most recent Note On.
          activeNotes(midiNote) = activeNote
        case None =>
          activeNotes(midiNote) = ActiveNote(velocity)
      }
```

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ScMidiChannelStateTrackerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS, all cases — this is a pure refactor, so any failure means the rewrite changed behaviour.

### Documentation

- [ ] **Step 12: Update the tracker's ScalaDocs**

In `ScMidiChannelStateTracker.scala`, replace the opening sentence of the class ScalaDoc (lines 25–28):

```scala
 * A [[ScMidiReceiver]] that tracks per-channel MIDI state derived from the messages it receives: active notes
 * (with their velocities and Polyphonic Key Pressure), Control Change values, Registered and Non-Registered
 * Parameter Number values together with the parameter each channel currently has selected, Channel Pressure,
 * Pitch Bend, and Program Change.
```

with:

```scala
 * A [[ScMidiReceiver]] that tracks per-channel MIDI state derived from the messages it receives: active notes
 * (with their velocities, Polyphonic Key Pressure, and a count of the Note On messages no Note Off has yet
 * discharged), Control Change values, Registered and Non-Registered Parameter Number values together with the
 * parameter each channel currently has selected, Channel Pressure, Pitch Bend, and Program Change.
 *
 * Notes are reference-counted, as MIDI 1.0 requires of a transmitter: a note struck twice without an intervening
 * release stays active until it has received two Note Off messages. See [[referenceCount]].
```

Replace the `activeNotes` ScalaDoc (line 118):

```scala
  /** @return the set of currently active notes on the given channel. */
```

with:

```scala
  /** @return the set of currently active notes on the given channel — those holding at least one undischarged
   *          Note On. */
```

Replace the `orderedActiveNotes` ScalaDoc (line 124):

```scala
  /** @return the currently active notes on the given channel, in the order they were turned on. */
```

with:

```scala
  /** @return the currently active notes on the given channel, in order of their most recent Note On. A duplicate
   *          Note On for an already-active note moves it to the end. */
```

Replace the `velocityOption` ScalaDoc (line 139):

```scala
  /** @return the velocity of the given note on the given channel, or `None` if the note is not active. */
```

with:

```scala
  /**
   * @return the velocity of the given note on the given channel, or `None` if the note is not active. A duplicate
   *         Note On overwrites it with the most recent value.
   */
```

Replace the `polyPressureOption` ScalaDoc (lines 152–156) with:

```scala
  /**
   * @return the most recent Polyphonic Key Pressure value for the given note on the given channel — `Some(0)` if
   *         the note is active but no Polyphonic Key Pressure has been received for it yet, or `None` if the note
   *         is not active. A duplicate Note On retains it: with two voices sounding for one key, pressure addressed
   *         to that key applies to both.
   */
```

- [ ] **Step 13: Update the `sc-midi` architecture doc**

In `docs/architecture/sc-midi/README.md`, replace the bullet at lines 90–93:

```markdown
- **`ScMidiChannelStateTracker`** — an explicitly `@NotThreadSafe` `ScMidiReceiver` (for a single track thread) that
  derives **per-channel MIDI state** (active notes, CC/RPN/NRPN/pressure/pitch-bend/program values) from the messages
  sent to it, implementing the RPN/NRPN Data Entry protocol and the relevant Channel Mode messages.
  `MonophonicPitchBendTuner` uses it to track held-note state.
```

with:

```markdown
- **`ScMidiChannelStateTracker`** — an explicitly `@NotThreadSafe` `ScMidiReceiver` (for a single track thread) that
  derives **per-channel MIDI state** (active notes, CC/RPN/NRPN/pressure/pitch-bend/program values) from the messages
  sent to it, implementing the RPN/NRPN Data Entry protocol and the relevant Channel Mode messages. Notes are
  **reference-counted**: a note struck twice without an intervening release needs two Note Offs to go inactive, which
  is what lets a consumer discharge MIDI 1.0's one-Note-Off-per-Note-On obligation. Active notes are ordered by their
  most recent Note On, so a duplicate Note On moves a note to the end of `orderedActiveNotes`.
  `MonophonicPitchBendTuner` uses it to track held-note state; `MpeTuner` uses it for Master Channel notes, which
  bypass its allocator.
```

- [ ] **Step 14: Verify both affected modules still pass**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS, unchanged. This is the checkpoint for the claim that Task 1 lands safely on its own:
`MonophonicPitchBendTuner`'s synthetic Note Off decrements the duplicate's count straight back to zero and removes the
entry, so the following Note On re-inserts a fresh `ActiveNote` — exactly today's behaviour. If anything in `tuner`
fails here, **stop**: the assumption is wrong and Task 2 needs re-sequencing, not a patch.

- [ ] **Step 15: Leave the changes uncommitted**

Per the Global Constraints, do not `git add`, `git commit`, or create a branch. Report the modified files and move on.

---

## Task 2: `MonophonicPitchBendTuner` reads the tracker's ordering

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTuner.scala:131-147`
  (`sendToTracker`), `:225-250` (`turnNoteOff`)
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTunerTest.scala`

**Interfaces:**
- Consumes: from Task 1 — `tracker.orderedActiveNotes(channel)` ordered by most recent Note On, and
  `tracker.isNoteActive(channel, midiNote)` returning `true` while any undischarged Note On remains.
- Produces: no new public API. `MonophonicPitchBendTuner`'s `process`, `tune`, and `reset` signatures are untouched.

**Behaviour being built** (design Section 4.1). For input `C4 on, E4 on, C4 on again, C4 off, C4 off`:

| Input | Tracker after | Emitted | Sounding |
|-------|---------------|---------|----------|
| `C4 on` | `[C4→1]` | `NoteOn C4` | C4 |
| `E4 on` | `[C4→1, E4→1]` | `NoteOff C4, NoteOn E4` | E4 |
| `C4 on` | `[E4→1, C4→2]` | `NoteOff E4, NoteOn C4` | C4 |
| `C4 off` | `[E4→1, C4→1]` | *(nothing)* | C4 |
| `C4 off` | `[E4→1]` | `NoteOff C4, NoteOn E4` | E4 |

- [ ] **Step 1: Write the failing trace test**

In `MonophonicPitchBendTunerTest.scala`, append to the end of the `behavior of "MonophonicPitchBendTuner when multiple
notes are on"` section — that is, after the `"tune reverted notes when holding a non-microtonal note…"` test which ends
at line 320, and before `behavior of "MonophonicPitchBendTuner when it receives pitch bend messages"` at line 322:

```scala
  it should "keep a re-pressed note sounding until its last Note Off, then revert to the note still held" in
    new Fixture {
      // Given
      // C4 is pressed, E4 takes over, then C4 is pressed a second time without the first press being released.
      output ++= tuner.tune(customTuning)
      output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4, 20).asJava)
      output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4, 40).asJava)
      output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4, 60).asJava)
      output.clear()

      // When
      // The first of the two C4 presses is released.
      output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteC4, 25).asJava)

      // Then
      // C4 is still held by the second press, so nothing at all is emitted and it keeps sounding.
      output shouldBe empty

      // When
      // The second press is released too.
      output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteC4, 25).asJava)

      // Then
      // Only now does C4 stop, handing the sound back to the still-held E4 with E's tuning.
      val outputNotes: Seq[ScMidiMessage] = filterNotes(scMidiOutput)
      outputNotes should have size 2
      inside(outputNotes.head) { case NoteOffScMidiMessage(_, note, 25) => note.number shouldEqual noteC4 }
      inside(outputNotes(1)) { case NoteOnScMidiMessage(_, note, 60) => note.number shouldEqual noteE4 }
      pitchBendOutput should have size 1
      PitchBendScMidiMessage.convertValueToCents(pitchBendOutput.head.value, pitchBendSensitivity) shouldEqual
        customTuning(4)
    }
```

Notes on the expected values, so you can tell a real failure from a mis-transcribed one: velocity `60` on the
auto-generated `NoteOn E4` is `_lastNoteOnVelocity`, set by the most recent `applyNoteOn` (the second C4 press);
velocity `25` on the `NoteOff C4` is the released message's own velocity; `customTuning(4)` is E's offset, `-16.67`
cents; and the single Pitch Bend is emitted because C's offset (`0.0`) differs from E's.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MonophonicPitchBendTunerTest -- -oNCXEHLOPQRMWS"`

Expected: FAIL on the new case at the first `output shouldBe empty`, with a message of the form `Buffer(…) was not
empty`. Today the first `C4 off` is treated as a full release: `turnNoteOff`'s guard sees `prevNotes.last == C4` and
emits a `NoteOff C4` followed by a `NoteOn E4`. Every other case in the suite must still pass.

- [ ] **Step 3: Delete the synthetic Note Off from `sendToTracker`**

Replace `sendToTracker` (lines 131–147) in `MonophonicPitchBendTuner.scala`:

```scala
  private def sendToTracker(scMessage: ScMidiMessage): Unit = {
    // Re-pressing an already-active note must move it to the most-recently-inserted position so
    // that `tracker.orderedActiveNotes(...).last` continues to reflect the audibly sounding note.
    // The tracker stores active notes in a `LinkedHashMap`, which keeps the original position
    // when an existing key is updated, so explicitly remove the note first.
    scMessage match {
      case m: NoteOnScMidiMessage if m.velocity > 0 && tracker.isNoteActive(trackedChannel, m.midiNote) =>
        tracker.send(NoteOffScMidiMessage(trackedChannel, m.midiNote))
      case _ =>
    }

    val normalized = scMessage match {
      case m: ChannelScMidiMessage => m.mapChannel(_ => trackedChannel)
      case m => m
    }
    tracker.send(normalized)
  }
```

with:

```scala
  private def sendToTracker(scMessage: ScMidiMessage): Unit = {
    val normalized = scMessage match {
      case m: ChannelScMidiMessage => m.mapChannel(_ => trackedChannel)
      case m => m
    }
    tracker.send(normalized)
  }
```

The tracker now does the reordering itself, so the synthetic Note Off — and the comment that explained it — go with the
work they did. Leaving the synthetic Note Off in place would silently cancel the increment, turning every duplicate
press back into a full replacement.

- [ ] **Step 4: Make `turnNoteOff`'s guard count-aware**

In `turnNoteOff`, replace line 227:

```scala
    if (prevNotes.nonEmpty && prevNotes.last == note) {
```

with:

```scala
    // `turnNoteOff` runs after `sendToTracker`, so `isNoteActive` reads the post-release state: `false` means this
    // Note Off discharged the note's last unmatched Note On and it must actually stop sounding.
    if (prevNotes.nonEmpty && prevNotes.last == note && !tracker.isNoteActive(trackedChannel, note)) {
```

Replace the comment at line 231:

```scala
      // After tracker.send the released note is gone, so the post-state can be read fresh
```

with:

```scala
      // The guard established that this Note Off discharged the note's last reference, so the post-update state no
      // longer holds it and can be read fresh
```

Replace the trailing comment at lines 248–249:

```scala
    // Otherwise: either no note was on (unexpected note off) or the released note was not the most recent;
    // the tracker has already removed it on send, so no audible change is needed.
```

with:

```scala
    // Otherwise: no note was on (unexpected note off), the released note was not the most recent, or the note is
    // still held down by another Note On this Note Off did not discharge; the tracker has already recorded the
    // release, so no audible change is needed.
```

- [ ] **Step 5: Compile and run the tests to verify they pass**

Compile: `mcp__metals__compile-module` with `module = "tuner"`, then `module = "tuner-test"`.

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MonophonicPitchBendTunerTest -- -oNCXEHLOPQRMWS"`

Expected: PASS, all cases.

- [ ] **Step 6: Add regression cover for the non-sounding-note release path**

This case is **expected to be green immediately** — it does not drive any implementation. It exists because design
Section 6 asks for it: the "released note was not the most recent" skip path is now also reached through a decremented
count, and nothing else in the suite pins that. Add it, run it, and confirm it passes; do **not** treat a pass here as a
sign the previous steps were unnecessary.

Append it immediately after the test from Step 1:

```scala
  it should "emit nothing when a note that is not the sounding one is released press by press" in new Fixture {
    // Given
    // C4 is pressed twice, then E4 takes over as the sounding note while both C4 presses are still down.
    output ++= tuner.tune(customTuning)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4, 20).asJava)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4, 30).asJava)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4, 40).asJava)
    output.clear()

    // When
    // Both of C4's presses are released while E4 is still held.
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteC4, 25).asJava)
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteC4, 25).asJava)

    // Then
    // Neither release is audible: E4 keeps sounding undisturbed.
    output shouldBe empty

    // When
    // E4 is released in turn.
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteE4, 45).asJava)

    // Then
    // E4 simply stops; C4 is not revived, both of its presses having been discharged.
    val outputNotes: Seq[ScMidiMessage] = filterNotes(scMidiOutput)
    outputNotes should have size 1
    inside(outputNotes.head) { case NoteOffScMidiMessage(_, note, 45) => note.number shouldEqual noteE4 }
  }
```

- [ ] **Step 7: Run the module suite**

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS — including `MpeTunerTest`, which Task 2 does not touch.

- [ ] **Step 8: Leave the changes uncommitted**

Per the Global Constraints, do not `git add`, `git commit`, or create a branch.

---

## Task 3: `MpeTuner.stopNotesOn` matches every forwarded Master Channel Note On

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:577-613` (`stopNotesOn` and
  its ScalaDoc)
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`
- Modify: `docs/architecture/tuner/README.md:191-193`

**Interfaces:**
- Consumes: from Task 1 — `tracker.referenceCount(channel, midiNote)`.
- Produces: no new public API. `stopNotesOn` stays `private`.

**Why the tracker's count is the right number here.** `MpeTuner.processShortMessage` feeds the tracker the raw input
message (`MpeTuner.scala:205`, after normalising a velocity-0 Note On to a Note Off), and `MpeMessageRouting.route`
gives an `MpeChannelRole.Master` note `ForwardOn(zone.masterChannel)` — the arrival channel itself, unmodified. So one
unmatched tracker reference on a Master Channel *is* one unmatched forwarded Note On. `stopNotesOn` also already runs
before `affected.foreach(tracker.reset)` in `processMcm` (`MpeTuner.scala:431-434`), so the counts are still there to be
read, and before `_zones = zonesAfter` (line 433), so `lowerZone`/`upperZone` still describe the layout the notes were
forwarded under.

- [ ] **Step 1: Write the failing test**

In `MpeTunerTest.scala`, append to the `// ---- Effects on active notes / other state ----` subgroup of
`behavior of "MpeTuner - MCM Processing - MPE Input"` — after the `"keep an Upper Zone note when an MCM enables the
Lower Zone, leaving the Upper Zone untouched"` test, which ends at line 3065:

```scala
  it should "stop a twice-struck Master Channel note with one Note Off per forwarded Note On" in
    new Fixture(dualZoneTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      // Upper Zone master 15. C4 is struck twice there without an intervening release and E4 once; a Master Channel
      // note bypasses the allocator and is forwarded on channel 15 unchanged, so three Note Ons went downstream.
      noteOn(15, C4)
      noteOn(15, C4)
      noteOn(15, E4)

      // When
      // The Upper Zone is disabled, so channel 15 leaves MPE control and everything sounding on it must be stopped.
      private val output = sendMcm(tuner, channel = 15, memberCount = 0)

      // Then
      // One Note Off per forwarded Note On: two for C4, one for E4.
      private val noteOffs = extractNoteOffs(output).filter(_.channel == 15)
      noteOffs.count(_.midiNote == C4) shouldEqual 2
      noteOffs.count(_.midiNote == E4) shouldEqual 1
    }
```

Why this scenario reaches the branch: `dualZoneTunerMpeInput` has a Lower Zone (master 0, members 1–7) and an Upper Zone
(master 15, members 8–14). An MCM with `memberCount = 0` on channel 15 disables the Upper Zone, so
`MpeMessageRouting.roleOf` reclassifies channel 15 from `Master(upper)` to `Outside` and `channelsAffectedByMcm`
therefore includes it.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`

Expected: FAIL on the new case with `1 did not equal 2` — the Master Channel branch iterates active notes, of which C4
is one however many times it was struck. The E4 assertion and every other case must pass.

- [ ] **Step 3: Emit one Note Off per reference**

In `MpeTuner.scala`, replace the Master Channel branch of `stopNotesOn` (lines 602–612):

```scala
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
```

with:

```scala
    if (_inputMode == MpeInputMode.Mpe) {
      for {
        zone <- Seq(lowerZone, upperZone) if zone.isEnabled && channels.contains(zone.masterChannel)
        midiNote <- tracker.activeNotes(zone.masterChannel)
        _ <- 1 to tracker.referenceCount(zone.masterChannel, midiNote)
      } {
        buffer += NoteOffScMidiMessage(zone.masterChannel, midiNote).asJava
      }
    }
```

- [ ] **Step 4: Update the `stopNotesOn` ScalaDoc**

Replace its closing paragraph (lines 587–590):

```scala
   * Member Channel notes get one Note Off per Note On forwarded for them, as [[emitDroppedNoteOffs]] does,
   * discharging the one-Note-Off-per-Note-On obligation of the paper's "Note Identity and Reference Counting"
   * section. Master Channel notes get exactly one each: they bypass the allocator, and
   * [[ScMidiChannelStateTracker]] models a channel's active notes as a set, so no count is available for them.
```

with:

```scala
   * Every note gets one Note Off per Note On forwarded for it, discharging the one-Note-Off-per-Note-On obligation
   * of the paper's "Note Identity and Reference Counting" section: Member Channel notes from the allocator's own
   * reference count, as [[emitDroppedNoteOffs]] does, and Master Channel notes — which bypass the allocator — from
   * the reference count [[ScMidiChannelStateTracker]] keeps for them.
```

- [ ] **Step 5: Compile and run the tests to verify they pass**

Compile: `mcp__metals__compile-module` with `module = "tuner"`, then `module = "tuner-test"`.

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`

Expected: PASS, all cases.

- [ ] **Step 6: Remove the TODO #254 bullet from the `tuner` architecture doc**

In `docs/architecture/tuner/README.md`, delete the bullet at lines 191–193 in the "Subject to change" section:

```markdown
- `MpeTuner.stopNotesOn`'s Master Channel branch emits one Note Off per active Master Channel note rather than one per
  forwarded Note On, because `ScMidiChannelStateTracker` tracks active notes as a set with no reference count
  (TODO #254). Member Channel notes, which the allocator reference-counts, are unaffected.
```

Leave the surrounding bullets (TODO #253 above it, TODO #90 below it) exactly as they are.

- [ ] **Step 7: Confirm no `TODO #254` remains in the codebase**

Run: `rg -n 'TODO #254' --glob '!issues/**'`
Expected: no matches. The only mentions of #254 left outside `issues/` should be none at all — check the output is
empty rather than assuming it.

- [ ] **Step 8: Run the module suite**

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 9: Leave the changes uncommitted**

Per the Global Constraints, do not `git add`, `git commit`, or create a branch.

---

## Task 4: Final checks

**Files:** none modified unless a check fails; then fix in the file the check points at and re-run.

**Interfaces:**
- Consumes: the finished implementation from Tasks 1–3.
- Produces: evidence that the work is complete — module suites green, coverage at or above the floors, full suite green.

This is the repository's "final checks" workflow step from `CLAUDE.md`. Run each check and report its actual output; do
not claim a check passed without having seen it pass.

- [ ] **Step 1: Module tests for both modified modules**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill (`Skill` tool, `skill: "scoverage-inspector"`). It carries the coverage policy
and drives the `scoverage-inspector` MCP server, which does the freshness check, the rebuild if the report is stale, and
the XML parsing in-process. Do not read or parse the scoverage XML by hand.

Check these, against the floors in the Global Constraints (`sc-midi` 67% / 52%, `tuner` 80% / 80%):

- `sc-midi` and `tuner` module totals are at or above their floors — the floors may never be lowered.
- The four changed source files meet the 80% target for new/changed code:
  `ScMidiChannelStateTracker.scala`, `MonophonicPitchBendTuner.scala`, `MpeTuner.scala`.

If a changed file falls short, add tests for the specific uncovered lines the inspector names — the likely gaps are the
two arms of `releaseNote`'s `if (activeNote.referenceCount == 0)` and the two cases of the Note On `match`, all of which
the Task 1 tests should already reach. Iterate until the target is met.

- [ ] **Step 3: Full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS across every module.

- [ ] **Step 4: Confirm the documentation set is complete**

Verify each of these landed (they are spread across Tasks 1 and 3, so this is the one place they are checked together):

- `ScMidiChannelStateTracker` class ScalaDoc mentions reference counting.
- `orderedActiveNotes` ScalaDoc says "in order of their most recent Note On".
- `referenceCount` has a ScalaDoc.
- `velocityOption` and `polyPressureOption` ScalaDocs describe what a duplicate Note On does to each.
- `MpeTuner.stopNotesOn` ScalaDoc no longer claims Master Channel notes get exactly one Note Off.
- `MonophonicPitchBendTuner` no longer carries the synthetic-Note-Off comment.
- `docs/architecture/sc-midi/README.md` tracker bullet covers reference counting and the ordering rule.
- `docs/architecture/tuner/README.md` no longer has the TODO #254 bullet.
- `docs/architecture/tuner/mpe-tuner-paper.md` is **untouched** — confirm with `git status` that it still shows only
  the unstaged edits it had before this work started, and that no step of this plan added to them.

- [ ] **Step 5: Report, and leave everything uncommitted**

Summarise: files changed, tests added, coverage numbers observed. Do not commit, do not create a branch, and do not open
a PR — ask the user whether they want an issue linked or a PR opened, per `CLAUDE.md`.

---

## Out of scope

Carried over from design Section 8 — do not let these creep in:

- **`MpeTuner`'s tracker does not respond to reset messages** (`shallRespondToResetMessages` defaults to `false`), so a
  relayed All Notes Off on a Master Channel leaves the tracker's notes stale. Pre-existing, harmless under this design's
  stated preference for a redundant Note Off over a hanging note, and not part of #254.
- **The Zone-reconfiguration Note Off policy itself.** #262 / #266 / #270 already shipped it; nothing here changes it.
- **TODO #253** (Expression Pitch Bend seeding after a member PBS change) is unrelated.
- **Any upper clamp on the reference count.** There is none, symmetric with `MpeChannelState`, which has none either.
