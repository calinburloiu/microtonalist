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
 * Represents a note that is currently sounding on an MPE Member Channel.
 */
trait ActiveNote {
  /** The MIDI note that was played. */
  def midiNote: MidiNote

  /** The expressive pitch bend value (excluding the tuning offset). */
  def expressivePitchBend: Int

  // TODO #143 channelPressure and slide are not update
  /** The MIDI Channel Pressure value. */
  def channelPressure: Int

  /** The MIDI CC #74 (Timbre/Slide) value. */
  def slide: Int
}

/**
 * Internal mutable implementation of [[ActiveNote]].
 */
private class MutableActiveNote(val midiNote: MidiNote,
                                var expressivePitchBend: Int = 0,
                                var channelPressure: Int = 0,
                                var slide: Int = 64) extends ActiveNote

private case class ImmutableActiveNote(midiNote: MidiNote,
                                       expressivePitchBend: Int = 0,
                                       channelPressure: Int = 0,
                                       slide: Int = 64) extends ActiveNote

case class DroppedNotes(channel: Int,
                        notes: Seq[MidiNote],
                        group: ChannelGroup)

/**
 * Result of a channel allocation operation.
 *
 * @param channel      The 0-indexed MIDI channel assigned to the new note.
 * @param droppedNotes Any notes that were dropped as a result of this allocation.
 */
case class AllocationResult(channel: Int, droppedNotes: Option[DroppedNotes] = None)

private class ChannelState(val channel: Int) {
  val notes: mutable.Buffer[MutableActiveNote] = mutable.Buffer.empty
  var pitchClass: Option[PitchClass] = None
  var group: Option[ChannelGroup] = None
  var lastOnsetTime: Long = 0L
  var lastNoteOffTime: Long = 0L
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
      boundary.break(doAllocate(target, midiNote, expressivePitchBend, time, Some(ChannelGroup.PitchClass)))
    }

    // Step 2: Try Expression Group
    if (expressionGroupCount < zone.expressionGroupSize) {
      val target = bestCandidate(unoccupiedChannels.map(channelStates), preferredChannel)
      boundary.break(doAllocate(target, midiNote, expressivePitchBend, time, Some(ChannelGroup.Expression)))
    }

    // Step 3: Try sharing with the same pitch class
    val samePcChannels = channelStates.values.filter { s =>
      s.notes.nonEmpty && s.pitchClass.contains(pc)
    }.toSeq
    if (samePcChannels.nonEmpty) {
      val target = bestCandidate(samePcChannels, preferredChannel)
      boundary.break(doAllocate(target, midiNote, expressivePitchBend, time))
    }

    // Step 4: No channel with the same pitch class and all channels occupied -> free a channel
    val dropped = freeChannel(midiNote)
    doAllocate(channelStates(dropped.channel), midiNote, expressivePitchBend, time, Some(dropped.group))
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
    val state = channelStates(channel)
    val idx = state.notes.indexWhere(_.midiNote == midiNote)
    if (idx >= 0) {
      state.notes.remove(idx)
      state.lastNoteOffTime = nextTime()
      if (state.notes.isEmpty) {
        state.pitchClass = None
        state.group = None
      }
    }
  }

  // TODO #143 Bad assumption that the last not is being bent. To map incoming channel to output channel.
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
    if (state.notes.size > 1 && isHighExpressivePitchBend(pitchBend)) {
      // Drop all notes except the one that is being bent
      // We assume the most recently added note is the one being bent
      val lastNote = state.notes.last
      val dropped = DroppedNotes(channel, state.notes.init.map(_.midiNote).toSeq, state.group.get)
      state.notes.clear()
      state.notes += lastNote
      lastNote.expressivePitchBend = pitchBend
      Some(dropped)
    } else {
      // Update the pitch bend for the most recent note
      if (state.notes.nonEmpty) {
        state.notes.last.expressivePitchBend = pitchBend
      }
      None
    }
  }

  /**
   * Resets the allocator state, clearing all active notes.
   */
  def reset(): Unit = {
    channelStates.values.foreach { s =>
      s.notes.clear()
      s.pitchClass = None
      s.group = None
      s.lastOnsetTime = 0L
      s.lastNoteOffTime = 0L
    }
    _time = 0L
  }

  // State inspection accessors

  /**
   * Returns the notes currently active on a channel.
   */
  def activeNotes(channel: Int): Seq[ActiveNote] = channelStates(channel).notes.toSeq

  /**
   * Returns the pitch class currently associated with a channel.
   *
   * If the channel is unoccupied, it returns `None`.
   */
  def channelPitchClass(channel: Int): Option[PitchClass] = channelStates(channel).pitchClass

  /**
   * Returns the number of channels that have at least one active note.
   */
  def activeChannelCount: Int = channelStates.values.count(_.notes.nonEmpty)

  /**
   * Returns whether a channel has any active notes.
   */
  def isChannelOccupied(channel: Int): Boolean = channelStates(channel).notes.nonEmpty

  /**
   * Returns the group to which a channel is currently assigned.
   */
  def channelGroupOf(channel: Int): Option[ChannelGroup] = channelStates(channel).group

  private def isHighExpressivePitchBend(pitchBend: Int): Boolean =
    Math.abs(pitchBend) > expressionPitchBendThreshold

  private def hasHighExpressivePitchBend(state: ChannelState): Boolean = {
    state.notes.exists(n => isHighExpressivePitchBend(n.expressivePitchBend))
  }

  private def pitchClassGroupChannels: Seq[ChannelState] =
    channelStates.values.filter(_.group.contains(ChannelGroup.PitchClass)).toSeq

  private def pitchClassGroupCount: Int = pitchClassGroupChannels.size

  private def expressionGroupCount: Int =
    channelStates.values.count(_.group.contains(ChannelGroup.Expression))

  private def unoccupiedChannels: Seq[Int] = channelStates.values.filter(_.notes.isEmpty).map(_.channel).toSeq

  private def doAllocate(state: ChannelState,
                         midiNote: MidiNote,
                         expressivePitchBend: Int,
                         time: Long,
                         targetGroup: Option[ChannelGroup] = None): AllocationResult = {
    val newNote = new MutableActiveNote(midiNote, expressivePitchBend)
    val existingNotes = state.notes.toSeq

    if (state.notes.isEmpty) {
      state.pitchClass = Some(midiNote.pitchClass)
      state.group = targetGroup
    }
    state.notes += newNote
    state.lastOnsetTime = time

    // Check if existing notes on this channel have high expressive pitch bend
    val dropped = if (state.notes.size > 1) {
      val existingHighBend = existingNotes.exists(n => isHighExpressivePitchBend(n.expressivePitchBend))
      val newHighBend = isHighExpressivePitchBend(expressivePitchBend)
      if (existingHighBend || newHighBend) {
        val toDrop = DroppedNotes(state.channel, existingNotes.map(_.midiNote), state.group.get)
        state.notes.clear()
        state.notes += newNote
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
    val occupiedChannelStates = channelStates.values.filter(_.notes.nonEmpty).toSeq
    assert(occupiedChannelStates.nonEmpty)

    // Find the highest and lowest pitched notes across all channels
    val allNotes = occupiedChannelStates.flatMap(s => s.notes)
    val highestNote = allNotes.maxBy(_.midiNote.number).midiNote.number
    val lowestNote = allNotes.minBy(_.midiNote.number).midiNote.number

    // Exclude channels with the highest or lowest pitched notes
    val candidates = occupiedChannelStates.filterNot { s =>
      s.notes.exists(n => n.midiNote.number == highestNote || n.midiNote.number == lowestNote)
    }

    val target = if (candidates.nonEmpty) {
      candidates.minBy(_.lastOnsetTime)
    } else {
      // If all channels have boundary notes, pick the oldest
      occupiedChannelStates.minBy(_.lastOnsetTime)
    }

    DroppedNotes(target.channel, target.notes.map(_.midiNote).toSeq, target.group.get)
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

