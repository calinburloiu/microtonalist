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
 * CC number constants are available in the [[ScMidiCc]] object.
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
  /** Extracts the channel, controller number, and value from a [[MidiMessage]] if it is a Control Change message. */
  def unapply(message: MidiMessage): Option[(Int, Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CONTROL_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[CcScMidiMessage] =
    unapply(message).map { tuple => CcScMidiMessage.apply.tupled(tuple) }
}
