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

package org.calinburloiu.music.scmidi.message

import javax.sound.midi.{MidiMessage, ShortMessage}

/**
 * Represents a MIDI Control Change (CC) message with named, validated `number` and `value` parameters.
 *
 * The companion object provides constants for commonly used controller numbers (e.g. [[CcScMidiMessage.SustainPedal]],
 * [[CcScMidiMessage.RpnMsb]]), avoiding the magic numbers typical of raw Java MIDI code.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param number  The controller number (0-127).
 * @param value   The controller value (0-127).
 */
case class CcScMidiMessage(channel: Int, number: Int, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("number", number)
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val javaMessage: ShortMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number, value)
}

/**
 * Companion object for [[CcScMidiMessage]].
 */
object CcScMidiMessage extends FromJavaMidiMessageConverter[CcScMidiMessage] {
  /** Registered Parameter Number (RPN) MSB controller number (#101). */
  val RpnMsb: Int = 101
  /** Registered Parameter Number (RPN) LSB controller number (#100). */
  val RpnLsb: Int = 100
  /** Data Entry MSB controller number (#6). */
  val DataEntryMsb: Int = 6
  /** Data Entry LSB controller number (#38). */
  val DataEntryLsb: Int = 38
  /** Data Increment controller number (#96). */
  val DataIncrement: Int = 96
  /** Data Decrement controller number (#97). */
  val DataDecrement: Int = 97
  /** All Sound Off controller number (#120). */
  val AllSoundOff: Int = 120
  /** Reset All Controllers controller number (#121). */
  val ResetAllControllers: Int = 121
  /** All Notes Off controller number (#123). */
  val AllNotesOff: Int = 123

  /** Bank Select MSB controller number (#0). */
  val BankSelectMsb: Int = 0
  /** Bank Select LSB controller number (#32). */
  val BankSelectLsb: Int = 32

  /** Modulation Wheel controller number (#1). */
  val Modulation: Int = 1
  /** Sustain Pedal (Damper) controller number (#64). */
  val SustainPedal: Int = 64
  /** Sostenuto Pedal controller number (#66). */
  val SostenutoPedal: Int = 66
  /** Soft Pedal controller number (#67). */
  val SoftPedal: Int = 67

  /**
   * Represents the MPE (MIDI Polyphonic Expression) Slide controller number (#74), also known as Timbre or Brightness.
   */
  val MpeSlide: Int = 74

  /** Extracts the channel, controller number, and value from a [[MidiMessage]] if it is a Control Change message. */
  def unapply(message: MidiMessage): Option[(Int, Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CONTROL_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[CcScMidiMessage] =
    unapply(message).map { tuple => CcScMidiMessage.apply.tupled(tuple) }
}
