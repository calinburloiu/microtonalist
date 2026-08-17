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
 * A dropped note together with the number of Note Off messages owed for it: one per Note On that was
 * forwarded for it, which is the reference count it held at the moment of the drop.
 */
private[tuner] case class MpeDroppedNote(noteIdentity: MpeNoteIdentity, referenceCount: Int)

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
private[tuner] case class MpeDroppedNotes(channel: Int,
                                          notes: Seq[MpeDroppedNote],
                                          group: ChannelGroup)

/**
 * Result of a channel allocation operation.
 *
 * @param channel      The 0-indexed MIDI channel assigned to the note.
 * @param update       The Expression Values of `channel` that changed as a result of the allocation.
 * @param droppedNotes Any notes that were dropped as a result of this allocation.
 *
 * @param isDuplicate  `true` when the Note On raised an already active identity's reference count, so the
 *                     allocation algorithm was bypassed.
 */
private[tuner] case class MpeAllocationResult(channel: Int,
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
private[tuner] case class MpeReleaseResult(channel: Int,
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
private[tuner] case class MpeExpressionUpdateResult(channelUpdates: Seq[MpeChannelExpressionUpdate] = Nil,
                                                    droppedNotes: Seq[MpeDroppedNotes] = Nil)

/**
 * Result of rebuilding a Zone's allocator for a reconfiguration — the allocator, paired with what having it
 * costs the receiver, so that a caller cannot take the one without being handed the other.
 *
 * @param allocator  The allocator built against the reconfigured Zone, already settled against it.
 *
 * @param settlement What settling moved: the output channels whose aggregate the rebuild changed and the notes it
 *                   dropped ''by the divergence rule''. Empty for a Zone whose allocator was built fresh, there
 *                   being nothing to settle. The notes dropped for their input channel leaving MPE control are
 *                   deliberately absent — see [[MpeChannelAllocator.retaining]].
 */
private[tuner] case class MpeRebuildResult(allocator: MpeChannelAllocator,
                                           settlement: MpeExpressionUpdateResult = MpeExpressionUpdateResult())

/**
 * Manages channel allocation and the Expression Value model for a single MPE Zone.
 *
 * '''Allocation''' follows the dual-group strategy, which partitions the available Member Channels into a
 * Pitch Class Group and an Expression Group to prioritize intonation precision over polyphony and per-note
 * Expression Value independence when necessary. See the paper's "Allocation of Notes to Member Channels"
 * section.
 *
 * '''Expression Values''' are owned here as well, in two layers (see the paper's "Expression Value
 * Processing" section):
 *  - each active [[MpeNoteIdentity]] carries its own [[MpeExpression]] — with the Expression Pitch Bend in raw
 *    signed 14-bit units, see [[MpeExpression.pitchBend]] — and a reference count, one per Note On forwarded
 *    for it;
 *  - each output Member Channel carries an aggregate, the average over its active identities — one term per
 *    identity whatever its reference count — which is '''retained''' unchanged when the channel empties, so
 *    that every dimension has a defined value at all times.
 *
 * Alongside them the allocator owns the `MpeNoteIdentity -> channel` binding, maintained on every path that
 * adds or removes a note, dropping included, so that a Note Off for a note the allocator has already dropped
 * finds nothing.
 *
 * Every mutating method reports which of the affected channels' three Expression Values actually changed, so
 * the caller can emit only the messages that are needed.
 *
 * This class is '''unaware of the input mode''' by construction: every mode-dependent decision reaches it as
 * an argument (`preferredChannel` on [[allocate]], `resetPressureOnEmpty` on [[release]], and the choice of
 * whether to pass Expression Values at all). This keeps the mode a concern of [[MpeTuner]] alone.
 *
 * On an MPE Configuration Message that reconfigures the Zone, a fresh allocator is not built directly: it is
 * built through [[MpeChannelAllocator.retaining]], which transplants the state of the channels untouched by
 * the reconfiguration instead of discarding it, and settles the result against the new Zone in one reported
 * pass.
 *
 * @param zone                                The MPE zone to allocate channels for.
 * @param initialExpressionPitchBendThreshold The raw Expression Pitch Bend magnitude above which a note counts as
 *                                            having a High Expression Pitch Bend. Supplied by [[MpeTuner]], which
 *                                            is the only component that knows the Member Channel Pitch Bend
 *                                            Sensitivity the threshold's definition in cents is evaluated against.
 *                                            It has no default for that reason. The `initial` prefix is forced:
 *                                            the plain name belongs to the getter below, which a constructor
 *                                            parameter may not shadow.
 * @param retainedStates                      The per-channel state to seed this allocator with, keyed by output
 *                                            Member Channel, for a Zone reconfiguration that keeps some channels'
 *                                            notes and state. Empty for a freshly constructed allocator. Adopted
 *                                            by reference and mutated in place: the caller must not keep or reuse
 *                                            these [[MpeChannelState]] instances after passing them in. See
 *                                            [[MpeChannelAllocator.retaining]].
 */
private[tuner] class MpeChannelAllocator(private val zone: MpeZoneStructure,
                                         initialExpressionPitchBendThreshold: Int,
                                         retainedStates: Map[Int, MpeChannelState] = Map.empty) {

  import MpeChannelAllocator.*

  /**
   * Data structures with allocation information, keyed by output Member Channel. A channel present in
   * `retainedStates` adopts that state — notes, reference counts, Expression Values, pitch class and group —
   * and every other Member Channel of the Zone starts empty.
   */
  private val channelStates: Map[Int, MpeChannelState] =
    zone.memberChannels.map(ch => ch -> retainedStates.getOrElse(ch, MpeChannelState(ch))).toMap

  /**
   * The output Member Channel each active Note Identity is bound to, derived from the channel states so that
   * a transplanted state and its bindings can never disagree.
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

  private var _expressionPitchBendThreshold: Int = initialExpressionPitchBendThreshold

  private def nextTime(): Long = {
    _time += 1
    _time
  }

  /** @return the [[MpeZoneType]] (Lower or Upper) of the zone this allocator manages channels for. */
  def zoneType: MpeZoneType = zone.zoneType

  /**
   * Allocates an output Member Channel for a note, or increments the reference count of an already active
   * one.
   *
   * @param noteIdentity     The note to allocate a channel for.
   * @param expression       The note's initial Expression Values, or `None` to use the defaults of
   *                         [[MpeExpression]]. Ignored on a duplicate Note On, which changes nothing but the
   *                         reference count — see [[allocateDuplicate]].
   * @param preferredChannel An optional preferred output channel, applied by tie-break criterion (e). It is
   *                         a separate parameter rather than `noteIdentity.inputChannel` because the
   *                         preference is input-mode-dependent and this class is unaware of the input mode.
   * @return the assigned channel, the Expression Values that changed on it, and any notes dropped.
   */
  def allocate(noteIdentity: MpeNoteIdentity,
               expression: Option[MpeExpression] = None,
               preferredChannel: Option[Int] = None): MpeAllocationResult =
    noteChannels.get(noteIdentity) match {
      case Some(channel) => allocateDuplicate(channelStates(channel), noteIdentity)
      case None => allocateFresh(noteIdentity, expression, preferredChannel)
    }

  /**
   * Handles a Note On for an already active identity: the reference count is incremented and nothing else
   * changes. The allocation algorithm is bypassed, the note stays a single term in its channel's averages,
   * and the channel's set of active identities is untouched.
   *
   * Nothing can therefore change, which is why no Expression Value is written and no note is dropped:
   *  - the paper's "Case 1: Note Ons from the Same Input Channel" section states the Expression Value
   *    override in MPE Input Mode is a no-op — under the update propagation of the "Expression Value
   *    Propagation" section the note already holds its input channel's values — and that Non-MPE Input Mode
   *    has no input-channel values to override from at all;
   *  - the allocation-time High Expression Pitch Bend rules are predicated on a note being ''assigned'' to a
   *    channel, and no assignment occurs here;
   *  - the divergence rule cannot engage either, since it needs a note's Expression Pitch Bend to move, and
   *    the invariant that a high-bend note is the sole note on its channel is preserved automatically.
   *
   * The `expression` argument of [[allocate]] is consequently ignored on this path.
   */
  private def allocateDuplicate(state: MpeChannelState, noteIdentity: MpeNoteIdentity): MpeAllocationResult = {
    state.incrementReferenceCount(noteIdentity)
    MpeAllocationResult(state.channel, MpeExpressionUpdate.Unchanged, None, isDuplicate = true)
  }

  private def allocateFresh(noteIdentity: MpeNoteIdentity,
                            expression: Option[MpeExpression],
                            preferredChannel: Option[Int]): MpeAllocationResult = boundary {
    val pc = noteIdentity.midiNote.pitchClass
    val time = nextTime()

    // A channel transplanted by MpeChannelAllocator.retaining can leave a group holding more occupied
    // channels than the new Zone's group size allows (see its ScalaDoc). When that happens, the "this
    // group still has nominal room" checks below can hold true with no unoccupied channel actually left to
    // grant, because the channel budget spent by the over-subscribed group is not reflected in either
    // group's own count. The `unoccupied.nonEmpty` guard on Steps 1 and 2 catches that case and falls
    // through to Steps 3/4, which already handle a fully occupied Zone; it is not a guard against
    // over-subscription arising in the first place, which #262 leaves unguarded by design.
    val unoccupied = unoccupiedChannels.map(channelStates)

    // Step 1: Check Pitch Class Group availability
    val pitchClassInPCG = pitchClassGroupChannels.exists(_.pitchClass.contains(pc))
    if (!pitchClassInPCG && pitchClassGroupCount < zone.pitchClassGroupSize && unoccupied.nonEmpty) {
      val target = bestCandidate(unoccupied, preferredChannel)
      boundary.break(doAllocate(target, noteIdentity, expression, time, ChannelGroup.PitchClass))
    }

    // Step 2: Try Expression Group
    if (expressionGroupCount < zone.expressionGroupSize && unoccupied.nonEmpty) {
      val target = bestCandidate(unoccupied, preferredChannel)
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
      val before = state.expression
      val deallocated = state.decrementReferenceCount(noteIdentity, nextTime())
      if (deallocated) {
        noteChannels.remove(noteIdentity)
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
   * @param inputChannel The input channel the Pitch Bend arrived on.
   * @param pitchBend    The new Expression Pitch Bend, as the raw signed 14-bit value received.
   * @return the output channels whose aggregate changed and any notes dropped by the divergence rule.
   */
  def updateExpressionPitchBend(inputChannel: Int, pitchBend: Int): MpeExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel),
      (state, noteIdentity) => state.setPitchBend(noteIdentity, pitchBend),
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
    updateExpressionValues(identitiesOn(inputChannel),
      (state, noteIdentity) => state.setPressure(noteIdentity, pressure))

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
    updateExpressionValues(Seq(noteIdentity),
      (state, identity) => state.setPressure(identity, pressure))

  /**
   * Applies a CC #74 (Timbre / Slide) value received on an input channel to every note active on it,
   * wherever the pitch-class invariant has placed those notes.
   *
   * @param inputChannel The input channel the CC #74 arrived on.
   * @param slide        The new CC #74 (Timbre / Slide) value.
   * @return the output channels whose aggregate changed; never drops notes.
   */
  def updateSlide(inputChannel: Int, slide: Int): MpeExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel),
      (state, noteIdentity) => state.setSlide(noteIdentity, slide))

  // State inspection accessors

  /**
   * The raw Expression Pitch Bend magnitude above which a note counts as having a High Expression Pitch Bend.
   */
  def expressionPitchBendThreshold: Int = _expressionPitchBendThreshold

  /**
   * Assigns a new High Expression Pitch Bend threshold and re-applies the divergence rule to every occupied
   * channel, since the reclassification can leave a high-bend note sharing its channel — an invariant the paper's
   * "Summary of Note-Dropping Invariants" section requires at all times, so it cannot wait for the next event.
   *
   * Assignment and re-evaluation are one operation rather than a setter plus a separate pass: Scala requires an
   * assignment setter (`expressionPitchBendThreshold_=`) to return `Unit`, which would leave the caller no way to
   * receive the drops, and splitting the two would let a caller perform one without the other.
   *
   * This is the entry point of a member Pitch Bend Sensitivity change alone. A Zone reconfiguration settles its
   * rebuilt allocator through [[MpeChannelAllocator.retaining]], which injects the new threshold at construction
   * and runs the same [[settleAgainstConfiguration]] pass with the channels the reconfiguration moved.
   *
   * @return the output channels whose aggregate changed and any notes dropped by the divergence rule.
   */
  def setExpressionPitchBendThreshold(threshold: Int): MpeExpressionUpdateResult = {
    _expressionPitchBendThreshold = threshold
    settleAgainstConfiguration(Set.empty)
  }

  /** The output Member Channel bound to an active note, or `None` when it holds no active count. */
  def channelOf(noteIdentity: MpeNoteIdentity): Option[Int] = noteChannels.get(noteIdentity)

  /**
   * The aggregated Expression Values of a channel, retained while the channel is unoccupied. The value is
   * immutable and keeps what the channel held at the moment of the call: it does not track later mutations,
   * so it stays valid as a "before" reference across a subsequent allocation or release.
   *
   * @throws NoSuchElementException if `channel` is not a Member Channel of this allocator's Zone.
   */
  def channelExpression(channel: Int): MpeExpression = channelStates(channel).expression

  /**
   * The Expression Values of an active note, with the same semantics as [[channelExpression]].
   *
   * @throws NoSuchElementException if the note holds no active count. Use [[referenceCountOf]] or
   *                                [[channelOf]] to test for that beforehand.
   */
  def expressionFor(noteIdentity: MpeNoteIdentity): MpeExpression =
    channelStates(noteChannels(noteIdentity)).expressionFor(noteIdentity)

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

  /** The per-channel state of the given channels, for transplanting into a reconfigured Zone's allocator. */
  private def statesOf(channels: Set[Int]): Map[Int, MpeChannelState] =
    channelStates.view.filterKeys(channels.contains).toMap

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
    val before = state.expression
    val existingIdentities = state.noteIdentities
    val actualExpression = expression.getOrElse(ImmutableMpeExpression.Default)

    state.addNote(noteIdentity, actualExpression, time, targetGroup)
    noteChannels(noteIdentity) = state.channel
    val dropped = dropExistingNotesForHighBend(state, existingIdentities, actualExpression.pitchBend, time)

    MpeAllocationResult(state.channel, diff(before, state.expression), dropped)
  }

  /**
   * Drops the existing notes on a channel when a High Expression Pitch Bend means they can no longer
   * coexist with the newly added note: either the new note has a high bend, or the channel already held a
   * note with one.
   */
  private def dropExistingNotesForHighBend(state: MpeChannelState,
                                           existingIdentities: Set[MpeNoteIdentity],
                                           newPitchBend: Int,
                                           time: Long): Option[MpeDroppedNotes] = {
    if (existingIdentities.isEmpty) {
      None
    } else {
      val existingHighBend = existingIdentities.exists { noteIdentity =>
        isHighExpressionPitchBend(state.pitchBendOf(noteIdentity))
      }
      val newHighBend = isHighExpressionPitchBend(newPitchBend)
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
   * @param write          Writes the new value into a note's Expression Values on its channel.
   * @param afterWrite     Applied to each affected channel after the writes and before the aggregate is read.
   */
  private def updateExpressionValues(noteIdentities: Seq[MpeNoteIdentity],
                                     write: (MpeChannelState, MpeNoteIdentity) => Unit,
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
      val before = state.expression
      identities.foreach(noteIdentity => write(state, noteIdentity))
      afterWrite(state).foreach(droppedNotes += _)
      val update = diff(before, state.expression)
      if (update != MpeExpressionUpdate.Unchanged) {
        channelUpdates += MpeChannelExpressionUpdate(channel, update)
      }
    }

    MpeExpressionUpdateResult(channelUpdates.result(), droppedNotes.result())
  }

  /**
   * Applies the divergence rule to a channel whose notes may have just crossed the High Expression Pitch Bend
   * threshold — either because a new Expression Pitch Bend was written to them, or because the threshold itself
   * moved under them: when the channel holds more than one note and at least one of them now has a High
   * Expression Pitch Bend, the high-bend note with the greatest onset time — the most recently sounded —
   * survives and every other note on the channel is dropped.
   *
   * The single-high-bend case is the paper's rule as written. Several notes can acquire a high bend at once in
   * two ways. They may share an input channel, the Pitch Bend being a channel message that belongs to all of
   * them, in which case their bends are identical. Or the threshold itself may move under them, a member Pitch
   * Bend Sensitivity change reinterpreting every held bend at once, in which case the co-residents may come from
   * different input channels and carry genuinely different bends. The resolution is the same either way:
   * retaining the most recently sounded preserves the note the performer is most likely still shaping, and
   * leaving exactly one note restores the invariant that a high-bend note is the sole note on its channel.
   */
  private def applyDivergenceRule(state: MpeChannelState): Option[MpeDroppedNotes] = {
    val identities = state.noteIdentities
    val highBendIdentities = identities.filter { noteIdentity =>
      isHighExpressionPitchBend(state.pitchBendOf(noteIdentity))
    }

    if (identities.sizeIs > 1 && highBendIdentities.nonEmpty) {
      val survivor = highBendIdentities.maxBy(state.onsetTimeOf)
      Some(dropIdentities(state, (identities - survivor).toSeq, nextTime()))
    } else {
      None
    }
  }

  /**
   * Settles every occupied channel against a configuration this allocator has just been given — a new High
   * Expression Pitch Bend threshold, and for a Zone reconfiguration the channels it moved — writing no new
   * Expression Value of its own:
   *  - drops the notes that arrived on an input channel the reconfiguration took out of this Zone's control;
   *  - re-applies the divergence rule, since either the drops or a threshold that moved under the notes can
   *    leave a high-bend note sharing its channel.
   *
   * The two are '''one pass''': each channel's aggregate is read once before both and once after both, so the
   * single [[MpeExpressionUpdate]] reported for it measures the net move against the value the receiver actually
   * holds — which a reconfiguration leaves untouched on a channel it did not affect (MPE Spec §2.1.4). Moving a
   * channel's aggregate outside this pass would compare that aggregate against itself, which is what once left a
   * retained channel's Pitch Bend re-emitted while its CC #74 and Channel Pressure went stale.
   *
   * Reusing [[updateExpressionValues]] with a no-op write gives the pass the same channel ordering — by the
   * earliest onset among a channel's notes — and the same "report only channels whose aggregate actually changed"
   * behaviour as an ordinary Expression Value update, so no second traversal and no second rule is written.
   *
   * @param affectedInputChannels The channels the reconfiguration took out of MPE control, read here as input
   *                              channels; empty for a configuration change that moves no channel, such as a
   *                              member Pitch Bend Sensitivity message.
   * @return the output channels whose aggregate changed and the notes dropped ''by the divergence rule''. The
   *         notes dropped for their departed input channel are deliberately absent — see
   *         [[dropNotesFromAffectedInputChannels]].
   */
  private def settleAgainstConfiguration(affectedInputChannels: Set[Int]): MpeExpressionUpdateResult =
    updateExpressionValues(noteChannels.keys.toSeq, (_, _) => (), { state =>
      dropNotesFromAffectedInputChannels(state, affectedInputChannels)
      applyDivergenceRule(state)
    })

  /**
   * Drops the notes a Zone reconfiguration strands on a channel it keeps: those that arrived on an input channel
   * the reconfiguration moved out of this Zone's control. Their output channel survives, but the performer's Note
   * Off will arrive on a channel no longer under this Zone's control and be discarded, so the note would hang.
   *
   * The drop is silent — the returned [[MpeDroppedNotes]] is discarded — because [[MpeTuner]] emits these notes'
   * Note Offs from `stopNotesOn` while the old Zone structure is still in place, before the allocator is rebuilt,
   * so reporting them here would emit each one twice.
   */
  private def dropNotesFromAffectedInputChannels(state: MpeChannelState, affectedInputChannels: Set[Int]): Unit = {
    val affected = state.noteIdentities.filter(n => affectedInputChannels.contains(n.inputChannel)).toSeq
    if (affected.nonEmpty) {
      dropIdentities(state, affected, nextTime())
    }
  }

  /**
   * Drops the given notes from a channel, clearing their channel bindings so that a Note Off the performer
   * sends for them afterward is discarded.
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

  private def isHighExpressionPitchBend(pitchBend: Int): Boolean =
    Math.abs(pitchBend) > _expressionPitchBendThreshold

  private def hasHighExpressionPitchBend(state: MpeChannelState): Boolean =
    state.noteIdentities.exists(n => isHighExpressionPitchBend(state.pitchBendOf(n)))

  /**
   * Reports which of the three dimensions changed between two aggregates. All three are integers and compare
   * exactly: a channel's aggregate is what reaches the wire, except the tuning term, so a difference here is
   * exactly a message that has to go out.
   */
  private def diff(before: MpeExpression, after: MpeExpression): MpeExpressionUpdate =
    MpeExpressionUpdate(
      pitchBend = Option.when(after.pitchBend != before.pitchBend)(after.pitchBend),
      pressure = Option.when(after.pressure != before.pressure)(after.pressure),
      slide = Option.when(after.slide != before.slide)(after.slide))
}

/**
 * Companion of [[MpeChannelAllocator]], holding the types, constants, helpers and factory methods of the dual-group
 * allocation strategy.
 */
private[tuner] object MpeChannelAllocator {

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

  /**
   * Builds the allocator of a reconfigured Zone, transplanting the state of the channels that keep their role
   * across the reconfiguration, as the paper's Zone-configuration section requires: "Channels of a Zone
   * untouched by the reconfiguration keep their notes and state."
   *
   * A retained channel may end up in a group holding more occupied channels than the new Zone's group size
   * allows. No invariant breaks: the allocation algorithm reads the group counts only to decide whether a
   * group has room, so an over-subscribed group simply admits no new channel until notes are released — a
   * promise kept by the `unoccupied.nonEmpty` guard on [[allocateFresh]]'s Steps 1 and 2, whose comment
   * cross-references this one so neither can be edited in ignorance of the other.
   *
   * The rebuilt allocator is handed back already consistent with the reconfigured Zone, together with what that
   * costs the receiver: the transplant is followed by a [[settleAgainstConfiguration]] pass that drops the notes
   * whose ''input'' channel left MPE control and re-applies the divergence rule against the new threshold.
   * Transplanting and settling are one call rather than two so that neither can happen without the other, and
   * the settling comes second rather than being folded into the transplant so that every aggregate a rebuild
   * moves does so ''inside'' the reporting pass, against the value the receiver still holds. Dropping during the
   * transplant instead, as this method once did, moved them outside any pass and left the receiver holding a
   * stale CC #74 and Channel Pressure.
   *
   * The threshold is the one piece of state that is not carried over but supplied: only [[MpeTuner]] knows the
   * Member Channel Pitch Bend Sensitivity the reconfigured Zone now holds, which the threshold's definition in
   * cents is evaluated against. It is injected at construction, so the pass classifies against it from the start.
   *
   * @note `from` is consumed, not merely read: the retained [[MpeChannelState]] objects are transplanted into
   *       the returned allocator by reference, and the settling pass then drops notes from them in place. `from`
   *       must not be used again after this call — in particular, releasing on `from` a note that this call
   *       dropped will fail the reference-count invariant `from` no longer holds, because the shared state's note
   *       is already gone while `from`'s own `noteChannels` binding for it is not. The sole caller
   *       ([[MpeTuner]]'s Zone-reconfiguration path) discards `from` the moment this method returns.
   *
   * @param zone                                The reconfigured Zone.
   * @param from                                The allocator of the same Zone before the reconfiguration. Mutated
   *                                            in place and must not be used after this call returns — see the
   *                                            `@note` above.
   * @param affectedChannels                    The channels entering or leaving MPE control by the
   *                                            reconfiguration. They are read in both directions, which is why one
   *                                            set suffices. As output channels: a Member Channel of the new Zone
   *                                            keeps its state unless it is affected, and every other channel of
   *                                            the new Zone — one the old Zone did not have — starts empty. As
   *                                            input channels: a note that arrived on an affected channel is
   *                                            dropped even when its output channel is retained, because the
   *                                            performer's Note Off will arrive on a channel that is no longer
   *                                            under this Zone's control and would be discarded, leaving the note
   *                                            hanging.
   * @param initialExpressionPitchBendThreshold The raw Expression Pitch Bend magnitude the reconfigured Zone's
   *                                            member Pitch Bend Sensitivity implies, above which a note counts as
   *                                            having a High Expression Pitch Bend from now on. Named as the
   *                                            constructor parameter it becomes, since the settling pass
   *                                            classifies against it from the start.
   * @return the rebuilt allocator and its settlement — see [[settleAgainstConfiguration]] for why the departed
   *         notes are absent from the latter.
   */
  def retaining(zone: MpeZoneStructure,
                from: MpeChannelAllocator,
                affectedChannels: Set[Int],
                initialExpressionPitchBendThreshold: Int): MpeRebuildResult = {
    val retainedStates = from.statesOf(zone.memberChannels.toSet -- affectedChannels)
    val allocator = MpeChannelAllocator(zone, initialExpressionPitchBendThreshold, retainedStates)
    MpeRebuildResult(allocator, allocator.settleAgainstConfiguration(affectedChannels))
  }
}
