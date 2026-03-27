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

import org.calinburloiu.music.scmidi.{MidiNote, PitchClass, ScPitchBendMidiMessage}

import scala.collection.mutable
import scala.util.boundary

class ActiveNote(val midiNote: MidiNote,
                 var expressivePitchBend: Int = 0,
                 var channelPressure: Int = 0,
                 var slide: Int = 64)

case class DroppedNote(channel: Int, midiNote: MidiNote)

case class AllocationResult(channel: Int, droppedNotes: Seq[DroppedNote] = Seq.empty)

/**
 * Manages channel allocation for a single MPE Zone following the dual-group allocation strategy.
 *
 * @param zone                              The MPE zone to allocate channels for.
 * @param expressionPitchBendThresholdCents The threshold in cents above which an expressive pitch bend is considered
 *                                          "high" and triggers note dropping on shared channels.
 */
class MpeChannelAllocator(val zone: MpeZone,
                          expressionPitchBendThresholdCents: Double = 50.0) {

  import MpeChannelAllocator.*

  private class ChannelState(val channel: Int) {
    val notes: mutable.Buffer[ActiveNote] = mutable.Buffer.empty
    var pitchClass: Option[PitchClass] = None
    var group: Option[ChannelGroup] = None
    var lastOnsetTime: Long = 0L
    var lastNoteOffTime: Long = 0L
  }

  private val channelStates: Map[Int, ChannelState] =
    zone.memberChannels.map(ch => ch -> ChannelState(ch)).toMap

  private var _time: Long = 0L

  private def nextTime(): Long = {
    _time += 1
    _time
  }

  private val expressionPitchBendThreshold: Int = {
    ScPitchBendMidiMessage.convertCentsToValue(expressionPitchBendThresholdCents, zone.memberPitchBendSensitivity)
  }

  def allocate(midiNote: MidiNote,
               expressivePitchBend: Int = 0,
               preferredChannel: Option[Int] = None): AllocationResult = boundary {
    val pc = midiNote.pitchClass
    val time = nextTime()

    // Try the preferred channel first (MPE input mode)
    preferredChannel.foreach { prefCh =>
      val state = channelStates.get(prefCh)
      state.foreach { s =>
        if (s.notes.isEmpty || s.pitchClass.contains(pc)) {
          // Can use the preferred channel if it doesn't violate constraints
          val canUsePitchClassGroup = s.group.isEmpty || s.group.contains(ChannelGroup.PitchClass)
          val canUseExpressionGroup = s.group.isEmpty || s.group.contains(ChannelGroup.Expression)
          val pitchClassInPCG = pitchClassGroupChannels.exists(cs => cs.pitchClass.contains(pc) && cs.channel != prefCh)

          if (s.notes.isEmpty && !pitchClassInPCG && pitchClassGroupCount < zone.pitchClassGroupSize &&
            canUsePitchClassGroup) {
            boundary.break(doAllocate(s, midiNote, expressivePitchBend, time, ChannelGroup.PitchClass))
          } else if (s.notes.isEmpty && expressionGroupCount < zone.expressionGroupSize && canUseExpressionGroup) {
            boundary.break(doAllocate(s, midiNote, expressivePitchBend, time, ChannelGroup.Expression))
          } else if (s.pitchClass.contains(pc)) {
            boundary.break(doAllocateShared(s, midiNote, expressivePitchBend, time))
          }
        }
      }
    }

    // Step 1: Check Pitch Class Group availability
    val pitchClassInPCG = pitchClassGroupChannels.exists(_.pitchClass.contains(pc))
    if (!pitchClassInPCG && pitchClassGroupCount < zone.pitchClassGroupSize) {
      val unoccupiedPCG = unoccupiedChannels.filter { s =>
        s.group.isEmpty || s.group.contains(ChannelGroup.PitchClass)
      }
      if (unoccupiedPCG.nonEmpty) {
        val target = unoccupiedPCG.minBy(_.channel)
        boundary.break(doAllocate(target, midiNote, expressivePitchBend, time, ChannelGroup.PitchClass))
      }
    }

    // Step 2: Try Expression Group
    if (expressionGroupCount < zone.expressionGroupSize) {
      val unoccupiedEG = unoccupiedChannels.filter { s =>
        s.group.isEmpty || s.group.contains(ChannelGroup.Expression)
      }
      if (unoccupiedEG.nonEmpty) {
        val target = unoccupiedEG.minBy(_.channel)
        boundary.break(doAllocate(target, midiNote, expressivePitchBend, time, ChannelGroup.Expression))
      }
    }

    // Step 3: Try sharing with same pitch class
    val samePcChannels = channelStates.values.filter(s => s.notes.nonEmpty && s.pitchClass.contains(pc)).toSeq
    if (samePcChannels.nonEmpty) {
      val target = bestSharingCandidate(samePcChannels)
      boundary.break(doAllocateShared(target, midiNote, expressivePitchBend, time))
    }

    // Step 4: No channel with same pitch class and all channels occupied -> free a channel
    val dropped = freeChannel(midiNote)
    val freedState = channelStates.values.find(s => s.notes.isEmpty).get
    val group = if (pitchClassGroupCount < zone.pitchClassGroupSize) {
      ChannelGroup.PitchClass
    } else {
      ChannelGroup.Expression
    }
    val result = doAllocate(freedState, midiNote, expressivePitchBend, time, group)
    AllocationResult(result.channel, dropped ++ result.droppedNotes)
  }

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

  def updateExpressivePitchBend(channel: Int, pitchBend: Int): Seq[DroppedNote] = {
    val state = channelStates(channel)
    if (state.notes.size > 1 && isHighExpressivePitchBend(pitchBend)) {
      // Drop all notes except the one that is being bent
      // We assume the most recently added note is the one being bent
      val lastNote = state.notes.last
      val dropped = state.notes.init.map(n => DroppedNote(channel, n.midiNote)).toSeq
      state.notes.clear()
      state.notes += lastNote
      lastNote.expressivePitchBend = pitchBend
      dropped
    } else {
      // Update the pitch bend for the most recent note
      if (state.notes.nonEmpty) {
        state.notes.last.expressivePitchBend = pitchBend
      }
      Seq.empty
    }
  }

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
  def activeNotes(channel: Int): Seq[ActiveNote] = channelStates(channel).notes.toSeq

  def channelPitchClass(channel: Int): Option[PitchClass] = channelStates(channel).pitchClass

  def activeChannelCount: Int = channelStates.values.count(_.notes.nonEmpty)

  def isChannelOccupied(channel: Int): Boolean = channelStates(channel).notes.nonEmpty

  def channelGroupOf(channel: Int): ChannelGroup = channelStates(channel).group.get

  private def isHighExpressivePitchBend(pitchBend: Int): Boolean =
    Math.abs(pitchBend) > expressionPitchBendThreshold

  private def pitchClassGroupChannels: Iterable[ChannelState] =
    channelStates.values.filter(_.group.contains(ChannelGroup.PitchClass))

  private def pitchClassGroupCount: Int = pitchClassGroupChannels.size

  private def expressionGroupCount: Int =
    channelStates.values.count(_.group.contains(ChannelGroup.Expression))

  private def unoccupiedChannels: Iterable[ChannelState] =
    channelStates.values.filter(_.notes.isEmpty)

  private def doAllocate(state: ChannelState, midiNote: MidiNote, expressivePitchBend: Int,
                         time: Long, group: ChannelGroup): AllocationResult = {
    val note = ActiveNote(midiNote, expressivePitchBend)
    state.notes += note
    state.pitchClass = Some(midiNote.pitchClass)
    state.group = Some(group)
    state.lastOnsetTime = time

    // Check if existing notes on this channel have high expressive pitch bend
    val dropped = if (state.notes.size > 1) {
      val existingHighBend = state.notes.init.exists(n => isHighExpressivePitchBend(n.expressivePitchBend))
      val newHighBend = isHighExpressivePitchBend(expressivePitchBend)
      if (existingHighBend || newHighBend) {
        val toDrop = state.notes.init.map(n => DroppedNote(state.channel, n.midiNote)).toSeq
        val kept = state.notes.last
        state.notes.clear()
        state.notes += kept
        toDrop
      } else {
        Seq.empty
      }
    } else {
      Seq.empty
    }

    AllocationResult(state.channel, dropped)
  }

  private def doAllocateShared(state: ChannelState, midiNote: MidiNote, expressivePitchBend: Int,
                               time: Long): AllocationResult = {
    val note = ActiveNote(midiNote, expressivePitchBend)
    state.notes += note
    state.lastOnsetTime = time

    // Check high expressive pitch bend rules
    val existingHighBend = state.notes.init.exists(n => isHighExpressivePitchBend(n.expressivePitchBend))
    val newHighBend = isHighExpressivePitchBend(expressivePitchBend)
    val dropped = if (existingHighBend || newHighBend) {
      val toDrop = state.notes.init.map(n => DroppedNote(state.channel, n.midiNote)).toSeq
      val kept = state.notes.last
      state.notes.clear()
      state.notes += kept
      toDrop
    } else {
      Seq.empty
    }

    AllocationResult(state.channel, dropped)
  }

  private def bestSharingCandidate(candidates: Seq[ChannelState]): ChannelState = {
    candidates.minBy(s => (s.notes.size, s.lastNoteOffTime))
  }

  private def freeChannel(incomingNote: MidiNote): Seq[DroppedNote] = {
    val occupiedChannels = channelStates.values.filter(_.notes.nonEmpty).toSeq
    if (occupiedChannels.isEmpty) {
      Seq.empty
    } else {
      // Find highest and lowest pitched notes across all channels
      val allNotes = occupiedChannels.flatMap(s => s.notes.map(n => (s, n)))
      val highestNote = allNotes.maxBy(_._2.midiNote.number)._2.midiNote.number
      val lowestNote = allNotes.minBy(_._2.midiNote.number)._2.midiNote.number

      // Exclude channels with highest or lowest pitched notes
      val candidates = occupiedChannels.filterNot { s =>
        s.notes.exists(n => n.midiNote.number == highestNote || n.midiNote.number == lowestNote)
      }

      val target = if (candidates.nonEmpty) {
        candidates.minBy(_.lastOnsetTime)
      } else {
        // If all channels have boundary notes, pick the oldest
        occupiedChannels.minBy(_.lastOnsetTime)
      }

      val dropped = target.notes.map(n => DroppedNote(target.channel, n.midiNote)).toSeq
      target.notes.clear()
      target.pitchClass = None
      target.group = None

      dropped
    }
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
