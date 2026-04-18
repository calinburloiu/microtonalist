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
 * Represents a MIDI Note On message.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note.
 * @param velocity The velocity (0-127).
 */
case class NoteOnScMidiMessage(override val channel: Int,
                               override val midiNote: MidiNote,
                               override val velocity: Int = NoteOnScMidiMessage.DefaultVelocity)
  extends NoteScMidiMessage(channel, midiNote, velocity) {
  override protected val midiCommand: Int = ShortMessage.NOTE_ON
}

/**
 * Companion object for [[NoteOnScMidiMessage]].
 */
object NoteOnScMidiMessage extends FromJavaMidiMessageConverter[NoteOnScMidiMessage] {
  /** The velocity value representing a Note Off via Note On (0). */
  val NoteOffVelocity: Int = 0x00
  /** The default velocity for Note On messages (64). */
  val DefaultVelocity: Int = 0x40

  /** Extracts the channel, MIDI note, and velocity from a [[MidiMessage]] if it is a Note On message. */
  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.NOTE_ON =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[NoteOnScMidiMessage] =
    unapply(message).map { tuple => NoteOnScMidiMessage.apply.tupled(tuple) }
}
