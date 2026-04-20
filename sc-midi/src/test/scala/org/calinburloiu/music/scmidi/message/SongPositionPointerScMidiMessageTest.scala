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

class SongPositionPointerScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val position = 12345 // arbitrary, fits in 14 bits (max 16383)
  private val lsb = position & 0x7F
  private val msb = (position >> 7) & 0x7F
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.SONG_POSITION_POINTER, lsb, msb)

  behavior of "SongPositionPointerScMidiMessage"

  it should "create correct Java MIDI message" in {
    // Given
    val msg = SongPositionPointerScMidiMessage(position)

    // When / Then
    msg.toJavaMidiMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    SongPositionPointerScMidiMessage.fromJavaMessage(javaMessage) should equal(
      Some(SongPositionPointerScMidiMessage(position)))
  }

  it should "be extracted from a valid Song Position Pointer message" in {
    // When / Then
    inside(javaMessage) {
      case SongPositionPointerScMidiMessage(`position`) => succeed
    }
  }

  it should "round-trip boundary positions" in {
    // Given
    val minPos = 0
    val maxPos = 16383

    // When / Then
    val min = SongPositionPointerScMidiMessage(minPos)
    val max = SongPositionPointerScMidiMessage(maxPos)
    SongPositionPointerScMidiMessage.fromJavaMessage(min.toJavaMidiMessage) should equal(Some(min))
    SongPositionPointerScMidiMessage.fromJavaMessage(max.toJavaMidiMessage) should equal(Some(max))
  }

  it should "return None for non-Song-Position-Pointer messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    SongPositionPointerScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid positions" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy SongPositionPointerScMidiMessage(16384)
    an[IllegalArgumentException] should be thrownBy SongPositionPointerScMidiMessage(-1)
  }
}
