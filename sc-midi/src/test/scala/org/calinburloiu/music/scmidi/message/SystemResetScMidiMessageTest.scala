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

class SystemResetScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.SYSTEM_RESET)

  behavior of "SystemResetScMidiMessage"

  it should "create correct Java MIDI message" in {
    // When / Then
    SystemResetScMidiMessage.javaMessage.getStatus should equal(ShortMessage.SYSTEM_RESET)
    SystemResetScMidiMessage.javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    SystemResetScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(SystemResetScMidiMessage))
  }

  it should "be extracted from a valid System Reset message" in {
    // When / Then
    SystemResetScMidiMessage.unapply(javaMessage) shouldBe true
  }

  it should "return false from unapply for other messages" in {
    // Given
    val activeSensing: MidiMessage = new ShortMessage(ShortMessage.ACTIVE_SENSING)

    // When / Then
    SystemResetScMidiMessage.unapply(activeSensing) shouldBe false
  }
}
