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

class SongSelectScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val song = 42
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.SONG_SELECT, song, 0)

  behavior of "SongSelectScMidiMessage"

  it should "create correct Java MIDI message" in {
    // Given
    val msg = SongSelectScMidiMessage(song)

    // When / Then
    msg.toJavaMidiMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    SongSelectScMidiMessage.fromJavaMessage(javaMessage) should equal(Some(SongSelectScMidiMessage(song)))
  }

  it should "be extracted from a valid Song Select message" in {
    // When / Then
    inside(javaMessage) {
      case SongSelectScMidiMessage(`song`) => succeed
    }
  }

  it should "return None for non-Song-Select messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    SongSelectScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid song numbers" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy SongSelectScMidiMessage(128)
    an[IllegalArgumentException] should be thrownBy SongSelectScMidiMessage(-1)
  }
}
