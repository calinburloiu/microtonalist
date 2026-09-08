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
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.collection.immutable.ArraySeq
import scala.compiletime.testing.typeChecks

class MidiMsgTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  behavior of "NoteOnMidiMsg"

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = NoteOnMidiMsg(3, MidiNote(60), 96)

    // When
    val mapped = original.mapChannel(_ + 5)

    // Then
    mapped shouldBe NoteOnMidiMsg(8, MidiNote(60), 96)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val noteOn = NoteOnMidiMsg(0, MidiNote(60))

    // When
    val mapped: NoteOnMidiMsg = noteOn.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "NoteOffMidiMsg"

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = NoteOffMidiMsg(3, MidiNote(60), 80)

    // When
    val mapped = original.mapChannel(_ => 10)

    // Then
    mapped shouldBe NoteOffMidiMsg(10, MidiNote(60), 80)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val noteOff = NoteOffMidiMsg(0, MidiNote(60))

    // When
    val mapped: NoteOffMidiMsg = noteOff.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "PolyPressureMidiMsg"

  it should "reject invalid channel and value" in {
    an[IllegalArgumentException] should be thrownBy PolyPressureMidiMsg(16, MidiNote(60), 80)
    an[IllegalArgumentException] should be thrownBy PolyPressureMidiMsg(0, MidiNote(60), 128)
    an[IllegalArgumentException] should be thrownBy PolyPressureMidiMsg(0, MidiNote(60), -1)
  }

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = PolyPressureMidiMsg(2, MidiNote(60), 100)

    // When
    val mapped = original.mapChannel(_ => 7)

    // Then
    mapped shouldBe PolyPressureMidiMsg(7, MidiNote(60), 100)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val polyPressure = PolyPressureMidiMsg(0, MidiNote(60), 80)

    // When
    val mapped: PolyPressureMidiMsg = polyPressure.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "CcMidiMsg"

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = CcMidiMsg(0, MidiCc.SustainPedal, 64)

    // When
    val mapped = original.mapChannel(_ => 9)

    // Then
    mapped shouldBe CcMidiMsg(9, MidiCc.SustainPedal, 64)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val cc = CcMidiMsg(0, MidiCc.ModulationMsb, 32)

    // When
    val mapped: CcMidiMsg = cc.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "ProgramChangeMidiMsg"

  it should "reject invalid channel and program" in {
    an[IllegalArgumentException] should be thrownBy ProgramChangeMidiMsg(16, 0)
    an[IllegalArgumentException] should be thrownBy ProgramChangeMidiMsg(0, 128)
    an[IllegalArgumentException] should be thrownBy ProgramChangeMidiMsg(0, -1)
  }

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = ProgramChangeMidiMsg(4, 42)

    // When
    val mapped = original.mapChannel(_ => 0)

    // Then
    mapped shouldBe ProgramChangeMidiMsg(0, 42)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val programChange = ProgramChangeMidiMsg(0, 5)

    // When
    val mapped: ProgramChangeMidiMsg = programChange.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "ChannelPressureMidiMsg"

  it should "reject invalid channel and value" in {
    an[IllegalArgumentException] should be thrownBy ChannelPressureMidiMsg(16, 0)
    an[IllegalArgumentException] should be thrownBy ChannelPressureMidiMsg(0, 128)
    an[IllegalArgumentException] should be thrownBy ChannelPressureMidiMsg(0, -1)
  }

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = ChannelPressureMidiMsg(5, 110)

    // When
    val mapped = original.mapChannel(_ => 1)

    // Then
    mapped shouldBe ChannelPressureMidiMsg(1, 110)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val channelPressure = ChannelPressureMidiMsg(0, 64)

    // When
    val mapped: ChannelPressureMidiMsg = channelPressure.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "PitchBendMidiMsg"

  it should "rewrite the channel via mapChannel" in {
    // Given
    val original = PitchBendMidiMsg(6, -2048)

    // When
    val mapped = original.mapChannel(_ => 11)

    // Then
    mapped shouldBe PitchBendMidiMsg(11, -2048)
  }

  it should "preserve the concrete subtype when mapping the channel" in {
    // Given
    val pitchBend = PitchBendMidiMsg(0, 0)

    // When
    val mapped: PitchBendMidiMsg = pitchBend.mapChannel(_ => 1)

    // Then
    mapped.channel shouldBe 1
  }

  behavior of "ChannelModeMidiMsg"

  private val channelModeMessages = Table(
    ("number", "message"),
    (AllSoundOffMidiMsg.Number, AllSoundOffMidiMsg(3)),
    (ResetAllControllersMidiMsg.Number, ResetAllControllersMidiMsg(3)),
    (LocalControlMidiMsg.Number, LocalControlMidiMsg(3, isOn = true)),
    (AllNotesOffMidiMsg.Number, AllNotesOffMidiMsg(3)),
    (OmniModeOffMidiMsg.Number, OmniModeOffMidiMsg(3)),
    (OmniModeOnMidiMsg.Number, OmniModeOnMidiMsg(3)),
    (MonoModeOnMidiMsg.Number, MonoModeOnMidiMsg(3, channelCount = 4)),
    (PolyModeOnMidiMsg.Number, PolyModeOnMidiMsg(3))
  )

  it should "assign each subtype the number MIDI 1.0 reserves for it" in {
    // When / Then
    AllSoundOffMidiMsg.Number shouldEqual 120
    ResetAllControllersMidiMsg.Number shouldEqual 121
    LocalControlMidiMsg.Number shouldEqual 122
    AllNotesOffMidiMsg.Number shouldEqual 123
    OmniModeOffMidiMsg.Number shouldEqual 124
    OmniModeOnMidiMsg.Number shouldEqual 125
    MonoModeOnMidiMsg.Number shouldEqual 126
    PolyModeOnMidiMsg.Number shouldEqual 127
  }

  it should "cover the Channel Mode number range exactly once, starting above the last controller number" in {
    // Given
    val numbers = channelModeMessages.map { case (number, _) => number }.toSeq

    // Then
    MidiRequirements.MaxControllerNumber shouldEqual 119
    ChannelModeMidiMsg.NumberRange shouldEqual (120 to 127)
    numbers should contain theSameElementsAs ChannelModeMidiMsg.NumberRange
  }

  it should "rewrite the channel via mapChannel, preserving the concrete subtype" in {
    forAll(channelModeMessages) { (_, message) =>
      // When
      val mapped = message.mapChannel(_ + 5)

      // Then
      mapped.channel shouldBe 8
      mapped.getClass shouldBe message.getClass
    }
  }

  it should "reject an invalid channel" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy AllSoundOffMidiMsg(16)
    an[IllegalArgumentException] should be thrownBy PolyModeOnMidiMsg(-1)
    an[IllegalArgumentException] should be thrownBy LocalControlMidiMsg(16, isOn = true)
    an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(16, channelCount = 0)
  }

  it should "carry the Local Control switch and its wire values" in {
    // When / Then
    LocalControlMidiMsg(0, isOn = true).isOn shouldBe true
    LocalControlMidiMsg(0, isOn = false).isOn shouldBe false
    LocalControlMidiMsg.OffValue shouldEqual 0
    LocalControlMidiMsg.OnValue shouldEqual 127
    LocalControlMidiMsg.OnThreshold shouldEqual 64
  }

  it should "accept every Mono Mode On channel count MIDI 1.0 allows" in {
    // Given
    val channelCounts = Table("channelCount", MonoModeOnMidiMsg.ChannelCountRange.toSeq*)

    forAll(channelCounts) { channelCount =>
      // When / Then
      MonoModeOnMidiMsg(0, channelCount).channelCount shouldEqual channelCount
    }
  }

  it should "reject a Mono Mode On channel count outside 0 to 16" in {
    // When / Then
    MonoModeOnMidiMsg.ChannelCountRange shouldEqual (0 to 16)
    an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(0, channelCount = -1)
    an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(0, channelCount = 17)
  }

  behavior of "MidiTimeCodeMidiMsg"

  it should "reject invalid messageType and values" in {
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeMidiMsg(8, 0)
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeMidiMsg(-1, 0)
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeMidiMsg(0, 16)
    an[IllegalArgumentException] should be thrownBy MidiTimeCodeMidiMsg(0, -1)
  }

  behavior of "SongPositionPointerMidiMsg"

  it should "reject invalid positions" in {
    an[IllegalArgumentException] should be thrownBy SongPositionPointerMidiMsg(16384)
    an[IllegalArgumentException] should be thrownBy SongPositionPointerMidiMsg(-1)
  }

  behavior of "SongSelectMidiMsg"

  it should "reject invalid song numbers" in {
    an[IllegalArgumentException] should be thrownBy SongSelectMidiMsg(128)
    an[IllegalArgumentException] should be thrownBy SongSelectMidiMsg(-1)
  }

  behavior of "SequenceNumberMetaMidiMsg"

  it should "reject invalid numbers" in {
    an[IllegalArgumentException] should be thrownBy SequenceNumberMetaMidiMsg(65536)
    an[IllegalArgumentException] should be thrownBy SequenceNumberMetaMidiMsg(-1)
  }

  behavior of "MidiChannelPrefixMetaMidiMsg"

  it should "reject invalid channels" in {
    an[IllegalArgumentException] should be thrownBy MidiChannelPrefixMetaMidiMsg(16)
    an[IllegalArgumentException] should be thrownBy MidiChannelPrefixMetaMidiMsg(-1)
  }

  behavior of "MidiPortMetaMidiMsg"

  it should "reject invalid port values" in {
    an[IllegalArgumentException] should be thrownBy MidiPortMetaMidiMsg(128)
    an[IllegalArgumentException] should be thrownBy MidiPortMetaMidiMsg(-1)
  }

  behavior of "SetTempoMetaMidiMsg"

  it should "reject invalid tempo values" in {
    an[IllegalArgumentException] should be thrownBy SetTempoMetaMidiMsg(1 << 24)
    an[IllegalArgumentException] should be thrownBy SetTempoMetaMidiMsg(-1)
  }

  behavior of "SmpteOffsetMetaMidiMsg"

  it should "reject invalid field values" in {
    an[IllegalArgumentException] should be thrownBy SmpteOffsetMetaMidiMsg(256, 0, 0, 0, 0)
    an[IllegalArgumentException] should be thrownBy SmpteOffsetMetaMidiMsg(0, -1, 0, 0, 0)
  }

  behavior of "TimeSignatureMetaMidiMsg"

  it should "reject invalid field values" in {
    an[IllegalArgumentException] should be thrownBy TimeSignatureMetaMidiMsg(256, 0, 0, 0)
    an[IllegalArgumentException] should be thrownBy TimeSignatureMetaMidiMsg(0, -1, 0, 0)
  }

  behavior of "KeySignatureMetaMidiMsg"

  it should "reject invalid sharpsOrFlats" in {
    an[IllegalArgumentException] should be thrownBy KeySignatureMetaMidiMsg(8, MidiKeySignatureMode.Major)
    an[IllegalArgumentException] should be thrownBy KeySignatureMetaMidiMsg(-8, MidiKeySignatureMode.Major)
  }

  behavior of "SysExMidiMsg"

  it should "expose the System Exclusive status byte" in {
    // Then
    SysExMidiMsg.StatusByte shouldEqual 0xF0.toByte
  }

  it should "expose the End of Exclusive byte" in {
    // Then
    SysExMidiMsg.EndOfExclusiveByte shouldEqual 0xF7.toByte
  }

  behavior of "MidiMsg hierarchy"

  it should "place every MIDI 1.0 message family under Midi1Msg" in {
    // When / Then
    typeChecks("val m: Midi1Msg = NoteOnMidiMsg(0, MidiNote(60))") shouldBe true // Channel Voice
    typeChecks("val m: Midi1Msg = TuneRequestMidiMsg") shouldBe true // System Common
    typeChecks("val m: Midi1Msg = TimingClockMidiMsg") shouldBe true // System Real-Time
    typeChecks("val m: Midi1Msg = SysExMidiMsg(ArraySeq.empty[Byte])") shouldBe true // System Exclusive
    typeChecks("val m: Midi1Msg = EndOfTrackMetaMidiMsg") shouldBe true // SMF meta
    typeChecks("val m: Midi1Msg = UnsupportedMidiMsg(ArraySeq.empty[Byte])") shouldBe true // fallback
  }

  it should "keep Midi1Msg and Midi2Msg as subtypes of MidiMsg" in {
    // When / Then
    typeChecks("summon[Midi1Msg <:< MidiMsg]") shouldBe true
    typeChecks("summon[Midi2Msg <:< MidiMsg]") shouldBe true
  }

  it should "place every Channel Mode message under ChannelModeMidiMsg, apart from the Control Changes" in {
    // When / Then
    typeChecks("val m: ChannelModeMidiMsg = AllSoundOffMidiMsg(0)") shouldBe true
    typeChecks("val m: ChannelMidiMsg = ResetAllControllersMidiMsg(0)") shouldBe true
    typeChecks("val m: Midi1Msg = MonoModeOnMidiMsg(0, 4)") shouldBe true
    typeChecks("val m: CcMidiMsg = LocalControlMidiMsg(0, true)") shouldBe false
  }
}
