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

import javax.sound.midi.{MidiMessage, ShortMessage, SysexMessage}
import scala.collection.immutable.ArraySeq

class SysexScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val bytes: Array[Byte] = Array(0xF0, 0x7E, 0x7F, 0x09, 0x01, 0xF7).map(_.toByte)
  private val data: ArraySeq[Byte] = ArraySeq.unsafeWrapArray(bytes.clone())
  private val javaMessage: MidiMessage = new SysexMessage(bytes.clone(), bytes.length)

  behavior of "SysexScMidiMessage"

  it should "create correct Java MIDI message" in {
    // Given
    val msg = SysexScMidiMessage(data)

    // When / Then
    msg.toJavaMidiMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    SysexScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(SysexScMidiMessage(data)))
  }

  it should "be extracted from a valid SysEx message" in {
    // When / Then
    inside(javaMessage) {
      case SysexScMidiMessage(`data`) => succeed
    }
  }

  it should "return None for non-SysEx messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    SysexScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "have structural equality via ArraySeq" in {
    // Given
    val a = SysexScMidiMessage(ArraySeq.unsafeWrapArray(bytes.clone()))
    val b = SysexScMidiMessage(ArraySeq.unsafeWrapArray(bytes.clone()))

    // When / Then
    a should equal(b)
    a.hashCode should equal(b.hashCode)
  }
}
