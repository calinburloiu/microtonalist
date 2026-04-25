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

  // One sample per concrete ChannelScMidiMessage subtype, used by the tests below as the input
  // message whose channel is mapped. Each sample uses non-default field values so that a faulty
  // implementation that drops or resets non-channel fields would be caught.
  private val noteOnSample = NoteOnScMidiMessage(3, MidiNote(60), 96)
  private val noteOffSample = NoteOffScMidiMessage(3, MidiNote(60), 80)
  private val polyPressureSample = PolyPressureScMidiMessage(2, MidiNote(60), 100)
  private val ccSample = CcScMidiMessage(0, ScMidiCc.SustainPedal, 64)
  private val programChangeSample = ProgramChangeScMidiMessage(4, 42)
  private val channelPressureSample = ChannelPressureScMidiMessage(5, 110)
  private val pitchBendSample = PitchBendScMidiMessage(6, -2048)

  behavior of "ChannelScMidiMessage#mapChannel"

  it should "rewrite the channel of a NoteOnScMidiMessage" in {
    // When
    val mapped = noteOnSample.mapChannel(_ + 5)

    // Then
    mapped shouldBe NoteOnScMidiMessage(8, MidiNote(60), 96)
  }

  it should "rewrite the channel of a NoteOffScMidiMessage" in {
    // When
    val mapped = noteOffSample.mapChannel(_ => 10)

    // Then
    mapped shouldBe NoteOffScMidiMessage(10, MidiNote(60), 80)
  }

  it should "rewrite the channel of a PolyPressureScMidiMessage" in {
    // When
    val mapped = polyPressureSample.mapChannel(_ => 7)

    // Then
    mapped shouldBe PolyPressureScMidiMessage(7, MidiNote(60), 100)
  }

  it should "rewrite the channel of a CcScMidiMessage" in {
    // When
    val mapped = ccSample.mapChannel(_ => 9)

    // Then
    mapped shouldBe CcScMidiMessage(9, ScMidiCc.SustainPedal, 64)
  }

  it should "rewrite the channel of a ProgramChangeScMidiMessage" in {
    // When
    val mapped = programChangeSample.mapChannel(_ => 0)

    // Then
    mapped shouldBe ProgramChangeScMidiMessage(0, 42)
  }

  it should "rewrite the channel of a ChannelPressureScMidiMessage" in {
    // When
    val mapped = channelPressureSample.mapChannel(_ => 1)

    // Then
    mapped shouldBe ChannelPressureScMidiMessage(1, 110)
  }

  it should "rewrite the channel of a PitchBendScMidiMessage" in {
    // When
    val mapped = pitchBendSample.mapChannel(_ => 11)

    // Then
    mapped shouldBe PitchBendScMidiMessage(11, -2048)
  }

  it should "preserve the concrete subtype in the return type" in {
    // When
    val mappedNoteOn: NoteOnScMidiMessage = noteOnSample.mapChannel(_ => 1)
    val mappedNoteOff: NoteOffScMidiMessage = noteOffSample.mapChannel(_ => 1)
    val mappedPolyPressure: PolyPressureScMidiMessage = polyPressureSample.mapChannel(_ => 1)
    val mappedCc: CcScMidiMessage = ccSample.mapChannel(_ => 1)
    val mappedProgramChange: ProgramChangeScMidiMessage = programChangeSample.mapChannel(_ => 1)
    val mappedChannelPressure: ChannelPressureScMidiMessage = channelPressureSample.mapChannel(_ => 1)
    val mappedPitchBend: PitchBendScMidiMessage = pitchBendSample.mapChannel(_ => 1)

    // Then
    mappedNoteOn.channel shouldBe 1
    mappedNoteOff.channel shouldBe 1
    mappedPolyPressure.channel shouldBe 1
    mappedCc.channel shouldBe 1
    mappedProgramChange.channel shouldBe 1
    mappedChannelPressure.channel shouldBe 1
    mappedPitchBend.channel shouldBe 1
  }
}
