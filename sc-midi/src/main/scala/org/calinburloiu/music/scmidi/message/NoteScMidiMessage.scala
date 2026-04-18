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
import org.calinburloiu.music.scmidi.message.NoteOnScMidiMessage.DefaultVelocity

import javax.sound.midi.ShortMessage

/**
 * Base class for Note On and Note Off MIDI messages, replacing Java's overloaded use of
 * [[javax.sound.midi.ShortMessage]] with typed `channel`, `midiNote`, and `velocity` parameters
 * that are validated on construction.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note.
 * @param velocity The velocity (0-127).
 */
abstract class NoteScMidiMessage(val channel: Int,
                                 val midiNote: MidiNote,
                                 val velocity: Int = DefaultVelocity) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  midiNote.assertValid()
  MidiRequirements.requireUnsigned7BitValue("velocity", velocity)

  override def javaMessage: ShortMessage = new ShortMessage(midiCommand, channel, midiNote.number, velocity)

  /** The MIDI command for this note message (e.g., [[ShortMessage.NOTE_ON]]). */
  protected val midiCommand: Int
}
