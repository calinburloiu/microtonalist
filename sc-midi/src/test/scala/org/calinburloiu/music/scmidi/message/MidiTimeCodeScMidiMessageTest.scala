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

import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, ShortMessage}

class MidiTimeCodeScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val messageType = 3 // 0..7
  private val values = 10 // 0..15
  private val data1 = (messageType << 4) | values
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.MIDI_TIME_CODE, data1, 0)

  behavior of "MidiTimeCodeScMidiMessage"

  it should "create correct Java MIDI message" in {
    // Given
    val msg = MidiTimeCodeScMidiMessage(messageType, values)

    // When / Then
    msg.toJavaMidiMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    MidiTimeCodeScMidiMessage.fromJavaMessage(javaMessage) should equal(
      Some(MidiTimeCodeScMidiMessage(messageType, values)))
  }

  it should "be extracted from a valid MIDI Time Code message" in {
    // When / Then
    inside(javaMessage) {
      case MidiTimeCodeScMidiMessage(`messageType`, `values`) => succeed
    }
  }

  it should "return None for non-MIDI-Time-Code messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    MidiTimeCodeScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid messageType" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeScMidiMessage(8, values)
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeScMidiMessage(-1, values)
  }

  it should "reject invalid values" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeScMidiMessage(messageType, 16)
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeScMidiMessage(messageType, -1)
  }
}
