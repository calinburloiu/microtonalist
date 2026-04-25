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

class ScMidiMessageTest extends AnyFlatSpec with Matchers {

  behavior of "NoteOnScMidiMessage"

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = NoteOnScMidiMessage(3, MidiNote(60), 96)

    // When
    val mapped = original.mapChannel(_ + 5)

    // Then
    mapped shouldBe NoteOnScMidiMessage(8, MidiNote(60), 96)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val noteOn = NoteOnScMidiMessage(0, MidiNote(60))

    // When
    val mapped: NoteOnScMidiMessage = noteOn.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "NoteOffScMidiMessage"

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = NoteOffScMidiMessage(3, MidiNote(60), 80)

    // When
    val mapped = original.mapChannel(_ => 10)

    // Then
    mapped shouldBe NoteOffScMidiMessage(10, MidiNote(60), 80)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val noteOff = NoteOffScMidiMessage(0, MidiNote(60))

    // When
    val mapped: NoteOffScMidiMessage = noteOff.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "PolyPressureScMidiMessage"

  it should "reject invalid channel and value" in {
    an[IllegalArgumentException] should be thrownBy PolyPressureScMidiMessage(16, MidiNote(60), 80)
    an[IllegalArgumentException] should be thrownBy PolyPressureScMidiMessage(0, MidiNote(60), 128)
    an[IllegalArgumentException] should be thrownBy PolyPressureScMidiMessage(0, MidiNote(60), -1)
  }

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = PolyPressureScMidiMessage(2, MidiNote(60), 100)

    // When
    val mapped = original.mapChannel(_ => 7)

    // Then
    mapped shouldBe PolyPressureScMidiMessage(7, MidiNote(60), 100)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val polyPressure = PolyPressureScMidiMessage(0, MidiNote(60), 80)

    // When
    val mapped: PolyPressureScMidiMessage = polyPressure.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "CcScMidiMessage"

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = CcScMidiMessage(0, ScMidiCc.SustainPedal, 64)

    // When
    val mapped = original.mapChannel(_ => 9)

    // Then
    mapped shouldBe CcScMidiMessage(9, ScMidiCc.SustainPedal, 64)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val cc = CcScMidiMessage(0, ScMidiCc.ModulationMsb, 32)

    // When
    val mapped: CcScMidiMessage = cc.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "ProgramChangeScMidiMessage"

  it should "reject invalid channel and program" in {
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(16, 0)
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(0, 128)
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(0, -1)
  }

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = ProgramChangeScMidiMessage(4, 42)

    // When
    val mapped = original.mapChannel(_ => 0)

    // Then
    mapped shouldBe ProgramChangeScMidiMessage(0, 42)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val programChange = ProgramChangeScMidiMessage(0, 5)

    // When
    val mapped: ProgramChangeScMidiMessage = programChange.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "ChannelPressureScMidiMessage"

  it should "reject invalid channel and value" in {
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(16, 0)
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(0, 128)
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(0, -1)
  }

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = ChannelPressureScMidiMessage(5, 110)

    // When
    val mapped = original.mapChannel(_ => 1)

    // Then
    mapped shouldBe ChannelPressureScMidiMessage(1, 110)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val channelPressure = ChannelPressureScMidiMessage(0, 64)

    // When
    val mapped: ChannelPressureScMidiMessage = channelPressure.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "PitchBendScMidiMessage"

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = PitchBendScMidiMessage(6, -2048)

    // When
    val mapped = original.mapChannel(_ => 11)

    // Then
    mapped shouldBe PitchBendScMidiMessage(11, -2048)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val pitchBend = PitchBendScMidiMessage(0, 0)

    // When
    val mapped: PitchBendScMidiMessage = pitchBend.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "MidiTimeCodeScMidiMessage"

  it should "reject invalid messageType and values" in {
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeScMidiMessage(8, 0)
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeScMidiMessage(-1, 0)
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeScMidiMessage(0, 16)
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeScMidiMessage(0, -1)
  }

  behavior of "SongPositionPointerScMidiMessage"

  it should "reject invalid positions" in {
    an[IllegalArgumentException] should be thrownBy SongPositionPointerScMidiMessage(16384)
    an[IllegalArgumentException] should be thrownBy SongPositionPointerScMidiMessage(-1)
  }

  behavior of "SongSelectScMidiMessage"

  it should "reject invalid song numbers" in {
    an[IllegalArgumentException] should be thrownBy SongSelectScMidiMessage(128)
    an[IllegalArgumentException] should be thrownBy SongSelectScMidiMessage(-1)
  }

  behavior of "SequenceNumberMetaScMidiMessage"

  it should "reject invalid numbers" in {
    an[IllegalArgumentException] should be thrownBy SequenceNumberMetaScMidiMessage(65536)
    an[IllegalArgumentException] should be thrownBy SequenceNumberMetaScMidiMessage(-1)
  }

  behavior of "MidiChannelPrefixMetaScMidiMessage"

  it should "reject invalid channels" in {
    an[IllegalArgumentException] should be thrownBy MidiChannelPrefixMetaScMidiMessage(16)
    an[IllegalArgumentException] should be thrownBy MidiChannelPrefixMetaScMidiMessage(-1)
  }

  behavior of "MidiPortMetaScMidiMessage"

  it should "reject invalid port values" in {
    an[IllegalArgumentException] should be thrownBy MidiPortMetaScMidiMessage(128)
    an[IllegalArgumentException] should be thrownBy MidiPortMetaScMidiMessage(-1)
  }

  behavior of "SetTempoMetaScMidiMessage"

  it should "reject invalid tempo values" in {
    an[IllegalArgumentException] should be thrownBy SetTempoMetaScMidiMessage(1 << 24)
    an[IllegalArgumentException] should be thrownBy SetTempoMetaScMidiMessage(-1)
  }

  behavior of "SmpteOffsetMetaScMidiMessage"

  it should "reject invalid field values" in {
    an[IllegalArgumentException] should be thrownBy SmpteOffsetMetaScMidiMessage(256, 0, 0, 0, 0)
    an[IllegalArgumentException] should be thrownBy SmpteOffsetMetaScMidiMessage(0, -1, 0, 0, 0)
  }

  behavior of "TimeSignatureMetaScMidiMessage"

  it should "reject invalid field values" in {
    an[IllegalArgumentException] should be thrownBy TimeSignatureMetaScMidiMessage(256, 0, 0, 0)
    an[IllegalArgumentException] should be thrownBy TimeSignatureMetaScMidiMessage(0, -1, 0, 0)
  }

  behavior of "KeySignatureMetaScMidiMessage"

  it should "reject invalid sharpsOrFlats" in {
    an[IllegalArgumentException] should be thrownBy KeySignatureMetaScMidiMessage(8, ScMidiKeySignatureMode.Major)
    an[IllegalArgumentException] should be thrownBy KeySignatureMetaScMidiMessage(-8, ScMidiKeySignatureMode.Major)
  }
}
