package org.calinburloiu.music.microtuner.midi

import org.calinburloiu.music.microtuner.midi.ScPitchBendMidiMessage.convertValueToDataBytes

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.Function.unlift

trait ScMidiMessage {
  def javaMidiMessage: MidiMessage
}

case class ScPitchBendMidiMessage(channel: Int, value: Int) extends ScMidiMessage {
  import ScPitchBendMidiMessage._
  require((channel & 0xFFFFFFF0) == 0, s"channel must be between 0 and 15; got $channel")
  require(value >= MinValue && value <= MaxValue, s"value must be between $MinValue and $MaxValue; got $value")

  override lazy val javaMidiMessage: ShortMessage = {
    val (data1, data2) = convertValueToDataBytes(value)
    new ShortMessage(ShortMessage.PITCH_BEND, channel, data1, data2)
  }
}

object ScPitchBendMidiMessage {
  val MinValue: Int = -(1 << 13)
  val MaxValue: Int = (1 << 13) - 1

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
}

case class ScCcMidiMessage(channel: Int, number: Int, value: Int) extends ScMidiMessage {
  require((channel & 0xFFFFFFF0) == 0, s"channel must be between 0 and 15; got $channel")
  require((number & 0xFFFFFF80) == 0, s"number must be between 0 and 127; got $number")
  require((value & 0xFFFFFF80) == 0, s"number must be between 0 and 127; got $value")

  override lazy val javaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number, value)
}

object ScCcMidiMessage {
  def unapply(message: MidiMessage): Option[(Int, Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CONTROL_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }
}
