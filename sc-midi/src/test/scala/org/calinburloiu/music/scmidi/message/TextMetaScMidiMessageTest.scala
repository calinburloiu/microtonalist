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

class TextMetaScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val text = "Hello, MIDI!"
  private val bytes = text.getBytes("ISO-8859-1")
  private val javaMessage: MidiMessage = {
    val m = new MetaMessage()
    m.setMessage(0x01, bytes, bytes.length)
    m
  }

  behavior of "TextMetaScMidiMessage"

  it should "create correct Java MIDI message" in {
    TextMetaScMidiMessage(text).javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    TextMetaScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(TextMetaScMidiMessage(text)))
  }

  it should "be extracted from a valid Text meta event" in {
    inside(javaMessage) {
      case TextMetaScMidiMessage(`text`) => succeed
    }
  }

  it should "return None for non-Text messages" in {
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)
    TextMetaScMidiMessage.unapply(noteOn) shouldBe None
  }
}
