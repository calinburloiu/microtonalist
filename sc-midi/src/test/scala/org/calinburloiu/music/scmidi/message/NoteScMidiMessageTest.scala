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
import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, ShortMessage}

class NoteScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 5
  private val noteNumber: Int = 62
  private val velocity = 102
  private val javaNoteOnMessage: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, channel, noteNumber, velocity)
  private val javaNoteOffMessage: MidiMessage = new ShortMessage(ShortMessage.NOTE_OFF, channel, noteNumber, velocity)

  behavior of "NoteOnScMidiMessage"

  it should "be extracted from a MidiMessage" in {
    // When / Then
    inside(javaNoteOnMessage) {
      case NoteOnScMidiMessage(`channel`, midiNote, `velocity`) => midiNote.number should equal(noteNumber)
    }
  }

  it should "be converted to a MidiMessage" in {
    // When / Then
    NoteOnScMidiMessage(channel, noteNumber, velocity).javaMessage.getMessage should equal(javaNoteOnMessage
      .getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    NoteOnScMidiMessage.fromJavaMessage(javaNoteOnMessage) should equal(Some(NoteOnScMidiMessage(channel, noteNumber,
      velocity)))
  }

  behavior of "NoteOffScMidiMessage"

  it should "be extracted from a MidiMessage" in {
    // When / Then
    inside(javaNoteOffMessage) {
      case NoteOffScMidiMessage(`channel`, midiNote, `velocity`) => midiNote.number should equal(noteNumber)
    }
  }

  it should "be converted to a MidiMessage" in {
    // When / Then
    NoteOffScMidiMessage(channel, noteNumber, velocity).javaMessage.getMessage should equal(javaNoteOffMessage
      .getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    NoteOffScMidiMessage.fromJavaMessage(javaNoteOffMessage) should equal(Some(NoteOffScMidiMessage(channel,
      noteNumber, velocity)))
  }
}
