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
 * Represents a MIDI Program Change message with a validated `program` parameter.
 *
 * Unlike Java's [[javax.sound.midi.ShortMessage]], which uses a generic `data1` byte for the program number,
 * this class provides a descriptively named `program` field.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param program The program number (0-127).
 */
case class ProgramChangeScMidiMessage(channel: Int, program: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("program", program)

  override lazy val javaMessage: ShortMessage =
    new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0)
}

/**
 * Companion object for [[ProgramChangeScMidiMessage]].
 */
object ProgramChangeScMidiMessage extends FromJavaMidiMessageConverter[ProgramChangeScMidiMessage] {
  /** Extracts the channel and program number from a [[MidiMessage]] if it is a Program Change message. */
  def unapply(message: MidiMessage): Option[(Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.PROGRAM_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ProgramChangeScMidiMessage] =
    unapply(message).map { tuple => ProgramChangeScMidiMessage.apply.tupled(tuple) }
}
