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

import javax.sound.midi.{MetaMessage, MidiMessage, ShortMessage}

class SmpteOffsetMetaScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val hour = 1
  private val minute = 30
  private val second = 45
  private val frame = 10
  private val fractionalFrame = 50
  private val javaMessage: MidiMessage = {
    val m = new MetaMessage()
    m.setMessage(0x54, Array(hour.toByte, minute.toByte, second.toByte, frame.toByte, fractionalFrame.toByte), 5)
    m
  }

  behavior of "SmpteOffsetMetaScMidiMessage"

  it should "create correct Java MIDI message" in {
    // When / Then
    SmpteOffsetMetaScMidiMessage(hour, minute, second, frame, fractionalFrame).javaMessage.getMessage should equal(
      javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    SmpteOffsetMetaScMidiMessage.fromJavaMessage(javaMessage) should equal(
      Some(SmpteOffsetMetaScMidiMessage(hour, minute, second, frame, fractionalFrame)))
  }

  it should "be extracted from a valid SMPTE Offset meta event" in {
    // When / Then
    inside(javaMessage) {
      case SmpteOffsetMetaScMidiMessage(`hour`, `minute`, `second`, `frame`, `fractionalFrame`) => succeed
    }
  }

  it should "return None for non-SMPTE-Offset messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    SmpteOffsetMetaScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid field values" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy SmpteOffsetMetaScMidiMessage(256, 0, 0, 0, 0)
    an[IllegalArgumentException] should be thrownBy SmpteOffsetMetaScMidiMessage(0, -1, 0, 0, 0)
  }
}
