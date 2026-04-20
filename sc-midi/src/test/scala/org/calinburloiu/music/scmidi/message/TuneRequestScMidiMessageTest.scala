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

import javax.sound.midi.{MidiMessage, ShortMessage}

class TuneRequestScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.TUNE_REQUEST)

  behavior of "TuneRequestScMidiMessage"

  it should "create correct Java MIDI message" in {
    // When / Then
    TuneRequestScMidiMessage.asJava.getStatus should equal(ShortMessage.TUNE_REQUEST)
    TuneRequestScMidiMessage.asJava.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    TuneRequestScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(TuneRequestScMidiMessage))
  }

  it should "be extracted from a valid Tune Request message" in {
    // When / Then
    TuneRequestScMidiMessage.unapply(javaMessage) shouldBe true
  }

  it should "return false from unapply for non-Tune-Request messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    TuneRequestScMidiMessage.unapply(noteOn) shouldBe false
  }
}
