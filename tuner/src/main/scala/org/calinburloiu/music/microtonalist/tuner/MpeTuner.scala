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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.scmidi.*

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.collection.mutable

sealed trait MpeInputMode

object MpeInputMode {
  case object NonMpe extends MpeInputMode

  case object Mpe extends MpeInputMode
}

/**
 * Tuner that uses MIDI Polyphonic Expression (MPE) to apply microtonal tunings to polyphonic MIDI streams.
 *
 * @param zones     A pair of MpeZone instances (lower and upper).
 * @param inputMode Non-MPE or MPE input mode.
 */
class MpeTuner(val zones: (MpeZone, MpeZone) = MpeTuner.DefaultZones,
               var inputMode: MpeInputMode = MpeInputMode.NonMpe) extends Tuner with StrictLogging {

  override val typeName: String = MpeTuner.TypeName

  private val lowerZone: MpeZone = zones._1
  private val upperZone: MpeZone = zones._2

  private val lowerAllocator: Option[MpeChannelAllocator] =
    if (lowerZone.isEnabled) Some(new MpeChannelAllocator(lowerZone)) else None
  private val upperAllocator: Option[MpeChannelAllocator] =
    if (upperZone.isEnabled) Some(new MpeChannelAllocator(upperZone)) else None

  private var _currTuning: Tuning = Tuning.Standard

  // Track which channel each note is on: (midiNote, inputChannel) -> outputChannel
  private val noteChannelMap: mutable.Map[(MidiNote, Int), Int] = mutable.Map.empty
  // Track input->output channel mapping for MPE input mode
  private val mpeInputChannelMap: mutable.Map[Int, Int] = mutable.Map.empty

  // Per-channel expressive pitch bend tracking (for non-MPE input, per-note tracking)
  // For MPE input, each member channel has its own expressive pitch bend
  private val channelExpressivePitchBend: mutable.Map[Int, Int] = mutable.Map.empty
  private val channelPressureMap: mutable.Map[Int, Int] = mutable.Map.empty
  private val channelSlideMap: mutable.Map[Int, Int] = mutable.Map.empty

  // For non-MPE input, track the global expressive pitch bend from input
  private var _globalExpressivePitchBend: Int = 0

  override def reset(): Seq[MidiMessage] = {
    _currTuning = Tuning.Standard
    noteChannelMap.clear()
    mpeInputChannelMap.clear()
    channelExpressivePitchBend.clear()
    channelPressureMap.clear()
    channelSlideMap.clear()
    _globalExpressivePitchBend = 0

    lowerAllocator.foreach(_.reset())
    upperAllocator.foreach(_.reset())

    val buffer = mutable.Buffer[MidiMessage]()

    // Output MPE Configuration Messages (MCM) for each enabled zone
    if (lowerZone.isEnabled) {
      buffer ++= mcmMessages(lowerZone)
      buffer ++= pitchBendSensitivityMessages(lowerZone)
    }
    if (upperZone.isEnabled) {
      buffer ++= mcmMessages(upperZone)
      buffer ++= pitchBendSensitivityMessages(upperZone)
    }

    buffer.toSeq
  }

  override def tune(tuning: Tuning): Seq[MidiMessage] = {
    _currTuning = tuning
    val buffer = mutable.Buffer[MidiMessage]()

    // Update pitch bend on all occupied member channels
    lowerAllocator.foreach { alloc =>
      updateTuningOnAllocator(alloc, lowerZone, buffer)
    }
    upperAllocator.foreach { alloc =>
      updateTuningOnAllocator(alloc, upperZone, buffer)
    }

    buffer.toSeq
  }

  override def process(message: MidiMessage): Seq[MidiMessage] = {
    message match {
      case shortMessage: ShortMessage =>
        processShortMessage(shortMessage)
      case _ =>
        Seq(message)
    }
  }

  private def processShortMessage(msg: ShortMessage): Seq[MidiMessage] = {
    val channel = msg.getChannel
    val buffer = mutable.Buffer[MidiMessage]()

    // Check for MCM (MPE Configuration Message): RPN 6 on channel 0 or 15
    // MCM is CC 101=0, CC 100=6, CC 6=memberCount on master channel

    msg.getCommand match {
      case ShortMessage.NOTE_ON if msg.getData2 > 0 =>
        processNoteOn(buffer, channel, msg.getData1, msg.getData2)
      case ShortMessage.NOTE_ON if msg.getData2 == 0 =>
        processNoteOff(buffer, channel, msg.getData1, 0)
      case ShortMessage.NOTE_OFF =>
        processNoteOff(buffer, channel, msg.getData1, msg.getData2)
      case ShortMessage.PITCH_BEND =>
        processPitchBend(buffer, channel, ScPitchBendMidiMessage.convertDataBytesToValue(msg.getData1, msg.getData2))
      case ShortMessage.CONTROL_CHANGE =>
        processCc(buffer, channel, msg.getData1, msg.getData2)
      case ShortMessage.CHANNEL_PRESSURE =>
        processChannelPressure(buffer, channel, msg.getData1)
      case ShortMessage.POLY_PRESSURE =>
        processPolyPressure(buffer, channel, msg.getData1, msg.getData2)
      case ShortMessage.PROGRAM_CHANGE =>
        // Forward on master channel
        forwardOnMasterChannel(buffer, msg)
      case _ =>
        buffer += msg
    }

    buffer.toSeq
  }

  private def processNoteOn(buffer: mutable.Buffer[MidiMessage], inputChannel: Int,
                            midiNote: MidiNote, velocity: Int): Unit = {
    val allocator = getAllocatorForInput(inputChannel)
    allocator match {
      case Some(alloc) =>
        val zone = alloc.zone
        val preferredChannel = if (inputMode == MpeInputMode.Mpe && zone.memberChannels.contains(inputChannel))
          Some(inputChannel) else None

        val result = alloc.allocate(midiNote, preferredChannel = preferredChannel)
        val outChannel = result.channel

        // Handle dropped notes
        result.droppedNotes.foreach { dropped =>
          buffer += ScNoteOffMidiMessage(dropped.channel, dropped.midiNote).javaMidiMessage
        }

        // Track the note
        noteChannelMap((midiNote, inputChannel)) = outChannel
        if (inputMode == MpeInputMode.Mpe) {
          mpeInputChannelMap(inputChannel) = outChannel
        }

        // Compute and send control dimensions before Note On
        val tuningOffset = _currTuning(midiNote.pitchClass)
        val expressiveBend = if (inputMode == MpeInputMode.Mpe) {
          channelExpressivePitchBend.getOrElse(inputChannel, 0)
        } else {
          0
        }

        val totalPitchBend = computeOutputPitchBend(outChannel, alloc, zone, tuningOffset)
        buffer += ScPitchBendMidiMessage(outChannel, totalPitchBend).javaMidiMessage

        // CC #74 (slide) - default 64
        val slide = channelSlideMap.getOrElse(inputChannel, 64)
        buffer += ScCcMidiMessage(outChannel, 74, slide).javaMidiMessage

        // Channel Pressure - default 0
        val pressure = channelPressureMap.getOrElse(inputChannel, 0)
        buffer += ScChannelPressureMidiMessage(outChannel, pressure).javaMidiMessage

        // Note On
        buffer += ScNoteOnMidiMessage(outChannel, midiNote, velocity).javaMidiMessage

      case None =>
        // No allocator for this channel, forward as-is
        buffer += ScNoteOnMidiMessage(inputChannel, midiNote, velocity).javaMidiMessage
    }
  }

  private def processNoteOff(buffer: mutable.Buffer[MidiMessage], inputChannel: Int,
                             midiNote: MidiNote, velocity: Int): Unit = {
    noteChannelMap.remove((midiNote, inputChannel)) match {
      case Some(outChannel) =>
        val allocator = getAllocatorForOutput(outChannel)
        allocator.foreach(_.release(midiNote, outChannel))
        buffer += ScNoteOffMidiMessage(outChannel, midiNote, velocity).javaMidiMessage
        // Clean up MPE input channel map if no more notes on this input channel
        if (inputMode == MpeInputMode.Mpe) {
          if (!noteChannelMap.values.exists(_ == mpeInputChannelMap.getOrElse(inputChannel, -1))) {
            mpeInputChannelMap.remove(inputChannel)
          }
        }
      case None =>
        buffer += ScNoteOffMidiMessage(inputChannel, midiNote, velocity).javaMidiMessage
    }
  }

  private def processPitchBend(buffer: mutable.Buffer[MidiMessage], inputChannel: Int, pitchBendValue: Int): Unit = {
    if (inputMode == MpeInputMode.Mpe) {
      // Check if it's a master channel
      if (isMasterChannel(inputChannel)) {
        // Forward master channel pitch bend without modification
        buffer += ScPitchBendMidiMessage(inputChannel, pitchBendValue).javaMidiMessage
      } else {
        // Per-note pitch bend in MPE input - treat as expressive pitch bend
        channelExpressivePitchBend(inputChannel) = pitchBendValue
        val outChannel = mpeInputChannelMap.getOrElse(inputChannel, inputChannel)
        val allocator = getAllocatorForOutput(outChannel)
        allocator.foreach { alloc =>
          val dropped = alloc.updateExpressivePitchBend(outChannel, pitchBendValue)
          dropped.foreach { d =>
            buffer += ScNoteOffMidiMessage(d.channel, d.midiNote).javaMidiMessage
          }
          val zone = alloc.zone
          val pc = alloc.channelPitchClass(outChannel)
          pc.foreach { pitchClass =>
            val tuningOffset = _currTuning(pitchClass)
            val totalPitchBend = computeOutputPitchBend(outChannel, alloc, zone, tuningOffset)
            buffer += ScPitchBendMidiMessage(outChannel, totalPitchBend).javaMidiMessage
          }
        }
      }
    } else {
      // Non-MPE input: redirect pitch bend to master channel as zone-level pitch bend
      _globalExpressivePitchBend = pitchBendValue
      if (lowerZone.isEnabled) {
        buffer += ScPitchBendMidiMessage(lowerZone.masterChannel, pitchBendValue).javaMidiMessage
      }
      if (upperZone.isEnabled) {
        buffer += ScPitchBendMidiMessage(upperZone.masterChannel, pitchBendValue).javaMidiMessage
      }
    }
  }

  private def processCc(buffer: mutable.Buffer[MidiMessage], inputChannel: Int,
                        ccNumber: Int, ccValue: Int): Unit = {
    ccNumber match {
      case 74 =>
        // Slide - forward to appropriate member channel
        channelSlideMap(inputChannel) = ccValue
        forwardToMemberChannel(buffer, inputChannel, ScCcMidiMessage(_, 74, ccValue))
      case ScCcMidiMessage.SustainPedal | ScCcMidiMessage.SostenutoPedal | ScCcMidiMessage.SoftPedal |
           ScCcMidiMessage.Modulation | ScCcMidiMessage.ResetAllControllers =>
        // Zone-level messages - forward on master channel
        if (lowerZone.isEnabled) {
          buffer += ScCcMidiMessage(lowerZone.masterChannel, ccNumber, ccValue).javaMidiMessage
        } else if (upperZone.isEnabled) {
          buffer += ScCcMidiMessage(upperZone.masterChannel, ccNumber, ccValue).javaMidiMessage
        }
      case _ =>
        // Forward as-is
        forwardOnMasterChannel(buffer, new ShortMessage(ShortMessage.CONTROL_CHANGE, inputChannel, ccNumber, ccValue))
    }
  }

  private def processChannelPressure(buffer: mutable.Buffer[MidiMessage], inputChannel: Int,
                                     pressure: Int): Unit = {
    channelPressureMap(inputChannel) = pressure
    forwardToMemberChannel(buffer, inputChannel, ScChannelPressureMidiMessage(_, pressure))
  }

  private def processPolyPressure(buffer: mutable.Buffer[MidiMessage], inputChannel: Int,
                                  midiNote: MidiNote, pressure: Int): Unit = {
    // Convert Poly Pressure to Channel Pressure on the appropriate member channel
    noteChannelMap.get((midiNote, inputChannel)).foreach { outChannel =>
      buffer += ScChannelPressureMidiMessage(outChannel, pressure).javaMidiMessage
    }
  }

  private def forwardToMemberChannel(buffer: mutable.Buffer[MidiMessage], inputChannel: Int,
                                     makeMessage: Int => ScMidiMessage): Unit = {
    if (inputMode == MpeInputMode.Mpe) {
      val outChannel = mpeInputChannelMap.getOrElse(inputChannel, inputChannel)
      buffer += makeMessage(outChannel).javaMidiMessage
    } else {
      // For non-MPE, forward to all occupied member channels that have notes from this input
      val outChannels = noteChannelMap.collect {
        case ((_, `inputChannel`), outCh) => outCh
      }.toSet
      outChannels.foreach { outCh =>
        buffer += makeMessage(outCh).javaMidiMessage
      }
    }
  }

  private def forwardOnMasterChannel(buffer: mutable.Buffer[MidiMessage], msg: ShortMessage): Unit = {
    if (lowerZone.isEnabled) {
      buffer += new ShortMessage(msg.getCommand, lowerZone.masterChannel, msg.getData1, msg.getData2)
    } else if (upperZone.isEnabled) {
      buffer += new ShortMessage(msg.getCommand, upperZone.masterChannel, msg.getData1, msg.getData2)
    }
  }

  private def computeOutputPitchBend(channel: Int, alloc: MpeChannelAllocator, zone: MpeZone,
                                     tuningOffsetCents: Double): Int = {
    val notes = alloc.activeNotes(channel)
    val avgExpressiveBend = if (notes.nonEmpty) {
      notes.map(_.expressivePitchBend).sum.toDouble / notes.size
    } else {
      0.0
    }

    val tuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(tuningOffsetCents,
      zone.memberPitchBendSensitivity)
    clampValue(tuningPitchBend + Math.round(avgExpressiveBend).toInt,
      ScPitchBendMidiMessage.MinValue, ScPitchBendMidiMessage.MaxValue)
  }

  private def updateTuningOnAllocator(alloc: MpeChannelAllocator, zone: MpeZone,
                                      buffer: mutable.Buffer[MidiMessage]): Unit = {
    zone.memberChannels.foreach { ch =>
      if (alloc.isChannelOccupied(ch)) {
        alloc.channelPitchClass(ch).foreach { pc =>
          val tuningOffset = _currTuning(pc)
          val totalPitchBend = computeOutputPitchBend(ch, alloc, zone, tuningOffset)
          buffer += ScPitchBendMidiMessage(ch, totalPitchBend).javaMidiMessage
        }
      }
    }
  }

  private def mcmMessages(zone: MpeZone): Seq[MidiMessage] = {
    // MCM: RPN 6 on master channel with data = memberCount
    Seq(
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.RpnLsb, 6),
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.RpnMsb, 0),
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.DataEntryMsb, zone.memberCount),
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.RpnLsb, Rpn.NullLsb),
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.RpnMsb, Rpn.NullMsb)
    ).map(_.javaMidiMessage)
  }

  private def pitchBendSensitivityMessages(zone: MpeZone): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()

    // Master channel PBS
    buffer ++= rpn0Messages(zone.masterChannel, zone.masterPitchBendSensitivity)

    // Member channel PBS
    zone.memberChannels.foreach { ch =>
      buffer ++= rpn0Messages(ch, zone.memberPitchBendSensitivity)
    }

    buffer.toSeq
  }

  private def rpn0Messages(channel: Int, pbs: PitchBendSensitivity): Seq[MidiMessage] = {
    Seq(
      ScCcMidiMessage(channel, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
      ScCcMidiMessage(channel, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
      ScCcMidiMessage(channel, ScCcMidiMessage.DataEntryMsb, pbs.semitones),
      ScCcMidiMessage(channel, ScCcMidiMessage.DataEntryLsb, pbs.cents),
      ScCcMidiMessage(channel, ScCcMidiMessage.RpnLsb, Rpn.NullLsb),
      ScCcMidiMessage(channel, ScCcMidiMessage.RpnMsb, Rpn.NullMsb)
    ).map(_.javaMidiMessage)
  }

  private def getAllocatorForInput(inputChannel: Int): Option[MpeChannelAllocator] = {
    // For non-MPE input, use the first enabled zone's allocator
    if (inputMode == MpeInputMode.NonMpe) {
      lowerAllocator.orElse(upperAllocator)
    } else {
      // For MPE input, determine zone based on input channel
      if (lowerZone.isEnabled && (lowerZone.memberChannels.contains(inputChannel) ||
        inputChannel == lowerZone.masterChannel)) {
        lowerAllocator
      } else if (upperZone.isEnabled && (upperZone.memberChannels.contains(inputChannel) ||
        inputChannel == upperZone.masterChannel)) {
        upperAllocator
      } else {
        lowerAllocator.orElse(upperAllocator)
      }
    }
  }

  private def getAllocatorForOutput(outputChannel: Int): Option[MpeChannelAllocator] = {
    if (lowerZone.isEnabled && lowerZone.memberChannels.contains(outputChannel)) {
      lowerAllocator
    } else if (upperZone.isEnabled && upperZone.memberChannels.contains(outputChannel)) {
      upperAllocator
    } else {
      None
    }
  }

  private def isMasterChannel(channel: Int): Boolean = {
    (lowerZone.isEnabled && channel == lowerZone.masterChannel) ||
      (upperZone.isEnabled && channel == upperZone.masterChannel)
  }
}

object MpeTuner {
  val TypeName: String = "mpe"
  val ExpressionPitchBendThreshold: Double = 50.0

  val DefaultZones: (MpeZone, MpeZone) = (
    MpeZone(MpeZoneType.Lower, 15),
    MpeZone(MpeZoneType.Upper, 0)
  )
}
