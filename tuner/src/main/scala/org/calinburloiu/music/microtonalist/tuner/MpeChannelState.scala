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
import org.calinburloiu.music.scmidi.PitchClass

import scala.collection.mutable

/**
 * Per-note state on an output Member Channel: the note's own Expression Values, its reference count and
 * the logical time of the Note On that allocated it.
 */
private[tuner] class MpeNoteState(val expression: MutableMpeExpression,
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
private[tuner] class MpeChannelState(val channel: Int) {
  private val _notes: mutable.HashMap[MpeNoteIdentity, MpeNoteState] = mutable.HashMap.empty
  private var _expression: ImmutableMpeExpression = ImmutableMpeExpression.Default
  private var _isExpressionStale: Boolean = false
  private var _pitchClass: Option[PitchClass] = None
  private var _group: Option[ChannelGroup] = None
  private var _lastNoteOnTime: Long = 0L
  private var _lastNoteOffTime: Long = 0L

  /** An immutable snapshot of the Note Identities currently active on this channel. */
  def noteIdentities: Set[MpeNoteIdentity] = _notes.keySet.toSet

  /** The number of distinct active Note Identities, whatever their reference counts. */
  def noteCount: Int = _notes.size

  /**
   * The channel's aggregated Expression Values: the average of its active notes' values, one term per Note
   * Identity whatever its reference count, retained unchanged while the channel is unoccupied.
   *
   * The average is recomputed on demand, the first time it is read after a mutation that can move it. The
   * value is immutable, so it stays valid across later mutations and serves as a "before" reference.
   */
  def expression: MpeExpression = {
    if (_isExpressionStale) {
      // Averaging is defined only while at least one note is active. When the channel is unoccupied the
      // aggregate is left untouched, which gives every dimension a defined value at all times.
      if (_notes.nonEmpty) _expression = averageExpression
      _isExpressionStale = false
    }
    _expression
  }

  /** The average of the active notes' Expression Values, rounding all three dimensions half up. */
  private def averageExpression: ImmutableMpeExpression = {
    val noteStates = _notes.values
    val count = _notes.size
    ImmutableMpeExpression(
      pitchBend = Math.round(noteStates.map(_.expression.pitchBend).sum.toDouble / count).toInt,
      pressure = Math.round(noteStates.map(_.expression.pressure).sum.toDouble / count).toInt,
      slide = Math.round(noteStates.map(_.expression.slide).sum.toDouble / count).toInt)
  }

  /** An immutable snapshot of the Expression Values of an active note on this channel. */
  def expressionFor(noteIdentity: MpeNoteIdentity): MpeExpression = {
    val expression = _notes(noteIdentity).expression
    ImmutableMpeExpression(expression.pitchBend, expression.pressure, expression.slide)
  }

  /** The Expression Pitch Bend of an active note, read without copying the other two dimensions. */
  def pitchBendOf(noteIdentity: MpeNoteIdentity): Int = _notes(noteIdentity).expression.pitchBend

  /** Sets the Expression Pitch Bend of an active note, invalidating the channel's aggregate. */
  def setPitchBend(noteIdentity: MpeNoteIdentity, pitchBend: Int): Unit = {
    _notes(noteIdentity).expression.pitchBend = pitchBend
    _isExpressionStale = true
  }

  /** Sets the Channel Pressure of an active note, invalidating the channel's aggregate. */
  def setPressure(noteIdentity: MpeNoteIdentity, pressure: Int): Unit = {
    _notes(noteIdentity).expression.pressure = pressure
    _isExpressionStale = true
  }

  /** Sets the CC #74 (Timbre / Slide) value of an active note, invalidating the channel's aggregate. */
  def setSlide(noteIdentity: MpeNoteIdentity, slide: Int): Unit = {
    _notes(noteIdentity).expression.slide = slide
    _isExpressionStale = true
  }

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
              expression: MpeExpression,
              time: Long,
              targetGroup: ChannelGroup): Unit = {
    if (_notes.isEmpty) {
      _pitchClass = Some(noteIdentity.midiNote.pitchClass)
      _group = Some(targetGroup)
    } else {
      require(_group.contains(targetGroup),
        s"targetGroup $targetGroup does not match existing group ${_group.orNull} on channel $channel")
    }
    val noteExpression = MutableMpeExpression(expression.pitchBend, expression.pressure, expression.slide)
    _notes(noteIdentity) = MpeNoteState(noteExpression, referenceCount = 1, onsetTime = time)
    _lastNoteOnTime = time
    _isExpressionStale = true
  }

  /**
   * Increments the reference count of an already active identity, for a duplicate Note On. Nothing else
   * changes: the identity keeps its onset time, the channel keeps its own timestamps because no allocation
   * occurs, and the aggregate is not invalidated because an identity contributes a single term to it
   * whatever its reference count.
   */
  def incrementReferenceCount(noteIdentity: MpeNoteIdentity): Unit = {
    require(referenceCountOf(noteIdentity) >= 1,
      s"$noteIdentity holds no active reference count on channel $channel")
    _notes(noteIdentity).referenceCount += 1
  }

  /**
   * Decrements the reference count of an active identity, removing it when the count reaches 0.
   *
   * @return `true` when the identity was removed, i.e. the count reached 0.
   */
  def decrementReferenceCount(noteIdentity: MpeNoteIdentity, time: Long): Boolean = {
    require(referenceCountOf(noteIdentity) >= 1,
      s"$noteIdentity holds no active reference count on channel $channel")
    val noteState = _notes(noteIdentity)
    noteState.referenceCount -= 1
    if (noteState.referenceCount == 0) {
      removeNote(noteIdentity, time)
      true
    } else {
      false
    }
  }

  /**
   * Removes a note from this channel whatever its reference count, updating note-off time accordingly.
   * Clears pitch class, group, and onset time when the channel becomes unoccupied. The aggregate is
   * invalidated, but a channel left unoccupied retains it rather than recomputing over an empty set.
   *
   * @param noteIdentity The note to remove.
   * @param time         The logical timestamp of the removal.
   */
  def removeNote(noteIdentity: MpeNoteIdentity, time: Long): Unit = {
    if (_notes.remove(noteIdentity).isDefined) {
      _lastNoteOffTime = time
      _isExpressionStale = true
      if (_notes.isEmpty) {
        _pitchClass = None
        _group = None
        _lastNoteOnTime = 0L
      }
    }
  }

  /** Returns the retained Channel Pressure to its default of 0. */
  def resetPressure(): Unit = {
    _expression = expression.asInstanceOf[ImmutableMpeExpression].copy(pressure = MpeExpression.DefaultPressure)
  }
}
