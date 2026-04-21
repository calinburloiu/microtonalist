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

class ScMidiMessageValidationTest extends AnyFlatSpec with Matchers {

  behavior of "PolyPressureScMidiMessage"

  it should "reject invalid channel and value" in {
    an[IllegalArgumentException] should be thrownBy PolyPressureScMidiMessage(16, MidiNote(60), 80)
    an[IllegalArgumentException] should be thrownBy PolyPressureScMidiMessage(0, MidiNote(60), 128)
    an[IllegalArgumentException] should be thrownBy PolyPressureScMidiMessage(0, MidiNote(60), -1)
  }

  behavior of "ProgramChangeScMidiMessage"

  it should "reject invalid channel and program" in {
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(16, 0)
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(0, 128)
    an[IllegalArgumentException] should be thrownBy ProgramChangeScMidiMessage(0, -1)
  }

  behavior of "ChannelPressureScMidiMessage"

  it should "reject invalid channel and value" in {
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(16, 0)
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(0, 128)
    an[IllegalArgumentException] should be thrownBy ChannelPressureScMidiMessage(0, -1)
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
