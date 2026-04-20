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

class ProgramChangeScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 2
  private val program = 42
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0)

  behavior of "ProgramChangeScMidiMessage"

  it should "create correct Java MIDI message" in {
    // Given
    val msg = ProgramChangeScMidiMessage(channel, program)

    // When / Then
    msg.toJavaMidiMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    ProgramChangeScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(ProgramChangeScMidiMessage(channel,
      program)))
  }

  it should "be extracted from a valid Program Change message" in {
    // When / Then
    inside(javaMessage) {
      case ProgramChangeScMidiMessage(`channel`, `program`) => succeed
    }
  }

  it should "return None for non-Program-Change messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, channel, 60, 100)

    // When / Then
    ProgramChangeScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid channel" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(16, program)
  }

  it should "reject invalid program" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(channel, 128)
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(channel, -1)
  }
}
