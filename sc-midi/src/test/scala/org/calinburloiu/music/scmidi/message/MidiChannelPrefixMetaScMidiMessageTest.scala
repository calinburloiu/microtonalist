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

class MidiChannelPrefixMetaScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 7
  private val javaMessage: MidiMessage = {
    val m = new MetaMessage()
    m.setMessage(0x20, Array(channel.toByte), 1)
    m
  }

  behavior of "MidiChannelPrefixMetaScMidiMessage"

  it should "create correct Java MIDI message" in {
    MidiChannelPrefixMetaScMidiMessage(channel).javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    MidiChannelPrefixMetaScMidiMessage.fromJavaMessage(javaMessage) should equal(
      Some(MidiChannelPrefixMetaScMidiMessage(channel)))
  }

  it should "be extracted from a valid MIDI Channel Prefix meta event" in {
    inside(javaMessage) {
      case MidiChannelPrefixMetaScMidiMessage(`channel`) => succeed
    }
  }

  it should "return None for non-MIDI-Channel-Prefix messages" in {
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)
    MidiChannelPrefixMetaScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid channels" in {
    an[IllegalArgumentException] should be thrownBy MidiChannelPrefixMetaScMidiMessage(16)
    an[IllegalArgumentException] should be thrownBy MidiChannelPrefixMetaScMidiMessage(-1)
  }
}
