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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.ShortMessage

class ScMidiMessageTest extends AnyFlatSpec with Matchers {
  behavior of "ScMidiMessage"

  it should "create correct ScMidiMessage from Java message" in {
    // Given
    val channel = 1
    val noteNumber = 60
    val velocity = 100
    val ccNumber = 7
    val ccValue = 120
    val pressureValue = 80
    val pitchBendValue = 0

    val noteOn = new ShortMessage(ShortMessage.NOTE_ON, channel, noteNumber, velocity)
    val noteOff = new ShortMessage(ShortMessage.NOTE_OFF, channel, noteNumber, velocity)
    val cc = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, ccNumber, ccValue)
    val pitchBend = new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x40)
    val channelPressure = new ShortMessage(ShortMessage.CHANNEL_PRESSURE, channel, pressureValue, 0)
    val polyPressure = new ShortMessage(ShortMessage.POLY_PRESSURE, channel, noteNumber, pressureValue)
    val programChange = new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, 1, 0)

    // When / Then
    ScMidiMessage.fromJavaMessage(noteOn) should equal(NoteOnScMidiMessage(channel, noteNumber, velocity))
    ScMidiMessage.fromJavaMessage(noteOff) should equal(NoteOffScMidiMessage(channel, noteNumber, velocity))
    ScMidiMessage.fromJavaMessage(cc) should equal(CcScMidiMessage(channel, ccNumber, ccValue))
    ScMidiMessage.fromJavaMessage(pitchBend) should equal(PitchBendScMidiMessage(channel, pitchBendValue))
    ScMidiMessage.fromJavaMessage(channelPressure) should equal(ChannelPressureScMidiMessage(channel, pressureValue))
    ScMidiMessage.fromJavaMessage(polyPressure) should equal(PolyPressureScMidiMessage(channel, noteNumber,
      pressureValue))
    ScMidiMessage.fromJavaMessage(programChange) should equal(ProgramChangeScMidiMessage(channel, 1))
  }

  it should "throw IllegalArgumentException for null input" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy ScMidiMessage.fromJavaMessage(null)
  }
}
