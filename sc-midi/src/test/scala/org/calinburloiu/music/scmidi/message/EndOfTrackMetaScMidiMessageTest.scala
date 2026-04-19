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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MetaMessage, MidiMessage, ShortMessage}

class EndOfTrackMetaScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val javaMessage: MidiMessage = {
    val m = new MetaMessage()
    m.setMessage(0x2F, Array.emptyByteArray, 0)
    m
  }

  behavior of "EndOfTrackMetaScMidiMessage"

  it should "create correct Java MIDI message" in {
    // When / Then
    EndOfTrackMetaScMidiMessage.javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    EndOfTrackMetaScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(EndOfTrackMetaScMidiMessage))
  }

  it should "be extracted from a valid End Of Track meta event" in {
    // When / Then
    EndOfTrackMetaScMidiMessage.unapply(javaMessage) shouldBe true
  }

  it should "return false from unapply for other messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    EndOfTrackMetaScMidiMessage.unapply(noteOn) shouldBe false
  }
}
