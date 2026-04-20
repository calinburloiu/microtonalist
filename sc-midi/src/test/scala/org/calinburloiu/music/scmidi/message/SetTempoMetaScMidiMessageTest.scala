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

class SetTempoMetaScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val tempo = 500000 // 120 BPM
  private val javaMessage: MidiMessage = {
    val m = new MetaMessage()
    m.setMessage(0x51,
      Array(((tempo >> 16) & 0xFF).toByte, ((tempo >> 8) & 0xFF).toByte, (tempo & 0xFF).toByte),
      3)
    m
  }

  behavior of "SetTempoMetaScMidiMessage"

  it should "create correct Java MIDI message" in {
    // When / Then
    SetTempoMetaScMidiMessage(tempo).toJavaMidiMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    SetTempoMetaScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(SetTempoMetaScMidiMessage(tempo)))
  }

  it should "be extracted from a valid Set Tempo meta event" in {
    // When / Then
    inside(javaMessage) {
      case SetTempoMetaScMidiMessage(`tempo`) => succeed
    }
  }

  it should "return None for non-Set-Tempo messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    SetTempoMetaScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid tempo values" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy SetTempoMetaScMidiMessage(1 << 24)
    an[IllegalArgumentException] should be thrownBy SetTempoMetaScMidiMessage(-1)
  }
}
