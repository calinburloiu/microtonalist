/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner.midi

import org.calinburloiu.music.microtuner.midi.ScNoteOnMidiMessage.DefaultVelocity

import javax.sound.midi.{MidiMessage, ShortMessage}

trait ScMidiMessage {
  def javaMidiMessage: MidiMessage
}

abstract class ScNoteMidiMessage(val channel: Int,
                                 val midiNote: MidiNote,
                                 val velocity: Int = DefaultVelocity) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  midiNote.assert()
  MidiRequirements.requireUnsigned7BitValue("velocity", velocity)

  override def javaMidiMessage: MidiMessage = new ShortMessage(midiCommand, channel, midiNote.number, velocity)

  protected val midiCommand: Int
}

case class ScNoteOnMidiMessage(override val channel: Int,
                               override val midiNote: MidiNote,
                               override val velocity: Int = DefaultVelocity)
  extends ScNoteMidiMessage(channel, midiNote, velocity) {
  override protected val midiCommand: Int = ShortMessage.NOTE_ON
}

object ScNoteOnMidiMessage {
  val NoteOffVelocity: Int = 0x00
  val DefaultVelocity: Int = 0x40

  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.NOTE_ON =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }
}

case class ScNoteOffMidiMessage(override val channel: Int,
                                override val midiNote: MidiNote,
                                override val velocity: Int = DefaultVelocity)
  extends ScNoteMidiMessage(channel, midiNote, velocity) {
  override protected val midiCommand: Int = ShortMessage.NOTE_OFF
}

object ScNoteOffMidiMessage {
  val DefaultVelocity: Int = 0x40

  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.NOTE_OFF =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }
}

case class ScPitchBendMidiMessage(channel: Int, value: Int) extends ScMidiMessage {
  import ScPitchBendMidiMessage._
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireSigned14BitValue("value", value)

  override lazy val javaMidiMessage: ShortMessage = {
    val (data1, data2) = convertValueToDataBytes(value)
    new ShortMessage(ShortMessage.PITCH_BEND, channel, data1, data2)
  }
}

object ScPitchBendMidiMessage {
  val MinValue: Int = MidiRequirements.MinSigned14BitValue
  val NoPitchBendValue: Int = 0
  val MaxValue: Int = MidiRequirements.MaxSigned14BitValue

  def unapply(message: MidiMessage): Option[(Int,Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.PITCH_BEND =>
      Some((shortMessage.getChannel, convertDataBytesToValue(shortMessage.getData1, shortMessage.getData2)))
    case _ => None
  }

  def fromCents(channel: Int,
                cents: Int,
                pitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default): ScPitchBendMidiMessage = {
    val value = convertCentsToValue(cents, pitchBendSensitivity)
    ScPitchBendMidiMessage(channel, value)
  }

  def convertDataBytesToValue(data1: Int, data2: Int): Int = {
    val lsb = data1
    val msb = data2
    ((msb << 7) | lsb) + MinValue
  }

  def convertValueToDataBytes(value: Int): (Int, Int) = {
    val unsignedValue = value - MinValue
    val lsb = unsignedValue & 0x7F
    val msb = (unsignedValue >> 7) & 0x7F
    (lsb, msb)
  }

  def convertCentsToValue(cents: Double, pitchBendSensitivity: PitchBendSensitivity): Int = {
    require(cents >= -pitchBendSensitivity.totalCents && cents <= pitchBendSensitivity.totalCents,
      "cents should not exceed the limits set by pitchBendSensitivity")

    if (cents == 0) {
      0
    } else {
      val r = Math.abs(cents / pitchBendSensitivity.totalCents)
      if (cents < 0) {
        Math.round(r * MinValue).toInt
      } else {
        Math.round(r * MaxValue).toInt
      }
    }
  }

  def convertValueToCents(value: Int, pitchBendSensitivity: PitchBendSensitivity): Double = {
    MidiRequirements.requireSigned14BitValue("value", value)

    if (value == 0) {
      0
    } else if (value < 0) {
      -Math.abs(value.toDouble / MinValue) * pitchBendSensitivity.totalCents
    } else {  // if (value > 0)
      Math.abs(value.toDouble / MaxValue) * pitchBendSensitivity.totalCents
    }
  }
}

case class ScCcMidiMessage(channel: Int, number: Int, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("number", number)
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val javaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number, value)
}

object ScCcMidiMessage {
  val RpnMsb: Int = 101
  val RpnLsb: Int = 100
  val DataEntryMsb: Int = 6
  val DataEntryLsb: Int = 38
  val DataIncrement: Int = 96
  val DataDecrement: Int = 97
  val AllSoundOff: Int = 120
  val ResetAllControllers: Int = 121
  val AllNotesOff: Int = 123

  val Modulation: Int = 1
  val SustainPedal: Int = 64
  val SostenutoPedal: Int = 66
  val SoftPedal: Int = 67

  def unapply(message: MidiMessage): Option[(Int, Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CONTROL_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }
}

object Rpn {
  val PitchBendSensitivityMsb: Int = 0x00
  val PitchBendSensitivityLsb: Int = 0x00

  val CoarseTuningMsb: Int = 0x00
  val CoarseTuningLsb: Int = 0x02

  val FineTuningMsb: Int = 0x00
  val FineTuningLsb: Int = 0x01

  val TuningBankSelectMsb: Int = 0x00
  val TuningBankSelectLsb: Int = 0x04

  val TuningProgramSelectMsb: Int = 0x00
  val TuningProgramSelectLsb: Int = 0x03

  val NullMsb: Int = 0x7F
  val NullLsb: Int = 0x7F
}

object MidiRequirements {
  val MinSigned14BitValue: Int = -(1 << 13)
  val MaxSigned14BitValue: Int = (1 << 13) - 1

  def requireChannel(channel: Int): Unit =
    require((channel & 0xFFFFFFF0) == 0, s"channel must be between 0 and 15; got $channel")

  def requireUnsigned7BitValue(name: String, value: Int): Unit =
    require((value & 0xFFFFFF80) == 0, s"$name must be between 0 and 127; got $value")

  def requireSigned14BitValue(name: String, value: Int): Unit = {
    require(
      value >= MinSigned14BitValue && value <= MaxSigned14BitValue,
      s"$name must be between $MinSigned14BitValue and $MaxSigned14BitValue; got $value"
    )
  }
}
