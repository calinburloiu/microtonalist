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

import org.calinburloiu.music.scmidi.{MidiNote, PitchBendSensitivity}
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.ArraySeq
import scala.compiletime.testing.typeChecks

class MidiMsgTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  "NoteOnMidiMsg" should {
    "rewrite the channel via mapChannel" in {
      // Given
      val original = NoteOnMidiMsg(3, MidiNote(60), 96)

      // When
      val mapped = original.mapChannel(_ + 5)

      // Then
      mapped shouldBe NoteOnMidiMsg(8, MidiNote(60), 96)
    }

    "preserve the concrete subtype when mapping the channel" in {
      // Given
      val noteOn = NoteOnMidiMsg(0, MidiNote(60))

      // When
      val mapped: NoteOnMidiMsg = noteOn.mapChannel(_ => 1)

      // Then
      mapped.channel shouldBe 1
    }

    "default the velocity to 64" in {
      // When
      val noteOn = NoteOnMidiMsg(0, MidiNote.C4)

      // Then
      NoteOnMidiMsg.DefaultVelocity shouldEqual 64
      noteOn.velocity shouldEqual NoteOnMidiMsg.DefaultVelocity
    }

    "accept the boundary channels, notes and velocities, including the Note Off velocity" in {
      // When
      val lowest = NoteOnMidiMsg(0, MidiNote(0), NoteOnMidiMsg.NoteOffVelocity)
      val highest = NoteOnMidiMsg(15, MidiNote(127), 127)

      // Then
      NoteOnMidiMsg.NoteOffVelocity shouldEqual 0
      (lowest.channel, lowest.midiNote.number, lowest.velocity) shouldEqual (0, 0, 0)
      (highest.channel, highest.midiNote.number, highest.velocity) shouldEqual (15, 127, 127)
    }

    "reject an invalid channel, note or velocity" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy NoteOnMidiMsg(16, MidiNote.C4, 100)
      an[IllegalArgumentException] should be thrownBy NoteOnMidiMsg(-1, MidiNote.C4, 100)
      an[IllegalArgumentException] should be thrownBy NoteOnMidiMsg(0, MidiNote(128), 100)
      an[IllegalArgumentException] should be thrownBy NoteOnMidiMsg(0, MidiNote(-1), 100)
      an[IllegalArgumentException] should be thrownBy NoteOnMidiMsg(0, MidiNote.C4, 128)
      an[IllegalArgumentException] should be thrownBy NoteOnMidiMsg(0, MidiNote.C4, -1)
    }
  }

  "NoteOffMidiMsg" should {
    "rewrite the channel via mapChannel" in {
      // Given
      val original = NoteOffMidiMsg(3, MidiNote(60), 80)

      // When
      val mapped = original.mapChannel(_ => 10)

      // Then
      mapped shouldBe NoteOffMidiMsg(10, MidiNote(60), 80)
    }

    "preserve the concrete subtype when mapping the channel" in {
      // Given
      val noteOff = NoteOffMidiMsg(0, MidiNote(60))

      // When
      val mapped: NoteOffMidiMsg = noteOff.mapChannel(_ => 1)

      // Then
      mapped.channel shouldBe 1
    }

    "default the velocity to 64" in {
      // When
      val noteOff = NoteOffMidiMsg(0, MidiNote.C4)

      // Then
      NoteOffMidiMsg.DefaultVelocity shouldEqual 64
      noteOff.velocity shouldEqual NoteOffMidiMsg.DefaultVelocity
    }

    "reject an invalid channel, note or velocity" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy NoteOffMidiMsg(16, MidiNote.C4, 64)
      an[IllegalArgumentException] should be thrownBy NoteOffMidiMsg(0, MidiNote(128), 64)
      an[IllegalArgumentException] should be thrownBy NoteOffMidiMsg(0, MidiNote.C4, 128)
      an[IllegalArgumentException] should be thrownBy NoteOffMidiMsg(0, MidiNote.C4, -1)
    }
  }

  "PolyPressureMidiMsg" should {
    "reject invalid channel and value" in {
      an[IllegalArgumentException] should be thrownBy PolyPressureMidiMsg(16, MidiNote(60), 80)
      an[IllegalArgumentException] should be thrownBy PolyPressureMidiMsg(0, MidiNote(60), 128)
      an[IllegalArgumentException] should be thrownBy PolyPressureMidiMsg(0, MidiNote(60), -1)
    }

    "rewrite the channel via mapChannel" in {
      // Given
      val original = PolyPressureMidiMsg(2, MidiNote(60), 100)

      // When
      val mapped = original.mapChannel(_ => 7)

      // Then
      mapped shouldBe PolyPressureMidiMsg(7, MidiNote(60), 100)
    }

    "preserve the concrete subtype when mapping the channel" in {
      // Given
      val polyPressure = PolyPressureMidiMsg(0, MidiNote(60), 80)

      // When
      val mapped: PolyPressureMidiMsg = polyPressure.mapChannel(_ => 1)

      // Then
      mapped.channel shouldBe 1
    }
  }

  "CcMidiMsg" should {
    "rewrite the channel via mapChannel" in {
      // Given
      val original = CcMidiMsg(0, MidiCc.SustainPedal, 64)

      // When
      val mapped = original.mapChannel(_ => 9)

      // Then
      mapped shouldBe CcMidiMsg(9, MidiCc.SustainPedal, 64)
    }

    "preserve the concrete subtype when mapping the channel" in {
      // Given
      val cc = CcMidiMsg(0, MidiCc.ModulationMsb, 32)

      // When
      val mapped: CcMidiMsg = cc.mapChannel(_ => 1)

      // Then
      mapped.channel shouldBe 1
    }

    "accept every controller number MIDI 1.0 defines" in {
      // Given
      val numbers = Table("number", 0, 1, 64, MidiRequirements.MaxControllerNumber)

      forAll(numbers) { number =>
        // When / Then
        CcMidiMsg(0, number, 0).number shouldEqual number
      }
    }

    "name the Channel Mode message types when rejecting a Channel Mode number" in {
      // When
      val exception = the[IllegalArgumentException] thrownBy CcMidiMsg(0, AllNotesOffMidiMsg.Number, 0)

      // Then
      exception.getMessage should include("Channel Mode")
      exception.getMessage should include("ChannelModeMidiMsg")
    }

    "reject the Channel Mode numbers and anything outside 0 to 119" in {
      // Given
      val numbers = Table("number", ChannelModeMidiMsg.NumberRange.toSeq*)

      forAll(numbers) { number =>
        // When / Then
        an[IllegalArgumentException] should be thrownBy CcMidiMsg(0, number, 0)
      }
      an[IllegalArgumentException] should be thrownBy CcMidiMsg(0, -1, 0)
      an[IllegalArgumentException] should be thrownBy CcMidiMsg(0, 128, 0)
    }
  }

  "ProgramChangeMidiMsg" should {
    "reject invalid channel and program" in {
      an[IllegalArgumentException] should be thrownBy ProgramChangeMidiMsg(16, 0)
      an[IllegalArgumentException] should be thrownBy ProgramChangeMidiMsg(0, 128)
      an[IllegalArgumentException] should be thrownBy ProgramChangeMidiMsg(0, -1)
    }

    "rewrite the channel via mapChannel" in {
      // Given
      val original = ProgramChangeMidiMsg(4, 42)

      // When
      val mapped = original.mapChannel(_ => 0)

      // Then
      mapped shouldBe ProgramChangeMidiMsg(0, 42)
    }

    "preserve the concrete subtype when mapping the channel" in {
      // Given
      val programChange = ProgramChangeMidiMsg(0, 5)

      // When
      val mapped: ProgramChangeMidiMsg = programChange.mapChannel(_ => 1)

      // Then
      mapped.channel shouldBe 1
    }
  }

  "ChannelPressureMidiMsg" should {
    "reject invalid channel and value" in {
      an[IllegalArgumentException] should be thrownBy ChannelPressureMidiMsg(16, 0)
      an[IllegalArgumentException] should be thrownBy ChannelPressureMidiMsg(0, 128)
      an[IllegalArgumentException] should be thrownBy ChannelPressureMidiMsg(0, -1)
    }

    "rewrite the channel via mapChannel" in {
      // Given
      val original = ChannelPressureMidiMsg(5, 110)

      // When
      val mapped = original.mapChannel(_ => 1)

      // Then
      mapped shouldBe ChannelPressureMidiMsg(1, 110)
    }

    "preserve the concrete subtype when mapping the channel" in {
      // Given
      val channelPressure = ChannelPressureMidiMsg(0, 64)

      // When
      val mapped: ChannelPressureMidiMsg = channelPressure.mapChannel(_ => 1)

      // Then
      mapped.channel shouldBe 1
    }
  }

  "PitchBendMidiMsg" should {
    "rewrite the channel via mapChannel" in {
      // Given
      val original = PitchBendMidiMsg(6, -2048)

      // When
      val mapped = original.mapChannel(_ => 11)

      // Then
      mapped shouldBe PitchBendMidiMsg(11, -2048)
    }

    "preserve the concrete subtype when mapping the channel" in {
      // Given
      val pitchBend = PitchBendMidiMsg(0, 0)

      // When
      val mapped: PitchBendMidiMsg = pitchBend.mapChannel(_ => 1)

      // Then
      mapped.channel shouldBe 1
    }

    "accept the signed 14-bit range and reject a value outside it or an invalid channel" in {
      // When / Then
      PitchBendMidiMsg.MinValue shouldEqual -8192
      PitchBendMidiMsg.NoPitchBendValue shouldEqual 0
      PitchBendMidiMsg.MaxValue shouldEqual 8191
      PitchBendMidiMsg(0, PitchBendMidiMsg.MinValue).value shouldEqual -8192
      PitchBendMidiMsg(15, PitchBendMidiMsg.MaxValue).value shouldEqual 8191
      an[IllegalArgumentException] should be thrownBy PitchBendMidiMsg(0, 8192)
      an[IllegalArgumentException] should be thrownBy PitchBendMidiMsg(0, -8193)
      an[IllegalArgumentException] should be thrownBy PitchBendMidiMsg(16, 0)
    }

    "convert its value to cents against a given pitch bend sensitivity" in {
      // Given
      val cases = Table(
        ("value", "pitchBendSensitivity", "cents"),
        (0, PitchBendSensitivity.Default, 0.0),
        (8191, PitchBendSensitivity.Default, 200.0),
        (-8192, PitchBendSensitivity.Default, -200.0),
        (-4096, PitchBendSensitivity.Default, -100.0),
        (8191, PitchBendSensitivity(1, 50), 150.0),
        (-8192, PitchBendSensitivity(12), -1200.0)
      )

      forAll(cases) { (value, pitchBendSensitivity, cents) =>
        // When / Then
        PitchBendMidiMsg(0, value).centsFor(pitchBendSensitivity) shouldEqual cents
      }
    }

    "convert its value to cents against the implicit pitch bend sensitivity" in {
      // Given
      implicit val pitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(12)

      // When / Then
      PitchBendMidiMsg(0, 8191).cents shouldEqual 1200.0
      PitchBendMidiMsg(0, -4096).cents shouldEqual -600.0
    }

    "be created from cents against a given pitch bend sensitivity" in {
      // Given
      val cases = Table(
        ("cents", "pitchBendSensitivity", "value"),
        (0, PitchBendSensitivity.Default, 0),
        (100, PitchBendSensitivity.Default, 4096),
        (-100, PitchBendSensitivity.Default, -4096),
        (200, PitchBendSensitivity.Default, 8191),
        (-200, PitchBendSensitivity.Default, -8192),
        (50, PitchBendSensitivity(1), 4096)
      )

      forAll(cases) { (cents, pitchBendSensitivity, value) =>
        // When / Then
        PitchBendMidiMsg.fromCents(3, cents, pitchBendSensitivity) shouldEqual PitchBendMidiMsg(3, value)
      }
    }

    "be created from cents against the default pitch bend sensitivity of 2 semitones when none is given" in {
      // When / Then
      PitchBendMidiMsg.fromCents(3, 100) shouldEqual PitchBendMidiMsg(3, 4096)
    }

    "reject being created from cents beyond the pitch bend sensitivity" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy PitchBendMidiMsg.fromCents(0, 201)
      an[IllegalArgumentException] should be thrownBy PitchBendMidiMsg.fromCents(0, -201)
      an[IllegalArgumentException] should be thrownBy PitchBendMidiMsg.fromCents(0, 101, PitchBendSensitivity(1))
    }
  }

  "PitchBendMidiMsg conversions" should {
    "scale cents down against MinValue and up against MaxValue, rounding to the nearest value" in {
      // Given
      val cases = Table(
        ("cents", "value"),
        (12.5, 512),
        (-12.5, -512),
        (50.0, 2048),
        (-50.0, -2048),
        (199.99, 8191),
        (-199.99, -8192)
      )

      forAll(cases) { (cents, value) =>
        // When / Then
        PitchBendMidiMsg.convertCentsToValue(cents, PitchBendSensitivity.Default) shouldEqual value
      }
    }

    "convert cents to a value and back to within half a step" in {
      // Given
      // 100 cents falls exactly halfway between two values, so the tolerance needs slack for floating-point error.
      val halfStepCents = 0.5 * PitchBendSensitivity.Default.totalCents / PitchBendMidiMsg.MaxValue + 1e-9
      val cases = Table("cents", (-200 to 200)*)

      forAll(cases) { cents =>
        // When
        val value = PitchBendMidiMsg.convertCentsToValue(cents, PitchBendSensitivity.Default)

        // Then
        PitchBendMidiMsg.convertValueToCents(value, PitchBendSensitivity.Default) shouldEqual
          cents.toDouble +- halfStepCents
      }
    }

    "reject converting a value outside the signed 14-bit range to cents" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy
        PitchBendMidiMsg.convertValueToCents(8192, PitchBendSensitivity.Default)
      an[IllegalArgumentException] should be thrownBy
        PitchBendMidiMsg.convertValueToCents(-8193, PitchBendSensitivity.Default)
    }

    "convert cents to the LSB and MSB data bytes, which convert back to the same value" in {
      // Given
      val cases = Table(
        ("cents", "value", "dataBytes"),
        (0.0, 0, (0x00, 0x40)),
        (200.0, 8191, (0x7F, 0x7F)),
        (-200.0, -8192, (0x00, 0x00)),
        (100.0, 4096, (0x00, 0x60))
      )

      forAll(cases) { (cents, value, dataBytes) =>
        // When
        val (lsb, msb) = PitchBendMidiMsg.convertCentsToDataBytes(cents, PitchBendSensitivity.Default)

        // Then
        (lsb, msb) shouldEqual dataBytes
        PitchBendMidiMsg.convertDataBytesToValue(lsb, msb) shouldEqual value
      }
    }
  }

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

  "ChannelModeMidiMsg" should {
    "assign each subtype the number MIDI 1.0 reserves for it" in {
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

    "cover the Channel Mode number range exactly once, starting above the last controller number" in {
      // Given
      val numbers = channelModeMessages.map { case (number, _) => number }.toSeq

      // Then
      MidiRequirements.MaxControllerNumber shouldEqual 119
      ChannelModeMidiMsg.NumberRange shouldEqual (120 to 127)
      numbers should contain theSameElementsAs ChannelModeMidiMsg.NumberRange
    }

    "rewrite the channel via mapChannel, preserving the concrete subtype" in {
      forAll(channelModeMessages) { (_, message) =>
        // When
        val mapped = message.mapChannel(_ + 5)

        // Then
        mapped.channel shouldBe 8
        mapped.getClass shouldBe message.getClass
      }
    }

    "reject an invalid channel" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy AllSoundOffMidiMsg(16)
      an[IllegalArgumentException] should be thrownBy PolyModeOnMidiMsg(-1)
      an[IllegalArgumentException] should be thrownBy LocalControlMidiMsg(16, isOn = true)
      an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(16, channelCount = 0)
    }

    "carry the Local Control switch and its wire values" in {
      // When / Then
      LocalControlMidiMsg(0, isOn = true).isOn shouldBe true
      LocalControlMidiMsg(0, isOn = false).isOn shouldBe false
      LocalControlMidiMsg.OffValue shouldEqual 0
      LocalControlMidiMsg.OnValue shouldEqual 127
      LocalControlMidiMsg.OnThreshold shouldEqual 64
    }

    "accept every Mono Mode On channel count MIDI 1.0 allows" in {
      // Given
      val channelCounts = Table("channelCount", MonoModeOnMidiMsg.ChannelCountRange.toSeq*)

      forAll(channelCounts) { channelCount =>
        // When / Then
        MonoModeOnMidiMsg(0, channelCount).channelCount shouldEqual channelCount
      }
    }

    "reject a Mono Mode On channel count outside 0 to 16" in {
      // When / Then
      MonoModeOnMidiMsg.ChannelCountRange shouldEqual (0 to 16)
      an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(0, channelCount = -1)
      an[IllegalArgumentException] should be thrownBy MonoModeOnMidiMsg(0, channelCount = 17)
    }
  }

  "MidiTimeCodeMidiMsg" should {
    "reject invalid messageType and values" in {
      an[IllegalArgumentException] should be thrownBy MidiTimeCodeMidiMsg(8, 0)
      an[IllegalArgumentException] should be thrownBy MidiTimeCodeMidiMsg(-1, 0)
      an[IllegalArgumentException] should be thrownBy MidiTimeCodeMidiMsg(0, 16)
      an[IllegalArgumentException] should be thrownBy MidiTimeCodeMidiMsg(0, -1)
    }
  }

  "SongPositionPointerMidiMsg" should {
    "reject invalid positions" in {
      an[IllegalArgumentException] should be thrownBy SongPositionPointerMidiMsg(16384)
      an[IllegalArgumentException] should be thrownBy SongPositionPointerMidiMsg(-1)
    }
  }

  "SongSelectMidiMsg" should {
    "reject invalid song numbers" in {
      an[IllegalArgumentException] should be thrownBy SongSelectMidiMsg(128)
      an[IllegalArgumentException] should be thrownBy SongSelectMidiMsg(-1)
    }
  }

  "SequenceNumberMetaMidiMsg" should {
    "reject invalid numbers" in {
      an[IllegalArgumentException] should be thrownBy SequenceNumberMetaMidiMsg(65536)
      an[IllegalArgumentException] should be thrownBy SequenceNumberMetaMidiMsg(-1)
    }
  }

  "MidiChannelPrefixMetaMidiMsg" should {
    "reject invalid channels" in {
      an[IllegalArgumentException] should be thrownBy MidiChannelPrefixMetaMidiMsg(16)
      an[IllegalArgumentException] should be thrownBy MidiChannelPrefixMetaMidiMsg(-1)
    }
  }

  "MidiPortMetaMidiMsg" should {
    "reject invalid port values" in {
      an[IllegalArgumentException] should be thrownBy MidiPortMetaMidiMsg(128)
      an[IllegalArgumentException] should be thrownBy MidiPortMetaMidiMsg(-1)
    }
  }

  "SetTempoMetaMidiMsg" should {
    "reject invalid tempo values" in {
      an[IllegalArgumentException] should be thrownBy SetTempoMetaMidiMsg(1 << 24)
      an[IllegalArgumentException] should be thrownBy SetTempoMetaMidiMsg(-1)
    }
  }

  "SmpteOffsetMetaMidiMsg" should {
    "reject invalid field values" in {
      an[IllegalArgumentException] should be thrownBy SmpteOffsetMetaMidiMsg(256, 0, 0, 0, 0)
      an[IllegalArgumentException] should be thrownBy SmpteOffsetMetaMidiMsg(0, -1, 0, 0, 0)
    }
  }

  "TimeSignatureMetaMidiMsg" should {
    "reject invalid field values" in {
      an[IllegalArgumentException] should be thrownBy TimeSignatureMetaMidiMsg(256, 0, 0, 0)
      an[IllegalArgumentException] should be thrownBy TimeSignatureMetaMidiMsg(0, -1, 0, 0)
    }
  }

  "KeySignatureMetaMidiMsg" should {
    "reject invalid sharpsOrFlats" in {
      an[IllegalArgumentException] should be thrownBy KeySignatureMetaMidiMsg(8, MidiKeySignatureMode.Major)
      an[IllegalArgumentException] should be thrownBy KeySignatureMetaMidiMsg(-8, MidiKeySignatureMode.Major)
    }
  }

  "MidiMsg hierarchy" should {
    "place every MIDI 1.0 message family under Midi1Msg" in {
      // When / Then
      typeChecks("val m: Midi1Msg = NoteOnMidiMsg(0, MidiNote(60))") shouldBe true // Channel Voice
      typeChecks("val m: Midi1Msg = TuneRequestMidiMsg") shouldBe true // System Common
      typeChecks("val m: Midi1Msg = TimingClockMidiMsg") shouldBe true // System Real-Time
      typeChecks("val m: Midi1Msg = SysExMidiMsg(ArraySeq.empty[Byte])") shouldBe true // System Exclusive
      typeChecks("val m: Midi1Msg = EndOfTrackMetaMidiMsg") shouldBe true // SMF meta
      typeChecks("val m: Midi1Msg = UnsupportedMidiMsg(ArraySeq.empty[Byte])") shouldBe true // fallback
    }

    "keep Midi1Msg and Midi2Msg as subtypes of MidiMsg" in {
      // When / Then
      typeChecks("summon[Midi1Msg <:< MidiMsg]") shouldBe true
      typeChecks("summon[Midi2Msg <:< MidiMsg]") shouldBe true
    }

    "place every Channel Mode message under ChannelModeMidiMsg, apart from the Control Changes" in {
      // When / Then
      typeChecks("val m: ChannelModeMidiMsg = AllSoundOffMidiMsg(0)") shouldBe true
      typeChecks("val m: ChannelMidiMsg = ResetAllControllersMidiMsg(0)") shouldBe true
      typeChecks("val m: Midi1Msg = MonoModeOnMidiMsg(0, 4)") shouldBe true
      typeChecks("val m: CcMidiMsg = LocalControlMidiMsg(0, true)") shouldBe false
    }
  }
}
