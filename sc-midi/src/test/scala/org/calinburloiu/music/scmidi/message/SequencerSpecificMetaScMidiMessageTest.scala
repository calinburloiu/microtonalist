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
import scala.collection.immutable.ArraySeq

class SequencerSpecificMetaScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val bytes: Array[Byte] = Array(0x41, 0x10, 0x42, 0x12).map(_.toByte)
  private val data: ArraySeq[Byte] = ArraySeq.unsafeWrapArray(bytes.clone())
  private val javaMessage: MidiMessage = {
    val m = new MetaMessage()
    m.setMessage(0x7F, bytes.clone(), bytes.length)
    m
  }

  behavior of "SequencerSpecificMetaScMidiMessage"

  it should "create correct Java MIDI message" in {
    // When / Then
    SequencerSpecificMetaScMidiMessage(data).toJavaMidiMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    SequencerSpecificMetaScMidiMessage.fromJavaMessage(javaMessage) should equal(
      Some(SequencerSpecificMetaScMidiMessage(data)))
  }

  it should "be extracted from a valid Sequencer-Specific meta event" in {
    // When / Then
    inside(javaMessage) {
      case SequencerSpecificMetaScMidiMessage(`data`) => succeed
    }
  }

  it should "return None for non-Sequencer-Specific messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    SequencerSpecificMetaScMidiMessage.unapply(noteOn) shouldBe None
  }
}
