/*
 * Copyright 2026 Calin-Andrei Burloiu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocator.ChannelGroup
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}

import scala.collection.mutable
import scala.util.boundary

/**
 * Holds the MPE '''Expression Values''' of a note, or the aggregated Expression Values of an output MPE
 * Member Channel.
 *
 * A note's Expression Values are the performer-controlled values of the three MPE control dimensions:
 * its Expression Pitch Bend, its Channel Pressure and its CC #74 (Timbre / Slide). The Tuning Pitch Bend
 * is ''not'' an Expression Value — it belongs to the tuning domain rather than to the expression domain,
 * and is added to the Expression Pitch Bend only when the Pitch Bend emitted on a Member Channel is
 * computed.
 */
trait MpeExpression {
  /**
   * Expression Pitch Bend in cents, excluding any tuning offset. 0.0 means no bend.
   * Stored in cents so that the value is independent of the current Pitch Bend Sensitivity.
   */
  def pitchBendCents: Double

  /** Channel pressure (aftertouch) value. Ranges from 0 to 127. */
  def pressure: Int

  /** MIDI CC #74 (Timbre / Slide) value. Ranges from 0 to 127; 64 is the centre. */
  def slide: Int
}

object MpeExpression {
  /** Default Expression Pitch Bend value in cents (no bend). */
  val DefaultPitchBendCents: Double = 0.0

  /** Default channel pressure value (no pressure). */
  val DefaultPressure: Int = 0

  /** Default slide (CC #74) value (centre position). */
  val DefaultSlide: Int = 64
}

private class MutableMpeExpression(var pitchBendCents: Double = MpeExpression.DefaultPitchBendCents,
                                   var pressure: Int = MpeExpression.DefaultPressure,
                                   var slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

/**
 * Immutable [[MpeExpression]], used to hand Expression Values to [[MpeChannelAllocator]] and to snapshot a
 * channel's aggregate.
 */
case class ImmutableMpeExpression(pitchBendCents: Double = MpeExpression.DefaultPitchBendCents,
                                  pressure: Int = MpeExpression.DefaultPressure,
                                  slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

/**
 * A note together with its origin: the pair (input channel, note number).
 *
 * The input channel belongs in a note's identity because it is the carrier of per-note information in both
 * input modes — of a note's Expression Values in MPE Input Mode, and of the Polyphonic Key Pressure
 * addressed to a note in Non-MPE Input Mode — so two notes with the same note number arriving on different
 * input channels are independent notes.
 */
case class MpeNoteIdentity(inputChannel: Int, midiNote: MidiNote)

/**
 * Which of an output Member Channel's aggregated Expression Values changed as the result of an operation.
 *
 * `None` means the dimension is unchanged and needs no message on the output channel; `Some(value)` means
 * it changed to `value`.
 */
case class MpeExpressionUpdate(pitchBendCents: Option[Double] = None,
                               pressure: Option[Int] = None,
                               slide: Option[Int] = None)

object MpeExpressionUpdate {
  /** No Expression Value changed. */
  val Unchanged: MpeExpressionUpdate = MpeExpressionUpdate()
}

/** An [[MpeExpressionUpdate]] addressed to a specific output Member Channel. */
case class MpeChannelExpressionUpdate(channel: Int, update: MpeExpressionUpdate)

/**
 * A dropped note together with the number of Note Off messages owed for it: one per Note On that was
 * forwarded for it, which is the reference count it held at the moment of the drop.
 */
case class MpeDroppedNote(noteIdentity: MpeNoteIdentity, referenceCount: Int)

/**
 * Describes the notes that were removed from a channel as a side-effect of an allocation or of an
 * Expression Pitch Bend update.
 *
 * Notes can be dropped in two situations:
 *  - '''Channel exhaustion''': all Member Channels are occupied and a new note requires a free channel.
 *    The allocator evicts the least-important occupied channel, dropping all its notes.
 *  - '''High Expression Pitch Bend''': a note on a shared channel develops a pitch bend large enough to
 *    interfere with the intonation of the other notes on that channel, or a new note is assigned to such a
 *    channel. All co-resident notes are dropped so the bending note can occupy the channel exclusively.
 *
 * @param channel The 0-indexed MIDI channel from which notes were dropped.
 * @param notes   The dropped notes, oldest onset first, each with the reference count it held.
 * @param group   The [[ChannelGroup]] that `channel` belonged to at the time of the drop.
 */
case class MpeDroppedNotes(channel: Int,
                           notes: Seq[MpeDroppedNote],
                           group: ChannelGroup)

/**
 * Result of a channel allocation operation.
 *
 * @param channel      The 0-indexed MIDI channel assigned to the note.
 * @param update       The Expression Values of `channel` that changed as a result of the allocation.
 * @param droppedNotes Any notes that were dropped as a result of this allocation, including a duplicate Note
 *                     On whose overridden Expression Values raise it to a High Expression Pitch Bend — the
 *                     allocation algorithm is bypassed for a duplicate, but the divergence rule is not.
 *
 * @param isDuplicate  `true` when the Note On raised an already active identity's reference count, so the
 *                     allocation algorithm was bypassed.
 */
case class MpeAllocationResult(channel: Int,
                               update: MpeExpressionUpdate = MpeExpressionUpdate.Unchanged,
                               droppedNotes: Option[MpeDroppedNotes] = None,
                               isDuplicate: Boolean = false)

/**
 * Result of releasing a note.
 *
 * @param channel          The 0-indexed MIDI channel the released note was bound to.
 * @param update           The Expression Values of `channel` that changed as a result of the release.
 * @param pressureWasReset `true` when the release emptied the channel, the caller asked for the Channel
 *                         Pressure reset and the reset actually changed the retained value. The new value
 *                         is carried by `update.pressure` and must be emitted ''before'' the Note Off — the
 *                         sole exception to the Note Off message ordering.
 */
case class MpeReleaseResult(channel: Int,
                            update: MpeExpressionUpdate = MpeExpressionUpdate.Unchanged,
                            pressureWasReset: Boolean = false)

/**
 * Result of an Expression Value update received on an input channel and fanned out to every output Member
 * Channel holding one of that input channel's notes.
 *
 * @param channelUpdates One entry per affected output channel whose aggregate actually changed, ordered by
 *                       the earliest onset among the notes updated on it.
 *
 * @param droppedNotes   Notes dropped by the divergence rule; always empty for pressure and slide updates.
 */
case class MpeExpressionUpdateResult(channelUpdates: Seq[MpeChannelExpressionUpdate] = Nil,
                                     droppedNotes: Seq[MpeDroppedNotes] = Nil)

/**
 * Per-note state on an output Member Channel: the note's own Expression Values, its reference count and
 * the logical time of the Note On that allocated it.
 */
private class MpeNoteState(val expression: MutableMpeExpression,
                           var referenceCount: Int,
                           val onsetTime: Long)

/**
 * Holds all mutable runtime state for a single MPE Member Channel within the allocator.
 *
 * A channel is considered ''occupied'' while it has at least one active Note Identity (i.e. a Note On has
 * been received but the matching Note Off has not yet arrived). Once it becomes unoccupied its pitch-class
 * and group assignments are cleared and the channel is eligible for reuse, but it '''retains''' its
 * aggregated Expression Values: averaging is defined only while at least one note is active, and the
 * retained values are what let the caller omit control messages whose value would not change.
 *
 * @param channel The 0-indexed MIDI channel number this state object represents.
 */
private class MpeChannelState(val channel: Int) {
  private val _notes: mutable.HashMap[MpeNoteIdentity, MpeNoteState] = mutable.HashMap.empty
  private val _expression: MutableMpeExpression = MutableMpeExpression()
  private var _pitchClass: Option[PitchClass] = None
  private var _group: Option[ChannelGroup] = None
  private var _lastNoteOnTime: Long = 0L
  private var _lastNoteOffTime: Long = 0L

  /** An immutable snapshot of the Note Identities currently active on this channel. */
  def noteIdentities: Set[MpeNoteIdentity] = _notes.keySet.toSet

  /** The number of distinct active Note Identities, whatever their reference counts. */
  def noteCount: Int = _notes.size

  /**
   * The channel's live aggregated Expression Values, retained while the channel is unoccupied. The object is
   * mutated in place by [[recomputeExpression]], so a caller that needs a value to survive a later mutation
   * must copy it out.
   */
  def expression: MpeExpression = _expression

  /** The live, mutable Expression Values of an active note on this channel. */
  def expressionFor(noteIdentity: MpeNoteIdentity): MutableMpeExpression = _notes(noteIdentity).expression

  /** The reference count of an active identity, or 0 when it is not active on this channel. */
  def referenceCountOf(noteIdentity: MpeNoteIdentity): Int =
    _notes.get(noteIdentity).map(_.referenceCount).getOrElse(0)

  /** The logical timestamp of the Note On that allocated an active identity. */
  def onsetTimeOf(noteIdentity: MpeNoteIdentity): Long = _notes(noteIdentity).onsetTime

  /**
   * The pitch class shared by all active notes on this channel, or `None` when the channel is
   * unoccupied. All notes on a single channel are required to belong to the same pitch class so
   * that one tuning offset can serve all of them.
   */
  def pitchClass: Option[PitchClass] = _pitchClass

  /**
   * The [[ChannelGroup]] this channel is currently assigned to, or `None` when the channel is
   * unoccupied.
   */
  def group: Option[ChannelGroup] = _group

  /**
   * The logical timestamp of the most recent Note On event processed on this channel.
   * Zero when the channel is unoccupied (never received a note, or all notes have been released).
   */
  def lastNoteOnTime: Long = _lastNoteOnTime

  /**
   * The logical timestamp of the most recent Note Off event processed on this channel.
   * Zero when the channel has never had a note released.
   */
  def lastNoteOffTime: Long = _lastNoteOffTime

  /** `true` if this channel has at least one active note; `false` if it is free for allocation. */
  def isOccupied: Boolean = _notes.nonEmpty

  /**
   * Adds a note to this channel with a reference count of 1, updating pitch class, group, and onset time
   * accordingly. Pitch class and group are only set when the channel transitions from unoccupied to
   * occupied. When the channel is already occupied, the `targetGroup` must match the existing group.
   *
   * @param noteIdentity The note being added.
   * @param expression   The initial Expression Values of the note.
   * @param time         The logical timestamp of the onset.
   * @param targetGroup  The channel group; must match the existing group when the channel is already
   *                     occupied.
   */
  def addNote(noteIdentity: MpeNoteIdentity,
              expression: MutableMpeExpression,
              time: Long,
              targetGroup: ChannelGroup): Unit = {
    if (_notes.isEmpty) {
      _pitchClass = Some(noteIdentity.midiNote.pitchClass)
      _group = Some(targetGroup)
    } else {
      require(_group.contains(targetGroup),
        s"targetGroup $targetGroup does not match existing group ${_group.orNull} on channel $channel")
    }
    _notes(noteIdentity) = MpeNoteState(expression, referenceCount = 1, onsetTime = time)
    _lastNoteOnTime = time
  }

  /**
   * Increments the reference count of an already active identity, for a duplicate Note On. Nothing else
   * changes: the identity keeps its onset time, and the channel keeps its own timestamps, because no
   * allocation occurs.
   */
  def incrementReferenceCount(noteIdentity: MpeNoteIdentity): Unit = {
    _notes(noteIdentity).referenceCount += 1
  }

  /**
   * Decrements the reference count of an active identity, removing it when the count reaches 0.
   *
   * @return `true` when the identity was removed, i.e. the count reached 0.
   */
  def decrementReferenceCount(noteIdentity: MpeNoteIdentity, time: Long): Boolean = {
    val noteState = _notes(noteIdentity)
    noteState.referenceCount -= 1
    if (noteState.referenceCount <= 0) {
      removeNote(noteIdentity, time)
      true
    } else {
      false
    }
  }

  /**
   * Removes a note from this channel whatever its reference count, updating note-off time accordingly.
   * Clears pitch class, group, and onset time when the channel becomes unoccupied. The aggregated
   * Expression Values are left untouched; the caller recomputes them.
   *
   * @param noteIdentity The note to remove.
   * @param time         The logical timestamp of the removal.
   */
  def removeNote(noteIdentity: MpeNoteIdentity, time: Long): Unit = {
    if (_notes.remove(noteIdentity).isDefined) {
      _lastNoteOffTime = time
      if (_notes.isEmpty) {
        _pitchClass = None
        _group = None
        _lastNoteOnTime = 0L
      }
    }
  }

  /**
   * Recomputes the channel's aggregated Expression Values as the average of its active notes' values, one
   * term per Note Identity whatever its reference count. The two integer dimensions are averaged in
   * `Double` and rounded half up.
   *
   * When the channel is unoccupied the aggregate is '''left untouched''': averaging is defined only while
   * at least one note is active, and the retained values give every dimension a defined value at all times.
   */
  def recomputeExpression(): Unit = {
    if (_notes.nonEmpty) {
      val noteStates = _notes.values
      val count = _notes.size
      _expression.pitchBendCents = noteStates.map(_.expression.pitchBendCents).sum / count
      _expression.pressure = Math.round(noteStates.map(_.expression.pressure).sum.toDouble / count).toInt
      _expression.slide = Math.round(noteStates.map(_.expression.slide).sum.toDouble / count).toInt
    }
  }

  /** Returns the retained Channel Pressure to its default of 0. */
  def resetPressure(): Unit = {
    _expression.pressure = MpeExpression.DefaultPressure
  }

  /** Resets all channel state, clearing notes, aggregated Expression Values and all timestamps. */
  def reset(): Unit = {
    _notes.clear()
    _expression.pitchBendCents = MpeExpression.DefaultPitchBendCents
    _expression.pressure = MpeExpression.DefaultPressure
    _expression.slide = MpeExpression.DefaultSlide
    _pitchClass = None
    _group = None
    _lastNoteOnTime = 0L
    _lastNoteOffTime = 0L
  }
}

/**
 * Manages channel allocation and the Expression Value model for a single MPE Zone.
 *
 * '''Allocation''' follows the dual-group strategy, which partitions the available Member Channels into a
 * Pitch Class Group and an Expression Group to prioritize intonation precision over polyphony and per-note
 * Expression Value independence when necessary. See the paper's "Allocation of Notes to Member Channels"
 * section.
 *
 * '''Expression Values''' are owned here as well, in three layers (see the paper's "Expression Value
 * Processing" section):
 *  - each active [[MpeNoteIdentity]] carries its own [[MpeExpression]] and a reference count, one per Note On
 *    forwarded for it;
 *  - each output Member Channel carries an aggregate, the average over its active identities — one term per
 *    identity whatever its reference count — which is '''retained''' unchanged when the channel empties, so
 *    that every dimension has a defined value at all times;
 *  - a `MpeNoteIdentity -> channel` binding, maintained on every path that adds or removes a note, dropping
 *    included, so that a Note Off for a note the allocator has already dropped finds nothing.
 *
 * Every mutating method reports which of the affected channels' three Expression Values actually changed, so
 * the caller can emit only the messages that are needed.
 *
 * This class is '''unaware of the input mode''' by construction: every mode-dependent decision reaches it as
 * an argument (`preferredChannel` on [[allocate]], `resetPressureOnEmpty` on [[release]], and the choice of
 * whether to pass Expression Values at all). This keeps the mode a concern of [[MpeTuner]] alone.
 *
 * @param zone The MPE zone to allocate channels for.
 */
class MpeChannelAllocator(private val zone: MpeZoneStructure) {

  import MpeChannelAllocator.*

  /** Data structures with allocation information, keyed by output Member Channel. */
  private val channelStates: Map[Int, MpeChannelState] = zone.memberChannels.map(ch => ch -> MpeChannelState(ch)).toMap

  /** The output Member Channel each active Note Identity is bound to. */
  private val noteChannels: mutable.HashMap[MpeNoteIdentity, Int] = mutable.HashMap.empty

  private var _time: Long = 0L

  private def nextTime(): Long = {
    _time += 1
    _time
  }

  reset()

  /** @return the [[MpeZoneType]] (Lower or Upper) of the zone this allocator manages channels for. */
  def zoneType: MpeZoneType = zone.zoneType

  /**
   * Allocates an output Member Channel for a note, or increments the reference count of an already active
   * one.
   *
   * @param noteIdentity     The note to allocate a channel for.
   * @param expression       The note's initial Expression Values, or `None` to use the defaults of
   *                         [[MpeExpression]]. On a duplicate Note On, `Some` overrides the note's current
   *                         Expression Values and `None` leaves them untouched.
   * @param preferredChannel An optional preferred output channel, applied by tie-break criterion (e). It is
   *                         a separate parameter rather than `noteIdentity.inputChannel` because the
   *                         preference is input-mode-dependent and this class is unaware of the input mode.
   * @return the assigned channel, the Expression Values that changed on it, and any notes dropped.
   */
  def allocate(noteIdentity: MpeNoteIdentity,
               expression: Option[MpeExpression] = None,
               preferredChannel: Option[Int] = None): MpeAllocationResult =
    noteChannels.get(noteIdentity) match {
      case Some(channel) => allocateDuplicate(channelStates(channel), noteIdentity, expression)
      case None => allocateFresh(noteIdentity, expression, preferredChannel)
    }

  /**
   * Handles a Note On for an already active identity: the reference count is incremented, the allocation
   * algorithm is bypassed and the note stays a single term in its channel's averages. The recomputation is
   * performed rather than assumed, so that a missed update surfaces as an emitted message instead of
   * silence.
   *
   * Overriding Expression Values can raise the note to a High Expression Pitch Bend even though no
   * allocation takes place, so the divergence rule is applied before the recomputation, exactly as
   * [[updateExpressionValues]] applies it for an Expression Pitch Bend received on an input channel. This
   * keeps the invariant that a High-Expression-Pitch-Bend note is never co-resident with another note true
   * regardless of which path raised the bend.
   *
   * The duplicated identity always survives when the rule drops notes here, never one of the others: the
   * invariant of the paper's "Summary of Note-Dropping Invariants" section stating that a High Expression
   * Pitch Bend note is the sole note on its channel guarantees that a channel holding more than one note has
   * no high-bend note among them ''before'' this call, so once this identity's Expression Values are
   * overridden, it is the only note on the channel that can possibly qualify as high-bend.
   */
  private def allocateDuplicate(state: MpeChannelState,
                                noteIdentity: MpeNoteIdentity,
                                expression: Option[MpeExpression]): MpeAllocationResult = {
    val before = snapshotOf(state.expression)
    state.incrementReferenceCount(noteIdentity)
    expression.foreach { newExpression =>
      val noteExpression = state.expressionFor(noteIdentity)
      noteExpression.pitchBendCents = newExpression.pitchBendCents
      noteExpression.pressure = newExpression.pressure
      noteExpression.slide = newExpression.slide
    }
    val dropped = applyDivergenceRule(state)
    state.recomputeExpression()
    MpeAllocationResult(state.channel, diff(before, state.expression), dropped, isDuplicate = true)
  }

  private def allocateFresh(noteIdentity: MpeNoteIdentity,
                            expression: Option[MpeExpression],
                            preferredChannel: Option[Int]): MpeAllocationResult = boundary {
    val pc = noteIdentity.midiNote.pitchClass
    val time = nextTime()

    // Step 1: Check Pitch Class Group availability
    val pitchClassInPCG = pitchClassGroupChannels.exists(_.pitchClass.contains(pc))
    if (!pitchClassInPCG && pitchClassGroupCount < zone.pitchClassGroupSize) {
      val target = bestCandidate(unoccupiedChannels.map(channelStates), preferredChannel)
      boundary.break(doAllocate(target, noteIdentity, expression, time, ChannelGroup.PitchClass))
    }

    // Step 2: Try Expression Group
    if (expressionGroupCount < zone.expressionGroupSize) {
      val target = bestCandidate(unoccupiedChannels.map(channelStates), preferredChannel)
      boundary.break(doAllocate(target, noteIdentity, expression, time, ChannelGroup.Expression))
    }

    // Step 3: Try sharing with the same pitch class
    val samePcChannels = channelStates.values.filter { s =>
      s.isOccupied && s.pitchClass.contains(pc)
    }.toSeq
    if (samePcChannels.nonEmpty) {
      // The candidates are all occupied, so the input-channel preference (criterion (e)) does not apply
      // and degenerates to the lowest channel number (see the paper's "Allocation Algorithm" section),
      // exactly as for Step 4's freeChannel; pass None rather than preferredChannel.
      val target = bestCandidate(samePcChannels, None)
      boundary.break(doAllocate(target, noteIdentity, expression, time, target.group.get))
    }

    // Step 4: No channel with the same pitch class and all channels occupied -> free a channel
    val dropped = freeChannel(time)
    doAllocate(channelStates(dropped.channel), noteIdentity, expression, time, dropped.group)
      .copy(droppedNotes = Some(dropped))
  }

  /**
   * Releases one Note On of a note. Deallocation — removal from the channel, from the identity → channel
   * binding, and the accompanying recomputation — happens only on the transition to a reference count of 0;
   * a decrement that leaves the count at 1 or above changes no average, because the identity remains a
   * single term in it.
   *
   * @param noteIdentity         The note to release.
   * @param resetPressureOnEmpty When `true` and this release empties the channel, the channel's retained
   *                             Channel Pressure is zeroed instead of retained.
   * @return `None` when the identity holds no active count, which is the signal to discard the Note Off;
   *         otherwise the resolved channel, the Expression Values that changed on it, and whether the
   *         Channel Pressure reset was applied.
   */
  def release(noteIdentity: MpeNoteIdentity, resetPressureOnEmpty: Boolean = false): Option[MpeReleaseResult] =
    noteChannels.get(noteIdentity).map { channel =>
      val state = channelStates(channel)
      val before = snapshotOf(state.expression)
      val deallocated = state.decrementReferenceCount(noteIdentity, nextTime())
      if (deallocated) {
        noteChannels.remove(noteIdentity)
        state.recomputeExpression()
      }

      val pressureWasReset = deallocated && !state.isOccupied && resetPressureOnEmpty &&
        state.expression.pressure != MpeExpression.DefaultPressure
      if (pressureWasReset) state.resetPressure()

      MpeReleaseResult(channel, diff(before, state.expression), pressureWasReset)
    }

  /**
   * Applies an Expression Pitch Bend received on an input channel to every note active on it, wherever the
   * pitch-class invariant has placed those notes, and applies the divergence rule to each affected output
   * channel.
   *
   * @param inputChannel   The input channel the Pitch Bend arrived on.
   * @param pitchBendCents The new Expression Pitch Bend in cents.
   * @return the output channels whose aggregate changed and any notes dropped by the divergence rule.
   */
  def updateExpressionPitchBend(inputChannel: Int, pitchBendCents: Double): MpeExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel),
      noteExpression => noteExpression.pitchBendCents = pitchBendCents,
      applyDivergenceRule)

  /**
   * Applies a Channel Pressure received on an input channel to every note active on it, wherever the
   * pitch-class invariant has placed those notes.
   *
   * @param inputChannel The input channel the Channel Pressure arrived on.
   * @param pressure     The new Channel Pressure value.
   * @return the output channels whose aggregate changed; never drops notes.
   */
  def updatePressure(inputChannel: Int, pressure: Int): MpeExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel), noteExpression => noteExpression.pressure = pressure)

  /**
   * Applies a pressure value addressed to a single note, as a Polyphonic Key Pressure is. An identity that
   * is not active yields an empty result, which is how a Polyphonic Key Pressure addressed to a note for
   * which no Note On was issued on that input channel is ignored.
   *
   * @param noteIdentity The note the pressure is addressed to.
   * @param pressure     The new pressure value.
   * @return the output channel whose aggregate changed, or an empty result; never drops notes.
   */
  def updatePressure(noteIdentity: MpeNoteIdentity, pressure: Int): MpeExpressionUpdateResult =
    updateExpressionValues(Seq(noteIdentity), noteExpression => noteExpression.pressure = pressure)

  /**
   * Applies a CC #74 (Timbre / Slide) value received on an input channel to every note active on it,
   * wherever the pitch-class invariant has placed those notes.
   *
   * @param inputChannel The input channel the CC #74 arrived on.
   * @param slide        The new CC #74 (Timbre / Slide) value.
   * @return the output channels whose aggregate changed; never drops notes.
   */
  def updateSlide(inputChannel: Int, slide: Int): MpeExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel), noteExpression => noteExpression.slide = slide)

  /**
   * Resets the allocator to its initial state: every active note and its channel binding is discarded, every
   * channel's retained aggregated Expression Values return to the defaults of [[MpeExpression]], and the
   * logical clock is rewound. No Note Off is owed for the discarded notes; emitting them is the caller's
   * responsibility, before the reset.
   */
  def reset(): Unit = {
    channelStates.values.foreach(_.reset())
    noteChannels.clear()
    _time = 0L
  }

  // State inspection accessors

  /** The output Member Channel bound to an active note, or `None` when it holds no active count. */
  def channelOf(noteIdentity: MpeNoteIdentity): Option[Int] = noteChannels.get(noteIdentity)

  /**
   * An immutable snapshot of the aggregated Expression Values of a channel, retained while the channel is
   * unoccupied. The snapshot keeps the values the channel held at the moment of the call: it does not track
   * later mutations, so it stays valid as a "before" reference across a subsequent allocation or release.
   *
   * @throws NoSuchElementException if `channel` is not a Member Channel of this allocator's Zone.
   */
  def channelExpression(channel: Int): MpeExpression = snapshotOf(channelStates(channel).expression)

  /**
   * An immutable snapshot of the Expression Values of an active note, with the same semantics as
   * [[channelExpression]].
   *
   * @throws NoSuchElementException if the note holds no active count. Use [[referenceCountOf]] or
   *                                [[channelOf]] to test for that beforehand.
   */
  def expressionFor(noteIdentity: MpeNoteIdentity): MpeExpression =
    snapshotOf(channelStates(noteChannels(noteIdentity)).expressionFor(noteIdentity))

  /** The reference count of a note, or 0 when it holds no active count. */
  def referenceCountOf(noteIdentity: MpeNoteIdentity): Int =
    noteChannels.get(noteIdentity).map(channelStates(_).referenceCountOf(noteIdentity)).getOrElse(0)

  /** The Note Identities currently active on a channel. */
  def activeNotes(channel: Int): Set[MpeNoteIdentity] = channelStates(channel).noteIdentities

  /** Every active note with the output Member Channel it is bound to, ordered by channel. */
  def activeAllocations: Seq[(MpeNoteIdentity, Int)] = noteChannels.toSeq.sortBy(_._2)

  /**
   * Returns the pitch class currently associated with a channel.
   *
   * If the channel is unoccupied, it returns `None`.
   */
  def channelPitchClass(channel: Int): Option[PitchClass] = channelStates(channel).pitchClass

  /**
   * Returns the number of channels that have at least one active note.
   */
  def activeChannelCount: Int = channelStates.values.count(_.isOccupied)

  /**
   * Returns whether a channel has any active notes.
   */
  def isChannelOccupied(channel: Int): Boolean = channelStates(channel).isOccupied

  /**
   * Returns the group to which a channel is currently assigned.
   */
  def channelGroupOf(channel: Int): Option[ChannelGroup] = channelStates(channel).group

  private def pitchClassGroupChannels: Seq[MpeChannelState] =
    channelStates.values.filter(_.group.contains(ChannelGroup.PitchClass)).toSeq

  private def pitchClassGroupCount: Int = pitchClassGroupChannels.size

  private def expressionGroupCount: Int =
    channelStates.values.count(_.group.contains(ChannelGroup.Expression))

  private def unoccupiedChannels: Seq[Int] = channelStates.values.filter(!_.isOccupied).map(_.channel).toSeq

  /** Returns the [[MpeChannelState]] of every channel that currently has at least one active note. */
  private def occupiedChannelStates: Seq[MpeChannelState] = channelStates.values.filter(_.isOccupied).toSeq

  /**
   * Returns the lowest- and highest-pitched active notes across the given occupied channel states.
   *
   * Lowest and highest are compared by [[MidiNote.number]]. The caller must pass only occupied
   * channels (each with at least one active note), so the note stream is guaranteed to be non-empty.
   *
   * @param states Occupied channel states to scan; must not be empty and each must have active notes.
   * @return A pair `(lowest, highest)` of [[MidiNote]] by ascending MIDI note number.
   */
  private def lowestAndHighestNotes(states: Seq[MpeChannelState]): (MidiNote, MidiNote) = {
    val notes = states.iterator.flatMap(_.noteIdentities.iterator.map(_.midiNote))
    var lowest = notes.next() // safe: callers pass only occupied channels, each with at least one note
    var highest = lowest
    for (note <- notes) {
      if (note.number < lowest.number) lowest = note
      if (note.number > highest.number) highest = note
    }
    (lowest, highest)
  }

  private def doAllocate(state: MpeChannelState,
                         noteIdentity: MpeNoteIdentity,
                         expression: Option[MpeExpression],
                         time: Long,
                         targetGroup: ChannelGroup): MpeAllocationResult = {
    val before = snapshotOf(state.expression)
    val existingIdentities = state.noteIdentities
    val noteExpression = MutableMpeExpression(
      expression.map(_.pitchBendCents).getOrElse(MpeExpression.DefaultPitchBendCents),
      expression.map(_.pressure).getOrElse(MpeExpression.DefaultPressure),
      expression.map(_.slide).getOrElse(MpeExpression.DefaultSlide))

    state.addNote(noteIdentity, noteExpression, time, targetGroup)
    noteChannels(noteIdentity) = state.channel
    val dropped = dropExistingNotesForHighBend(state, existingIdentities, noteExpression.pitchBendCents, time)
    state.recomputeExpression()

    MpeAllocationResult(state.channel, diff(before, state.expression), dropped)
  }

  /**
   * Drops the existing notes on a channel when a High Expression Pitch Bend means they can no longer
   * coexist with the newly added note: either the new note has a high bend, or the channel already held a
   * note with one.
   */
  private def dropExistingNotesForHighBend(state: MpeChannelState,
                                           existingIdentities: Set[MpeNoteIdentity],
                                           newPitchBendCents: Double,
                                           time: Long): Option[MpeDroppedNotes] = {
    if (existingIdentities.isEmpty) {
      None
    } else {
      val existingHighBend = existingIdentities.exists { noteIdentity =>
        isHighExpressionPitchBend(state.expressionFor(noteIdentity).pitchBendCents)
      }
      val newHighBend = isHighExpressionPitchBend(newPitchBendCents)
      if (existingHighBend || newHighBend) {
        Some(dropIdentities(state, existingIdentities.toSeq, time))
      } else {
        None
      }
    }
  }

  /**
   * Selects the single best channel from `candidates` using a lexicographic tie-break:
   * (a) no High Expression Pitch Bend (channels without a high bend are preferred),
   * (b) fewest active notes,
   * (c) oldest onset time (smallest `lastNoteOnTime`),
   * (d) oldest last Note Off time (smallest `lastNoteOffTime`),
   * (e) the preferred input channel first, then the lowest channel number.
   *
   * @param candidates       The channel states to choose from; must not be empty.
   * @param preferredChannel An optional channel number to favour in criterion (e).
   * @return The [[MpeChannelState]] that wins the tie-break.
   */
  private def bestCandidate(candidates: Seq[MpeChannelState], preferredChannel: Option[Int]): MpeChannelState =
    candidates.minBy { s =>
      (
        // (a) no high bend (false < true)
        hasHighExpressionPitchBend(s),
        // (b) fewest active Note Identities
        s.noteCount,
        // (c) oldest onset
        s.lastNoteOnTime,
        // (d) oldest last Note Off
        s.lastNoteOffTime,
        // (e) prefer the input channel
        if (preferredChannel.contains(s.channel)) 0 else 1,
        // (e) then the lowest channel number
        s.channel
      )
    }

  /**
   * Frees a channel so the incoming note can be placed on it, dropping all of the freed channel's
   * active notes. Boundary channels — those holding the highest- or lowest-pitched active note — are
   * preserved when possible. The final selection among the remaining candidates reuses the tie-break
   * criteria of [[bestCandidate]] (criterion (e) degenerates to the lowest channel number, since the
   * candidates are occupied).
   * When only one channel is occupied, it is freed unconditionally, regardless of register.
   * When every occupied channel is a boundary channel (the highest and lowest notes lie on different
   * channels, so neither can be preserved without dropping the other), the channel holding the lower
   * (bass) note is freed, retaining the upper melodic note.
   *
   * @param time The logical timestamp at which the freed notes are dropped.
   * @return The notes dropped from the freed channel.
   */
  private def freeChannel(time: Long): MpeDroppedNotes = {
    val occupied = occupiedChannelStates
    assert(occupied.nonEmpty)

    val target =
      if (occupied.sizeIs == 1) {
        // Only one candidate: free it regardless of register.
        occupied.head
      } else {
        val (lowest, highest) = lowestAndHighestNotes(occupied)
        val nonBoundary = occupied.filterNot { s =>
          s.noteIdentities.exists(n => n.midiNote.number == lowest.number || n.midiNote.number == highest.number)
        }
        if (nonBoundary.nonEmpty) {
          bestCandidate(nonBoundary, None)
        } else {
          // Every occupied channel is a boundary channel (extremes on different channels): free the
          // channel holding the lower (bass) note, retaining the upper melodic note.
          bestCandidate(occupied.filter(_.noteIdentities.exists(_.midiNote.number == lowest.number)), None)
        }
      }

    dropIdentities(target, target.noteIdentities.toSeq, time)
  }

  private def identitiesOn(inputChannel: Int): Seq[MpeNoteIdentity] =
    noteChannels.keys.filter(_.inputChannel == inputChannel).toSeq

  /**
   * Writes a new contribution into each of the given notes, applies an optional per-channel rule, then
   * recomputes each affected channel's aggregate and reports only the channels whose value actually
   * changed.
   *
   * Channels are reported in the order of the earliest onset among the notes updated on them, so the
   * output follows the order in which the performer sounded the notes rather than an incidental map order.
   *
   * @param noteIdentities The notes whose contribution changes; inactive ones are ignored.
   * @param write          Writes the new value into a note's Expression Values.
   * @param afterWrite     Applied to each affected channel after the writes and before the recomputation.
   */
  private def updateExpressionValues(noteIdentities: Seq[MpeNoteIdentity],
                                     write: MutableMpeExpression => Unit,
                                     afterWrite: MpeChannelState => Option[MpeDroppedNotes] = _ => None)
  : MpeExpressionUpdateResult = {
    val identitiesByChannel = noteIdentities
      .flatMap(noteIdentity => noteChannels.get(noteIdentity).map(channel => (channel, noteIdentity)))
      .groupMap(_._1)(_._2)
      .toSeq
      .sortBy { case (channel, identities) =>
        (identities.map(channelStates(channel).onsetTimeOf).min, channel)
      }

    val channelUpdates = Seq.newBuilder[MpeChannelExpressionUpdate]
    val droppedNotes = Seq.newBuilder[MpeDroppedNotes]
    for ((channel, identities) <- identitiesByChannel) {
      val state = channelStates(channel)
      val before = snapshotOf(state.expression)
      identities.foreach(noteIdentity => write(state.expressionFor(noteIdentity)))
      afterWrite(state).foreach(droppedNotes += _)
      state.recomputeExpression()
      val update = diff(before, state.expression)
      if (update != MpeExpressionUpdate.Unchanged) {
        channelUpdates += MpeChannelExpressionUpdate(channel, update)
      }
    }

    MpeExpressionUpdateResult(channelUpdates.result(), droppedNotes.result())
  }

  /**
   * Applies the divergence rule to a channel whose notes have just received a new Expression Pitch Bend:
   * when the channel holds more than one note and at least one of them now has a High Expression Pitch
   * Bend, the high-bend note with the greatest onset time — the most recently sounded — survives and every
   * other note on the channel is dropped.
   *
   * The single-high-bend case is the paper's rule as written. Several notes can acquire a high bend at once
   * only when they share an input channel, the Pitch Bend being a channel message that belongs to all of
   * them; retaining the most recently sounded preserves the performer's gesture on one voice, and leaving
   * exactly one note restores the invariant that a high-bend note is the sole note on its channel.
   */
  private def applyDivergenceRule(state: MpeChannelState): Option[MpeDroppedNotes] = {
    val identities = state.noteIdentities
    val highBendIdentities = identities.filter { noteIdentity =>
      isHighExpressionPitchBend(state.expressionFor(noteIdentity).pitchBendCents)
    }

    if (identities.sizeIs > 1 && highBendIdentities.nonEmpty) {
      val survivor = highBendIdentities.maxBy(state.onsetTimeOf)
      Some(dropIdentities(state, (identities - survivor).toSeq, nextTime()))
    } else {
      None
    }
  }

  /**
   * Drops the given notes from a channel, clearing their channel bindings so that a Note Off the performer
   * sends for them afterwards is discarded.
   *
   * @return the dropped notes, oldest onset first, each with the reference count it held.
   */
  private def dropIdentities(state: MpeChannelState,
                             noteIdentities: Seq[MpeNoteIdentity],
                             time: Long): MpeDroppedNotes = {
    val group = state.group.get
    val dropped = noteIdentities
      .sortBy(state.onsetTimeOf)
      .map(noteIdentity => MpeDroppedNote(noteIdentity, state.referenceCountOf(noteIdentity)))
    dropped.foreach { droppedNote =>
      state.removeNote(droppedNote.noteIdentity, time)
      noteChannels.remove(droppedNote.noteIdentity)
    }

    MpeDroppedNotes(state.channel, dropped, group)
  }

  /**
   * Copies Expression Values out of a live [[MutableMpeExpression]], so that the result keeps the values it
   * held at the moment of the call instead of tracking later mutations.
   */
  private def snapshotOf(expression: MpeExpression): MpeExpression =
    ImmutableMpeExpression(expression.pitchBendCents, expression.pressure, expression.slide)

  private def diff(before: MpeExpression, after: MpeExpression): MpeExpressionUpdate =
    MpeExpressionUpdate(
      pitchBendCents = Option.when(after.pitchBendCents != before.pitchBendCents)(after.pitchBendCents),
      pressure = Option.when(after.pressure != before.pressure)(after.pressure),
      slide = Option.when(after.slide != before.slide)(after.slide))
}

/** Constants and the [[MpeChannelAllocator.ChannelGroup]] taxonomy of the dual-group allocation strategy. */
object MpeChannelAllocator {

  /**
   * The absolute threshold in cents above which an Expression Pitch Bend is considered "high" and triggers note
   * dropping on shared channels.
   */
  private val ExpressionPitchBendThreshold: Double = 50.0

  /**
   * Logical partitioning of Member Channels into two groups to manage note allocation while
   * prioritizing intonation precision.
   */
  enum ChannelGroup {
    /**
     * Channels reserved for notes of distinct pitch classes. Within this group, no two occupied
     * Channels may have active notes of the same pitch class. This group ensures that the Zone
     * can accommodate as many distinct pitch classes as possible, each with an independently
     * controllable tuning offset.
     */
    case PitchClass

    /**
     * Channels available for notes whose pitch class is already represented in the Pitch Class
     * Group, or for notes that cannot be accommodated in the Pitch Class Group because all its
     * channels are occupied. This group accommodates scenarios where multiple notes of the same
     * pitch class must coexist with different Expression Pitch Bends.
     */
    case Expression
  }

  private def isHighExpressionPitchBend(pitchBendCents: Double): Boolean =
    Math.abs(pitchBendCents) > ExpressionPitchBendThreshold

  private def hasHighExpressionPitchBend(state: MpeChannelState): Boolean =
    state.noteIdentities.exists(n => isHighExpressionPitchBend(state.expressionFor(n).pitchBendCents))
}
