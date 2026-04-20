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

class TimeSignatureMetaScMidiMessageTest extends AnyFlatSpec with Matchers {
  private val numerator = 6
  private val denominatorPowerOf2 = 3 // denominator = 8
  private val midiClocksPerMetronomeTick = 24
  private val thirtySecondNotesPer24MidiClocks = 8
  private val javaMessage: MidiMessage = {
    val m = new MetaMessage()
    m.setMessage(0x58,
      Array(numerator.toByte, denominatorPowerOf2.toByte,
        midiClocksPerMetronomeTick.toByte, thirtySecondNotesPer24MidiClocks.toByte),
      4)
    m
  }

  behavior of "TimeSignatureMetaScMidiMessage"

  it should "create correct Java MIDI message" in {
    // When / Then
    TimeSignatureMetaScMidiMessage(numerator, denominatorPowerOf2,
      midiClocksPerMetronomeTick, thirtySecondNotesPer24MidiClocks)
      .toJavaMidiMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    TimeSignatureMetaScMidiMessage.fromJavaMessage(javaMessage) should equal(
      Some(TimeSignatureMetaScMidiMessage(numerator, denominatorPowerOf2,
        midiClocksPerMetronomeTick, thirtySecondNotesPer24MidiClocks)))
  }

  it should "be extracted from a valid Time Signature meta event" in {
    // When / Then
    inside(javaMessage) {
      case TimeSignatureMetaScMidiMessage(`numerator`, `denominatorPowerOf2`,
        `midiClocksPerMetronomeTick`, `thirtySecondNotesPer24MidiClocks`) => succeed
    }
  }

  it should "return None for non-Time-Signature messages" in {
    // Given
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When / Then
    TimeSignatureMetaScMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid field values" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy TimeSignatureMetaScMidiMessage(256, 0, 0, 0)
    an[IllegalArgumentException] should be thrownBy TimeSignatureMetaScMidiMessage(0, -1, 0, 0)
  }
}
