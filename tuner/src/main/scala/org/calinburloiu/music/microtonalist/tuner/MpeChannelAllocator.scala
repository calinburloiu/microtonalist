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
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass, ScPitchBendMidiMessage}

import scala.collection.mutable
import scala.util.boundary

/**
 * Holds the MPE per-note expression parameters transmitted on an MPE Member Channel.
 */
trait MpeExpression {
  /** Expressive pitch bend value, excluding any tuning offset. Ranges from -8192 to 8191; 0 is the centre (no bend). */
  def pitchBend: Int

  /** Channel pressure (aftertouch) value. Ranges from 0 to 127. */
  def pressure: Int

  /** MIDI CC #74 (Timbre / Slide) value. Ranges from 0 to 127; 64 is the centre. */
  def slide: Int
}

object MpeExpression {
  /** Default expressive pitch bend value (no bend). */
  val DefaultPitchBend: Int = 0

  /** Default channel pressure value (no pressure). */
  val DefaultPressure: Int = 0

  /** Default slide (CC #74) value (centre position). */
  val DefaultSlide: Int = 64
}

private class MutableMpeExpression(var pitchBend: Int = MpeExpression.DefaultPitchBend,
                                   var pressure: Int = MpeExpression.DefaultPressure,
                                   var slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

private case class ImmutableMpeExpression(pitchBend: Int = MpeExpression.DefaultPitchBend,
                                          pressure: Int = MpeExpression.DefaultPressure,
                                          slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

// TODO #143 Note expression is not currently updated

/**
 * Describes the notes that were removed from a channel as a side-effect of a new allocation.
 *
 * Notes can be dropped in two situations:
 *  - '''Channel exhaustion''': all Member Channels are occupied and a new note requires a free channel.
 *    The allocator evicts the least-important occupied channel, dropping all its notes.
 *  - '''High expressive pitch bend''': a note on a shared channel develops a pitch bend large enough to
 *    interfere with the intonation of the other notes on that channel.  All co-resident notes are dropped
 *    so the bending note can occupy the channel exclusively.
 *
 * @param channel The 0-indexed MIDI channel from which notes were dropped.
 * @param notes   The MIDI notes that were dropped from `channel`.
 * @param group   The [[ChannelGroup]] that `channel` belonged to at the time of the drop.
 */
case class DroppedNotes(channel: Int,
                        notes: Seq[MidiNote],
                        group: ChannelGroup)

// TODO #143 Add a channelExpression field of type MpeExpression which will be set on the output channel before the
//  Note On message in MpeTuner. The new field is averaged across all channel notes from ChannelState.

/**
 * Result of a channel allocation operation.
 *
 * @param channel      The 0-indexed MIDI channel assigned to the new note.
 * @param droppedNotes Any notes that were dropped as a result of this allocation.
 */
case class AllocationResult(channel: Int, droppedNotes: Option[DroppedNotes] = None)

/**
 * Holds all mutable runtime state for a single MPE Member Channel within the allocator.
 *
 * A channel is considered ''occupied'' while it has at least one active note (i.e. a Note On has
 * been received but the matching Note Off has not yet arrived).  Once it becomes unoccupied its
 * pitch-class and group assignments are cleared and the channel is eligible for reuse.
 *
 * @param channel The 0-indexed MIDI channel number this state object represents.
 */
private class ChannelState(val channel: Int) {
  // TODO #143 Switch to mutable.HashMap once the dropping logic in MpeChannelAllocator#updateExpressivePitchBend
  //  is fixed and lastAddedNote is no longer needed.
  private val _notes: mutable.LinkedHashMap[MidiNote, MutableMpeExpression] = mutable.LinkedHashMap.empty
  private var _pitchClass: Option[PitchClass] = None
  private var _group: Option[ChannelGroup] = None
  private var _lastOnsetTime: Long = 0L
  private var _lastNoteOffTime: Long = 0L

  /** An immutable snapshot of the MIDI notes currently active on this channel. */
  def notes: Set[MidiNote] = _notes.keySet.toSet

  /**
   * The pitch class shared by all active notes on this channel, or `None` when the channel is
   * unoccupied.  All notes on a single channel are required to belong to the same pitch class so
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
   * Zero when the channel has never received a note.
   */
  def lastOnsetTime: Long = _lastOnsetTime

  /**
   * The logical timestamp of the most recent Note Off event processed on this channel.
   * Zero when the channel has never had a note released.
   */
  def lastNoteOffTime: Long = _lastNoteOffTime

  /** `true` if this channel has at least one active note; `false` if it is free for allocation. */
  def isOccupied: Boolean = _notes.nonEmpty

  /**
   * Returns the mutable MPE expression associated with the given note.
   *
   * @param midiNote The MIDI note to look up.
   * @return The [[MutableMpeExpression]] for the note.
   */
  def expressionFor(midiNote: MidiNote): MutableMpeExpression = _notes(midiNote)

  /**
   * The most recently added note on this channel. Returns `None` if the channel is unoccupied.
   *
   * TODO #143 Remove once the dropping logic in [[MpeChannelAllocator#updateExpressivePitchBend]] is
   * fixed and the internal storage is switched to [[mutable.HashMap]].
   */
  def lastAddedNote: Option[MidiNote] = _notes.lastOption.map(_._1)

  /**
   * Adds a note to this channel, updating pitch class, group, and onset time accordingly.
   * Pitch class and group are only set when the channel transitions from unoccupied to occupied.
   * When the channel is already occupied, the `targetGroup` must match the existing group.
   *
   * @param midiNote    The MIDI note being added.
   * @param expression  The initial MPE expression for the note.
   * @param time        The logical timestamp of the onset.
   * @param targetGroup The channel group; must match the existing group when the channel is already occupied.
   */
  def addNote(midiNote: MidiNote,
              expression: MutableMpeExpression,
              time: Long,
              targetGroup: ChannelGroup): Unit = {
    if (_notes.isEmpty) {
      _pitchClass = Some(midiNote.pitchClass)
      _group = Some(targetGroup)
    } else {
      require(_group.contains(targetGroup),
        s"targetGroup $targetGroup does not match existing group ${_group.orNull} on channel $channel")
    }
    _notes(midiNote) = expression
    _lastOnsetTime = time
  }

  /**
   * Removes a note from this channel, updating note-off time accordingly.
   * Clears pitch class and group when the channel becomes unoccupied.
   *
   * @param midiNote The MIDI note to remove.
   * @param time     The logical timestamp of the release.
   */
  def removeNote(midiNote: MidiNote, time: Long): Unit = {
    if (_notes.remove(midiNote).isDefined) {
      _lastNoteOffTime = time
      if (_notes.isEmpty) {
        _pitchClass = None
        _group = None
      }
    }
  }

  /** Resets all channel state, clearing notes and resetting all timestamps. */
  def reset(): Unit = {
    _notes.clear()
    _pitchClass = None
    _group = None
    _lastOnsetTime = 0L
    _lastNoteOffTime = 0L
  }
}

/**
 * Manages channel allocation for a single MPE Zone following the dual-group allocation strategy.
 *
 * The dual-group strategy partitions available Member Channels into a Pitch Class Group
 * and an Expression Group to prioritize intonation precision over polyphony and expressive
 * independence when necessary.
 *
 * @param zone                              The MPE zone to allocate channels for.
 * @param expressionPitchBendThresholdCents The threshold in cents above which an expressive pitch bend is considered
 *                                          "high" and triggers note dropping on shared channels.
 */
class MpeChannelAllocator(val zone: MpeZone,
                          expressionPitchBendThresholdCents: Double = 50.0) {

  import MpeChannelAllocator.*

  private val channelStates: Map[Int, ChannelState] = zone.memberChannels.map(ch => ch -> ChannelState(ch)).toMap

  private var _time: Long = 0L

  private def nextTime(): Long = {
    _time += 1
    _time
  }

  private val expressionPitchBendThreshold: Int = {
    ScPitchBendMidiMessage.convertCentsToValue(expressionPitchBendThresholdCents, zone.memberPitchBendSensitivity)
  }

  reset()

  /**
   * Allocates a channel for a new note.
   *
   * @param midiNote            The MIDI note to allocate a channel for.
   * @param expressivePitchBend The initial expressive pitch bend value for the note.
   * @param preferredChannel    An optional preferred output channel (e.g., to preserve input
   *                            allocation in MPE input mode).
   * @return [[AllocationResult]] containing the assigned channel and any notes that were dropped.
   */
  def allocate(midiNote: MidiNote,
               expressivePitchBend: Int = 0,
               preferredChannel: Option[Int] = None): AllocationResult = boundary {
    val pc = midiNote.pitchClass
    val time = nextTime()

    // Step 1: Check Pitch Class Group availability
    val pitchClassInPCG = pitchClassGroupChannels.exists(_.pitchClass.contains(pc))
    if (!pitchClassInPCG && pitchClassGroupCount < zone.pitchClassGroupSize) {
      val target = bestCandidate(unoccupiedChannels.map(channelStates), preferredChannel)
      boundary.break(doAllocate(target, midiNote, expressivePitchBend, time, ChannelGroup.PitchClass))
    }

    // Step 2: Try Expression Group
    if (expressionGroupCount < zone.expressionGroupSize) {
      val target = bestCandidate(unoccupiedChannels.map(channelStates), preferredChannel)
      boundary.break(doAllocate(target, midiNote, expressivePitchBend, time, ChannelGroup.Expression))
    }

    // Step 3: Try sharing with the same pitch class
    val samePcChannels = channelStates.values.filter { s =>
      s.isOccupied && s.pitchClass.contains(pc)
    }.toSeq
    if (samePcChannels.nonEmpty) {
      val target = bestCandidate(samePcChannels, preferredChannel)
      boundary.break(doAllocate(target, midiNote, expressivePitchBend, time, target.group.get))
    }

    // Step 4: No channel with the same pitch class and all channels occupied -> free a channel
    val dropped = freeChannel(midiNote)
    doAllocate(channelStates(dropped.channel), midiNote, expressivePitchBend, time, dropped.group)
      .copy(droppedNotes = Some(dropped))
  }

  // TODO #143 To map incoming channel to output channel?

  /**
   * Releases a note from a channel.
   *
   * @param midiNote The MIDI note to release.
   * @param channel  The 0-indexed MIDI channel the note was on.
   */
  def release(midiNote: MidiNote, channel: Int): Unit = {
    channelStates(channel).removeNote(midiNote, nextTime())
  }

  // TODO #143 Bad assumption that the last note is being bent. To map incoming channel to output channel.
  //  Once fixed, remove lastAddedNote from ChannelState and switch its internal storage to mutable.HashMap.

  /**
   * Updates the expressive pitch bend for a channel.
   *
   * According to the MPE Tuner specification, if a channel holds multiple notes and one
   * develops a high expressive pitch bend, all other notes on that channel are dropped.
   *
   * @param channel   The 0-indexed MIDI channel.
   * @param pitchBend The new expressive pitch bend value.
   * @return Any notes that were dropped as a result of a high expressive pitch bend.
   */
  def updateExpressivePitchBend(channel: Int, pitchBend: Int): Option[DroppedNotes] = {
    val state = channelStates(channel)
    val currentNotes = state.notes
    if (currentNotes.size > 1 && isHighExpressivePitchBend(pitchBend)) {
      // Drop all notes except the one that is being bent
      // We assume the most recently added note is the one being bent
      val lastNote = state.lastAddedNote.get
      val notesToDrop = currentNotes - lastNote
      val dropped = DroppedNotes(channel, notesToDrop.toSeq, state.group.get)
      val time = nextTime()
      notesToDrop.foreach(n => state.removeNote(n, time))
      state.expressionFor(lastNote).pitchBend = pitchBend
      Some(dropped)
    } else {
      // Update the pitch bend for the most recent note
      state.lastAddedNote.foreach(n => state.expressionFor(n).pitchBend = pitchBend)
      None
    }
  }

  /**
   * Resets the allocator state, clearing all active notes.
   */
  def reset(): Unit = {
    channelStates.values.foreach(_.reset())
    _time = 0L
  }

  // State inspection accessors

  /**
   * Returns the MIDI notes currently active on a channel.
   */
  def activeNotes(channel: Int): Set[MidiNote] = channelStates(channel).notes

  /**
   * Returns the read-only MPE expression for an active note on a channel.
   *
   * @param channel  The 0-indexed MIDI channel.
   * @param midiNote The active MIDI note to look up.
   * @return The [[MpeExpression]] for the note.
   */
  def expressionFor(channel: Int, midiNote: MidiNote): MpeExpression =
    channelStates(channel).expressionFor(midiNote)

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

  private def isHighExpressivePitchBend(pitchBend: Int): Boolean =
    Math.abs(pitchBend) > expressionPitchBendThreshold

  private def hasHighExpressivePitchBend(state: ChannelState): Boolean = {
    state.notes.exists(n => isHighExpressivePitchBend(state.expressionFor(n).pitchBend))
  }

  private def pitchClassGroupChannels: Seq[ChannelState] =
    channelStates.values.filter(_.group.contains(ChannelGroup.PitchClass)).toSeq

  private def pitchClassGroupCount: Int = pitchClassGroupChannels.size

  private def expressionGroupCount: Int =
    channelStates.values.count(_.group.contains(ChannelGroup.Expression))

  private def unoccupiedChannels: Seq[Int] = channelStates.values.filter(!_.isOccupied).map(_.channel).toSeq

  private def doAllocate(state: ChannelState,
                         midiNote: MidiNote,
                         expressivePitchBend: Int,
                         time: Long,
                         targetGroup: ChannelGroup): AllocationResult = {
    val existingNotes = state.notes
    state.addNote(midiNote, MutableMpeExpression(expressivePitchBend), time, targetGroup)

    // Check if existing notes on this channel have high expressive pitch bend
    val dropped = if (existingNotes.nonEmpty) {
      val existingHighBend = existingNotes.exists(n => isHighExpressivePitchBend(state.expressionFor(n).pitchBend))
      val newHighBend = isHighExpressivePitchBend(expressivePitchBend)
      if (existingHighBend || newHighBend) {
        val toDrop = DroppedNotes(state.channel, existingNotes.toSeq, state.group.get)
        existingNotes.foreach(n => state.removeNote(n, time))
        Some(toDrop)
      } else {
        None
      }
    } else {
      None
    }

    AllocationResult(state.channel, dropped)
  }

  private def bestCandidate(candidates: Seq[ChannelState], preferredChannel: Option[Int]): ChannelState = {
    preferredChannel match {
      case Some(prefCh) if candidates.map(_.channel).contains(prefCh) => channelStates(prefCh)
      case _ => candidates.minBy { s =>
        (hasHighExpressivePitchBend(s), s.notes.size, s.lastNoteOffTime, s.lastOnsetTime, s.channel)
      }
    }
  }

  private def freeChannel(incomingNote: MidiNote): DroppedNotes = {
    val occupiedChannelStates = channelStates.values.filter(_.isOccupied).toSeq
    assert(occupiedChannelStates.nonEmpty)

    // Find the highest and lowest pitched notes across all channels
    val allNotes = occupiedChannelStates.flatMap(s => s.notes)
    val highestNote = allNotes.maxBy(_.number).number
    val lowestNote = allNotes.minBy(_.number).number

    // Exclude channels with the highest or lowest pitched notes
    val candidates = occupiedChannelStates.filterNot { s =>
      s.notes.exists(n => n.number == highestNote || n.number == lowestNote)
    }

    val target = if (candidates.nonEmpty) {
      candidates.minBy(_.lastOnsetTime)
    } else {
      // If all channels have boundary notes, pick the oldest
      occupiedChannelStates.minBy(_.lastOnsetTime)
    }

    DroppedNotes(target.channel, target.notes.toSeq, target.group.get)
  }
}

object MpeChannelAllocator {

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
     * pitch class must coexist with different expressive pitch bends.
     */
    case Expression
  }
}

