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

import org.calinburloiu.music.scmidi.MidiNote

import javax.sound.midi.{MidiMessage, ShortMessage}

/**
 * Represents a MIDI Polyphonic Key Pressure (Poly Aftertouch) message with typed `midiNote` and validated `value`
 * parameters.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note to which the pressure applies.
 * @param value    The pressure value (0-127).
 */
case class PolyPressureScMidiMessage(channel: Int, midiNote: MidiNote, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  midiNote.assertValid()
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val javaMessage: ShortMessage =
    new ShortMessage(ShortMessage.POLY_PRESSURE, channel, midiNote.number, value)
}

/**
 * Companion object for [[PolyPressureScMidiMessage]].
 */
object PolyPressureScMidiMessage extends FromJavaMidiMessageConverter[PolyPressureScMidiMessage] {
  /** Extracts the channel, MIDI note, and value from a [[MidiMessage]] if it is a Poly Pressure message. */
  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.POLY_PRESSURE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[PolyPressureScMidiMessage] =
    unapply(message).map { tuple => PolyPressureScMidiMessage.apply.tupled(tuple) }
}
