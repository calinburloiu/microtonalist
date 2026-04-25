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

import org.calinburloiu.music.scmidi.MidiNote
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelScMidiMessageMapChannelTest extends AnyFlatSpec with Matchers {

  behavior of "ChannelScMidiMessage#mapChannel"

  it should "rewrite the channel of a NoteOnScMidiMessage" in {
    // Given
    val original = NoteOnScMidiMessage(3, MidiNote(60), 96)

    // When
    val mapped = original.mapChannel(_ + 5)

    // Then
    mapped shouldBe NoteOnScMidiMessage(8, MidiNote(60), 96)
  }

  it should "rewrite the channel of a NoteOffScMidiMessage" in {
    // Given
    val original = NoteOffScMidiMessage(3, MidiNote(60), 80)

    // When
    val mapped = original.mapChannel(_ => 10)

    // Then
    mapped shouldBe NoteOffScMidiMessage(10, MidiNote(60), 80)
  }

  it should "rewrite the channel of a PolyPressureScMidiMessage" in {
    // Given
    val original = PolyPressureScMidiMessage(2, MidiNote(60), 100)

    // When
    val mapped = original.mapChannel(_ => 7)

    // Then
    mapped shouldBe PolyPressureScMidiMessage(7, MidiNote(60), 100)
  }

  it should "rewrite the channel of a CcScMidiMessage" in {
    // Given
    val original = CcScMidiMessage(0, ScMidiCc.SustainPedal, 64)

    // When
    val mapped = original.mapChannel(_ => 9)

    // Then
    mapped shouldBe CcScMidiMessage(9, ScMidiCc.SustainPedal, 64)
  }

  it should "rewrite the channel of a ProgramChangeScMidiMessage" in {
    // Given
    val original = ProgramChangeScMidiMessage(4, 42)

    // When
    val mapped = original.mapChannel(_ => 0)

    // Then
    mapped shouldBe ProgramChangeScMidiMessage(0, 42)
  }

  it should "rewrite the channel of a ChannelPressureScMidiMessage" in {
    // Given
    val original = ChannelPressureScMidiMessage(5, 110)

    // When
    val mapped = original.mapChannel(_ => 1)

    // Then
    mapped shouldBe ChannelPressureScMidiMessage(1, 110)
  }

  it should "rewrite the channel of a PitchBendScMidiMessage" in {
    // Given
    val original = PitchBendScMidiMessage(6, -2048)

    // When
    val mapped = original.mapChannel(_ => 11)

    // Then
    mapped shouldBe PitchBendScMidiMessage(11, -2048)
  }

  it should "preserve the concrete subtype in the return type" in {
    // Given
    val noteOn = NoteOnScMidiMessage(0, MidiNote(60))
    val noteOff = NoteOffScMidiMessage(0, MidiNote(60))
    val polyPressure = PolyPressureScMidiMessage(0, MidiNote(60), 80)
    val cc = CcScMidiMessage(0, ScMidiCc.ModulationMsb, 32)
    val programChange = ProgramChangeScMidiMessage(0, 5)
    val channelPressure = ChannelPressureScMidiMessage(0, 64)
    val pitchBend = PitchBendScMidiMessage(0, 0)

    // When / Then
    val mappedNoteOn: NoteOnScMidiMessage = noteOn.mapChannel(_ => 1)
    mappedNoteOn.channel shouldBe 1

    val mappedNoteOff: NoteOffScMidiMessage = noteOff.mapChannel(_ => 1)
    mappedNoteOff.channel shouldBe 1

    val mappedPolyPressure: PolyPressureScMidiMessage = polyPressure.mapChannel(_ => 1)
    mappedPolyPressure.channel shouldBe 1

    val mappedCc: CcScMidiMessage = cc.mapChannel(_ => 1)
    mappedCc.channel shouldBe 1

    val mappedProgramChange: ProgramChangeScMidiMessage = programChange.mapChannel(_ => 1)
    mappedProgramChange.channel shouldBe 1

    val mappedChannelPressure: ChannelPressureScMidiMessage = channelPressure.mapChannel(_ => 1)
    mappedChannelPressure.channel shouldBe 1

    val mappedPitchBend: PitchBendScMidiMessage = pitchBend.mapChannel(_ => 1)
    mappedPitchBend.channel shouldBe 1
  }
}
