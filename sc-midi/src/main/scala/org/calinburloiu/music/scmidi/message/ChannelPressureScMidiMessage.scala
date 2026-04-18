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
 * Represents a MIDI Channel Pressure (Aftertouch) message with a validated `value` parameter.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param value   The pressure value (0-127).
 */
case class ChannelPressureScMidiMessage(channel: Int, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val javaMessage: ShortMessage = new ShortMessage(ShortMessage.CHANNEL_PRESSURE, channel, value, 0)
}

/**
 * Companion object for [[ChannelPressureScMidiMessage]].
 */
object ChannelPressureScMidiMessage extends FromJavaMidiMessageConverter[ChannelPressureScMidiMessage] {
  /** Extracts the channel and value from a [[MidiMessage]] if it is a Channel Pressure message. */
  def unapply(message: MidiMessage): Option[(Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CHANNEL_PRESSURE =>
      Some((shortMessage.getChannel, shortMessage.getData1))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ChannelPressureScMidiMessage] =
    unapply(message).map { tuple => ChannelPressureScMidiMessage.apply.tupled(tuple) }
}
