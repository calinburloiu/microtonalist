# Note Reference Counting in `ScMidiChannelStateTracker` (Design)

- **Date**: 2026-08-16
- **Issue**: [#254](https://github.com/calinburloiu/microtonalist/issues/254) — "ScMidiChannelStateTracker: track note
  reference counts so duplicate Note Ons get matching Note Offs"
- **Base commit**: `979a1d4` — "[#250] Reconcile the four MPE Tuner routing conformance phases (#270)"
- **Modules touched**: `sc-midi` (the tracker), `tuner` (`MonophonicPitchBendTuner`, `MpeTuner`)
- **Source of truth for the MPE half**: the MPE Tuner paper,
  [`docs/architecture/tuner/mpe-tuner-paper.md`](../../docs/architecture/tuner/mpe-tuner-paper.md), Sections 4.2 and
  5.1. This design does **not** amend the paper — see Section 7.

## 1. Problem

`ScMidiChannelStateTracker` models a channel's active notes as a `LinkedHashMap[MidiNote, ActiveNote]` keyed by note
number alone, with no count. A note that received two Note On messages with no intervening Note Off is therefore
indistinguishable from one that received a single Note On, and the first Note Off removes it outright.

Two consequences, one per consumer:

1. **`MpeTuner.stopNotesOn`** (`MpeTuner.scala:592-613`) discharges the "one Note Off per forwarded Note On"
   obligation — MIDI 1.0's transmitter rule [2, p. A-4], restated in the paper's Section 5.1 — for Member Channel
   notes, by iterating `alloc.referenceCountOf(noteIdentity)`. Its Master Channel branch cannot: Master Channel notes
   bypass the allocator and the tracker is their sole record. It emits one Note Off per *active note*, so a Master
   Channel note struck twice leaves one unmatched Note On downstream on Zone reconfiguration. The branch carries a
   `TODO #254` (`MpeTuner.scala:602-605`) and a matching "Subject to change" bullet in the `tuner` architecture doc.

2. **`MonophonicPitchBendTuner.sendToTracker`** (`MonophonicPitchBendTuner.scala:131-147`) exploits the set-like
   behaviour deliberately. It sends a *synthetic* Note Off before re-sending a Note On for an already-active note —
   not to model a release, but to evict the entry so the following Note On re-inserts it at the most-recently-inserted
   position, keeping `tracker.orderedActiveNotes(trackedChannel).last` naming the audibly sounding note. Adding
   reference counting turns that synthetic Note Off into a 2 → 1 decrement that leaves the entry in place, so the
   reordering silently stops working and the tuner bends the wrong pitch class.

## 2. Decisions

### D1 — The tracker reorders on a duplicate Note On; no new reordering API

A Note On for an already-active note increments the count **and** moves the entry to the most-recently-inserted
position. `orderedActiveNotes` is redefined from "in the order they were turned on" to "**in order of their most
recent Note On**".

`MonophonicPitchBendTuner` then needs no compensating call at all — the synthetic Note Off and its explanatory comment
are deleted outright.

Considered and rejected:

- **An explicit `touchNote(channel, midiNote)`** that reorders without touching the count. It keeps the tracker's
  original ordering rule, but adds an API that mutates derived state with no MIDI-message counterpart, and leaves the
  tuner with a two-step dance that a second consumer would have to know to repeat.
- **`MonophonicPitchBendTuner` owning its own held-note ordering**, using the tracker only for CC / RPN / Pitch Bend
  state. It duplicates bookkeeping the tracker already does, is the largest change of the three, and would leave
  `orderedActiveNotes` with no production consumer at all.

The new ordering rule is only observable on duplicate Note Ons, where it is at least as defensible as the old one: a
receiver typically assigns a duplicate Note On to a *new voice*, which is precisely why MIDI 1.0 demands one Note Off
per Note On.

### D2 — A partial release keeps the note sounding in `MonophonicPitchBendTuner`

When a note held twice receives one Note Off, the tuner emits nothing; the note keeps sounding. The final release
performs the note-off and the legato return to the previously held note.

`touchNote` alone — the option the issue body favours — would **not** have been sufficient, and neither is D1 on its
own. `turnNoteOff` reads `notesAfter.last` *after* the tracker update, so under reference counting a partial release
would find the just-released note still present and re-trigger it (`NoteOff C4` immediately followed by `NoteOn C4`)
instead of returning to the held note. Whichever reordering option had been chosen, `turnNoteOff` needed a
count-aware guard.

The alternative — any Note Off releases, preserving today's audible behaviour exactly — was rejected because it makes
the tuner disagree with the tracker it reads from: the note stays active there while the tuner has already released
it, and the second Note Off then finds `prevNotes.last` naming a different note and is silently ignored.

### D3 — `activeNotes` keeps returning `Set[MidiNote]`; the count gets its own accessor

As the issue proposes: the smaller change for existing callers than replacing it with a count-carrying structure.
The new accessor is named `referenceCount(channel, midiNote)`, matching the tracker's own `velocity` / `polyPressure`
accessor style rather than `MpeChannelState.referenceCountOf`'s.

### D4 — A duplicate Note On preserves Polyphonic Key Pressure and records the latest velocity

Today `ActiveNote(velocity)` replaces the entry wholesale, which also resets `polyPressure` to 0. That reset must not
survive: with two voices sounding for one key, pressure addressed to that key applies to both, and dropping it would
lose the first voice's pressure. Velocity takes the most recent value.

## 3. `sc-midi` — `ScMidiChannelStateTracker`

### 3.1 State

```scala
private class ActiveNote(var velocity: Int, var polyPressure: Int = 0, var referenceCount: Int = 1)
```

`velocity` becomes a `var` (D4 overwrites it in place, retaining the object so `polyPressure` survives). `ActiveNote`
stays a plain class, not a `case class`, per the repository's mutable-data-structure convention.

`ChannelState` is unchanged — `activeNotes` remains a `mutable.LinkedHashMap[MidiNote, ActiveNote]`.

### 3.2 `send`

The Note On branch (velocity > 0) becomes:

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

The two release branches — `NoteOnScMidiMessage(channel, midiNote, NoteOnScMidiMessage.NoteOffVelocity)` and
`NoteOffScMidiMessage(channel, midiNote, _)` — both delegate to one private helper:

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

The vel-0 case must stay **above** the general Note On case in the match, as today.

### 3.3 New accessor

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

### 3.4 Unchanged behaviour

- `activeNotes`, `isNoteActive`, `velocity` / `velocityOption`, `polyPressure` / `polyPressureOption` keep their
  signatures and meanings. A note with any positive count is active.
- `AllSoundOff` / `AllNotesOff` (only under `shallRespondToResetMessages`) clear the map and with it every count.
- `ResetAllControllers` zeroes `polyPressure` on every active note and leaves counts untouched — it is not a note-off.
- `reset()` and `reset(channel)` wipe everything, counts included.
- A release for an inactive note remains a no-op; no count ever goes negative, and there is no upper clamp (symmetric
  with `MpeChannelState`, which has none either).

## 4. `tuner` — `MonophonicPitchBendTuner`

Two edits, no new fields:

1. **`sendToTracker`** loses the synthetic-Note-Off `match` and its four-line comment entirely, leaving only the
   channel normalisation and `tracker.send(normalized)`.

2. **`turnNoteOff`**'s guard gains a count-aware clause:

   ```scala
   if (prevNotes.nonEmpty && prevNotes.last == note && !tracker.isNoteActive(trackedChannel, note)) {
   ```

   `turnNoteOff` runs after `sendToTracker`, so `isNoteActive` reflects the post-release state: `false` means the last
   reference was just discharged. The trailing comment explaining the two skip cases gains a third — the note is still
   held by another unmatched Note On.

`prevNotes` continues to be captured before the tracker update, and under D1 its order is "most recent Note On last",
which is what `prevLastNote` and the `prevNotes.last == note` test both want.

### 4.1 Worked trace

`C4 on, E4 on, C4 on again, C4 off, C4 off` — tracker state, then messages emitted downstream:

| Input        | Tracker after           | Emitted                | Sounding |
|--------------|-------------------------|------------------------|----------|
| `C4 on`      | `[C4→1]`                | `NoteOn C4`            | C4       |
| `E4 on`      | `[C4→1, E4→1]`          | `NoteOff C4, NoteOn E4`| E4       |
| `C4 on`      | `[E4→1, C4→2]`          | `NoteOff E4, NoteOn C4`| C4       |
| `C4 off`     | `[E4→1, C4→1]`          | *(nothing)*            | C4       |
| `C4 off`     | `[E4→1]`                | `NoteOff C4, NoteOn E4`| E4       |

Every note's downstream Note On / Note Off stream is balanced at every step, and the sounding note is correct at every
step.

## 5. `tuner` — `MpeTuner.stopNotesOn`

The Master Channel branch gains the inner loop its Member Channel sibling already has and drops the `TODO #254`:

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

The tracker's count is the right number here because the two ends line up exactly: `MpeTuner.processShortMessage`
feeds the tracker the raw input message (`MpeTuner.scala:205`, after normalising a vel-0 Note On to a Note Off), and
`MpeMessageRouting.route` gives a `MpeChannelRole.Master` note `ForwardOn(zone.masterChannel)` — the arrival channel
itself, unmodified. So one unmatched tracker reference on a Master Channel *is* one unmatched forwarded Note On.

`stopNotesOn` already runs before `affected.foreach(tracker.reset)` in `processMcm` (`MpeTuner.scala:431-434`), so the
counts are still present when read.

The method's ScalaDoc loses its closing caveat ("Master Channel notes get exactly one each: they bypass the allocator,
and `ScMidiChannelStateTracker` models a channel's active notes as a set, so no count is available for them") in
favour of stating that both kinds now get one Note Off per forwarded Note On.

## 6. Testing

Strict TDD, red in `sc-midi` first, then each consumer.

### `ScMidiChannelStateTrackerTest` (`behavior of "ScMidiChannelStateTracker per note tracking"`)

New cases:

- a single Note On gives a reference count of 1; a duplicate raises it to 2
- a Note Off decrements without deactivating while a reference remains
- the last Note Off removes the note (`activeNotes` empty, `referenceCount` back to 0)
- a Note On with velocity 0 decrements exactly as a Note Off does
- a Note Off for a note that is not active is a no-op and leaves the count at 0
- `referenceCount` is 0 for a never-played note
- counts are independent per channel and per note number
- a duplicate Note On moves the note to the end of `orderedActiveNotes`
- `reset()`, `reset(channel)`, and — on a `ResettableTrackerFixture` — `AllNotesOff` / `AllSoundOff` clear counts
- `ResetAllControllers` leaves counts intact while zeroing `polyPressure`
- `referenceCount` rejects channel `-1` and `16` with `IllegalArgumentException`, alongside the existing validation
  cases

Changed cases:

- *"reset Polyphonic Key Pressure to its default when a note is re-triggered with Note On"*
  (`ScMidiChannelStateTrackerTest.scala:140-151`) inverts to **preserve** it (D4).
- *"overwrite the velocity of an active note when a Note On is re-sent"* stays green as written; extend it to also
  assert the count is 2.

### `MonophonicPitchBendTunerTest`

- the Section 4.1 trace: a duplicate Note On keeps the retriggered note sounding, a partial release emits nothing, and
  the final release returns to the still-held note with the right Pitch Bend for its pitch class
- a partial release of a note that is *not* the sounding one emits nothing and leaves the sounding note undisturbed,
  and its final release is likewise inaudible — the existing "released note was not the most recent" path, now also
  reached through a decremented count

### `MpeTunerTest`

- in MPE Input Mode, a Master Channel note struck twice yields **two** Note Off messages on that Master Channel when a
  Zone reconfiguration affects it, and a note struck once still yields exactly one

### Full checks

Per the repository workflow: module tests for `sc-midi` and `tuner`, the `scoverage-inspector` skill for coverage
(both modules must stay at or above their floors, and changed files should meet the 80% target), then the full suite.

## 7. Documentation

- **`ScMidiChannelStateTracker` ScalaDocs** — the class comment gains reference counting in its summary of what it
  tracks; `orderedActiveNotes` is re-worded to "in order of their most recent Note On"; `referenceCount` is documented
  as in Section 3.3; `velocityOption` / `polyPressureOption` note that a duplicate Note On overwrites the velocity and
  retains the pressure.
- **`docs/architecture/sc-midi/README.md`** — the `ScMidiChannelStateTracker` bullet under "MIDI plumbing" gains
  per-note reference counting, and the most-recent-Note-On ordering rule.
- **`docs/architecture/tuner/README.md`** — delete the "Subject to change" bullet about `MpeTuner.stopNotesOn`'s
  Master Channel branch and TODO #254.
- **`MpeTuner.stopNotesOn` ScalaDoc** — as in Section 5.
- **`MonophonicPitchBendTuner`** — the synthetic-Note-Off comment goes with the code it explained.

**`mpe-tuner-paper.md` needs no change.** Section 4.2 already prescribes Note Offs "one per forwarded Note On, as
Section 5.1 requires", and Section 3.4 (Master Channel Forwarding) makes no claim about Master Channel note
bookkeeping. The code is catching up to the paper, not the reverse. This also keeps the work clear of the paper edits
in flight on another branch.

## 8. Out of scope

- **`MpeTuner`'s tracker does not respond to reset messages** (`shallRespondToResetMessages` defaults to `false`), so
  a relayed All Notes Off on a Master Channel leaves the tracker's notes stale and `stopNotesOn` would later emit
  Note Offs for notes the receiver already stopped. Pre-existing, harmless under this design's stated preference for a
  redundant Note Off over a hanging note, and not part of #254.
- **The Zone-reconfiguration Note Off policy itself.** #254's body settles it — `stopNotesOn` keeps emitting, scoped by
  #262 to the channels entering or leaving MPE control, for both halves of a note's identity — and #262 / #266 / #270
  already shipped it. Nothing in this design changes it.
- **TODO #253** (Expression Pitch Bend seeding after a member PBS change) is unrelated.

## 9. File inventory

| File | Change |
|------|--------|
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTracker.scala` | `ActiveNote.referenceCount`, reordering Note On, `releaseNote` helper, `referenceCount` accessor, ScalaDocs |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTrackerTest.scala` | new count/ordering cases; invert the poly-pressure retrigger case |
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTuner.scala` | delete the synthetic Note Off; count-aware `turnNoteOff` guard |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTunerTest.scala` | duplicate Note On and partial-release traces |
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` | `stopNotesOn` Master branch inner loop; drop `TODO #254`; ScalaDoc |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` | twice-struck Master Channel note on Zone reconfiguration |
| `docs/architecture/sc-midi/README.md` | tracker bullet |
| `docs/architecture/tuner/README.md` | remove the TODO #254 "Subject to change" bullet |
