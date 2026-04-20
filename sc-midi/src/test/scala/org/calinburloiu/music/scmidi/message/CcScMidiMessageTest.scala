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

class CcScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 15
  private val number = 67
  private val ccValue = 64
  private val javaMidiMessage: MidiMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number, ccValue)

  behavior of "CcScMidiMessage"

  it should "be extracted from a MidiMessage" in {
    // When / Then
    inside(javaMidiMessage) { case CcScMidiMessage(`channel`, `number`, `ccValue`) => }
  }

  it should "be converted to a MidiMessage" in {
    // When / Then
    CcScMidiMessage(channel, number, ccValue).toJavaMidiMessage.getMessage should equal(javaMidiMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    CcScMidiMessage.fromJavaMessage(javaMidiMessage) should equal(Some(CcScMidiMessage(channel, number, ccValue)))
  }
}
