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
  MidiRequirements.requireUnsigned7BitValue("noteNumber", midiNote.number)
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
  val MaxValue: Int = MidiRequirements.MaxSigned14BitValue

  def unapply(message: MidiMessage): Option[(Int,Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.PITCH_BEND =>
      Some((shortMessage.getChannel, convertDataBytesToValue(shortMessage.getData1, shortMessage.getData2)))
    case _ => None
  }

  def convertDataBytesToValue(data1: Int, data2: Int): Int = {
    val lsb = data1
    val msb = data2
    ((msb << 7) | lsb) + MinValue
  }

  def convertValueToDataBytes(value: Int): (Int, Int) = {
    val unsignedValue = value + MinValue
    val lsb = unsignedValue & 0x7F
    val msb = (unsignedValue >> 7) & 0x7F
    (lsb, msb)
  }

  def convertCentsToValue(cents: Double, pitchBendSensitivity: PitchBendSensitivity): Int = {
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
