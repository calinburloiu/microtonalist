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

class ChannelPressureScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 3
  private val pressure = 100
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.CHANNEL_PRESSURE, channel, pressure, 0)

  behavior of "ChannelPressureScMidiMessage"

  it should "create correct Java MIDI message" in {
    // Given
    val msg = ChannelPressureScMidiMessage(channel, pressure)

    // When / Then
    msg.javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    ChannelPressureScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(ChannelPressureScMidiMessage(channel,
      pressure)))
  }

  it should "be extracted from a valid Channel Pressure message" in {
    // When / Then
    inside(javaMessage) {
      case ChannelPressureScMidiMessage(`channel`, `pressure`) => succeed
    }
  }

  it should "return None for non-Channel-Pressure messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, channel, 60, 100)

    // When / Then
    ChannelPressureScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid channel" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(16, pressure)
  }

  it should "reject invalid value" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(channel, 128)
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(channel, -1)
  }
}
