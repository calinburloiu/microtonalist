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

class KeySignatureMetaScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val sharpsOrFlats = -3
  private val mode = KeySignatureMode.Minor
  private val javaMessage: MidiMessage = {
    val m = new MetaMessage()
    m.setMessage(0x59, Array(sharpsOrFlats.toByte, 1.toByte), 2)
    m
  }

  behavior of "KeySignatureMetaScMidiMessage"

  it should "create correct Java MIDI message" in {
    // When / Then
    KeySignatureMetaScMidiMessage(sharpsOrFlats, mode).javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    KeySignatureMetaScMidiMessage.fromJavaMessage(javaMessage) should equal(
      Some(KeySignatureMetaScMidiMessage(sharpsOrFlats, mode)))
  }

  it should "be extracted from a valid Key Signature meta event" in {
    // When / Then
    inside(javaMessage) {
      case KeySignatureMetaScMidiMessage(`sharpsOrFlats`, `mode`) => succeed
    }
  }

  it should "round-trip a major key with sharps" in {
    // Given
    val msg = KeySignatureMetaScMidiMessage(4, KeySignatureMode.Major)

    // When / Then
    KeySignatureMetaScMidiMessage.fromJavaMessage(msg.javaMessage) should equal(Some(msg))
  }

  it should "return None for non-Key-Signature messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    KeySignatureMetaScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid sharpsOrFlats" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy KeySignatureMetaScMidiMessage(8, KeySignatureMode.Major)
    an[IllegalArgumentException] should be thrownBy KeySignatureMetaScMidiMessage(-8, KeySignatureMode.Major)
  }
}
