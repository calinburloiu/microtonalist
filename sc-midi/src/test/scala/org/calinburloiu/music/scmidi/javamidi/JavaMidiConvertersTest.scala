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

package org.calinburloiu.music.scmidi.javamidi

import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.*
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import javax.sound.midi.{MetaMessage, MidiDevice, MidiMessage, ShortMessage, SysexMessage}
import scala.collection.immutable.ArraySeq
import scala.compiletime.testing.typeChecks

class JavaMidiConvertersTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers with MockFactory
  with Inside {

  private def shortMsg(status: Int, data1: Int, data2: Int): ShortMessage =
    new ShortMessage(status, data1, data2)

  private def shortMsgC(command: Int, channel: Int, data1: Int, data2: Int): ShortMessage =
    new ShortMessage(command, channel, data1, data2)

  private def metaMsg(metaType: Int, data: Array[Byte]): MetaMessage = {
    val m = new MetaMessage()
    m.setMessage(metaType, data, data.length)
    m
  }

  private def textBytes(s: String): Array[Byte] = s.getBytes("ISO-8859-1")

  private val sysexBytes: Array[Byte] = Array(0xF0.toByte, 0x43.toByte, 0x12.toByte, 0x7F.toByte, 0xF7.toByte)

  private val cases = Table[Midi1Msg, MidiMessage](
    ("MidiMsg", "Java MidiMessage"),
    // Channel Voice
    (NoteOnMidiMsg(5, MidiNote(62), 102), shortMsgC(ShortMessage.NOTE_ON, 5, 62, 102)),
    (NoteOffMidiMsg(5, MidiNote(62), 102), shortMsgC(ShortMessage.NOTE_OFF, 5, 62, 102)),
    (PolyPressureMidiMsg(2, MidiNote(60), 80), shortMsgC(ShortMessage.POLY_PRESSURE, 2, 60, 80)),
    (CcMidiMsg(15, 67, 64), shortMsgC(ShortMessage.CONTROL_CHANGE, 15, 67, 64)),
    (ProgramChangeMidiMsg(1, 42), shortMsgC(ShortMessage.PROGRAM_CHANGE, 1, 42, 0)),
    (ChannelPressureMidiMsg(3, 100), shortMsgC(ShortMessage.CHANNEL_PRESSURE, 3, 100, 0)),
    (PitchBendMidiMsg(3, 0), shortMsgC(ShortMessage.PITCH_BEND, 3, 0x00, 0x40)),
    (PitchBendMidiMsg(3, -8192), shortMsgC(ShortMessage.PITCH_BEND, 3, 0x00, 0x00)),
    (PitchBendMidiMsg(3, 8191), shortMsgC(ShortMessage.PITCH_BEND, 3, 0x7F, 0x7F)),
    (PitchBendMidiMsg(3, 1050), shortMsgC(ShortMessage.PITCH_BEND, 3, 0x1A, 0x48)),
    // System Common
    (MidiTimeCodeMidiMsg(3, 5), shortMsg(ShortMessage.MIDI_TIME_CODE, (3 << 4) | 5, 0)),
    (SongPositionPointerMidiMsg(1000), shortMsg(ShortMessage.SONG_POSITION_POINTER, 1000 & 0x7F, (1000 >> 7) & 0x7F)),
    (SongSelectMidiMsg(7), shortMsg(ShortMessage.SONG_SELECT, 7, 0)),
    (TuneRequestMidiMsg, new ShortMessage(ShortMessage.TUNE_REQUEST)),
    // System Real-Time
    (TimingClockMidiMsg, new ShortMessage(ShortMessage.TIMING_CLOCK)),
    (StartMidiMsg, new ShortMessage(ShortMessage.START)),
    (ContinueMidiMsg, new ShortMessage(ShortMessage.CONTINUE)),
    (StopMidiMsg, new ShortMessage(ShortMessage.STOP)),
    (ActiveSensingMidiMsg, new ShortMessage(ShortMessage.ACTIVE_SENSING)),
    (SystemResetMidiMsg, new ShortMessage(ShortMessage.SYSTEM_RESET)),
    // Sysex
    (SysExMidiMsg(ArraySeq.unsafeWrapArray(sysexBytes)), new SysexMessage(sysexBytes, sysexBytes.length)),
    // Meta
    (SequenceNumberMetaMidiMsg(0x1234), metaMsg(0x00, Array(0x12.toByte, 0x34.toByte))),
    (TextMetaMidiMsg("hello"), metaMsg(0x01, textBytes("hello"))),
    (CopyrightNoticeMetaMidiMsg("(c) 2026"), metaMsg(0x02, textBytes("(c) 2026"))),
    (TrackNameMetaMidiMsg("Track 1"), metaMsg(0x03, textBytes("Track 1"))),
    (InstrumentNameMetaMidiMsg("Piano"), metaMsg(0x04, textBytes("Piano"))),
    (LyricMetaMidiMsg("la"), metaMsg(0x05, textBytes("la"))),
    (MarkerMetaMidiMsg("A"), metaMsg(0x06, textBytes("A"))),
    (CuePointMetaMidiMsg("cue"), metaMsg(0x07, textBytes("cue"))),
    (ProgramNameMetaMidiMsg("Prog"), metaMsg(0x08, textBytes("Prog"))),
    (DeviceNameMetaMidiMsg("Dev"), metaMsg(0x09, textBytes("Dev"))),
    (MidiChannelPrefixMetaMidiMsg(9), metaMsg(0x20, Array(9.toByte))),
    (MidiPortMetaMidiMsg(3), metaMsg(0x21, Array(3.toByte))),
    (EndOfTrackMetaMidiMsg, metaMsg(0x2F, Array.emptyByteArray)),
    (SetTempoMetaMidiMsg(500000), metaMsg(0x51, Array(0x07.toByte, 0xA1.toByte, 0x20.toByte))),
    (
      SmpteOffsetMetaMidiMsg(1, 2, 3, 4, 5),
      metaMsg(0x54, Array(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte))
    ),
    (
      TimeSignatureMetaMidiMsg(4, 2, 24, 8),
      metaMsg(0x58, Array(4.toByte, 2.toByte, 24.toByte, 8.toByte))
    ),
    (
      KeySignatureMetaMidiMsg(-3, MidiKeySignatureMode.Minor),
      metaMsg(0x59, Array((-3).toByte, 1.toByte))
    ),
    (
      KeySignatureMetaMidiMsg(2, MidiKeySignatureMode.Major),
      metaMsg(0x59, Array(2.toByte, 0.toByte))
    ),
    (
      SequencerSpecificMetaMidiMsg(ArraySeq(0x00.toByte, 0x12.toByte, 0x34.toByte)),
      metaMsg(0x7F, Array(0x00.toByte, 0x12.toByte, 0x34.toByte))
    )
  )

  behavior of "JavaMidiConverters.asJava"

  it should "produce Java bytes equal to the expected Java MidiMessage for every MidiMsg subtype" in {
    forAll(cases) { (scMsg, javaMsg) =>
      // When
      val actual = scMsg.asJava

      // Then
      actual.getMessage should equal(javaMsg.getMessage)
    }
  }

  behavior of "JavaMidiConverters.asScala"

  it should "produce the expected MidiMsg for every Java MidiMessage" in {
    forAll(cases) { (scMsg, javaMsg) =>
      // When
      val actual = javaMsg.asScala

      // Then
      actual should equal(scMsg)
    }
  }

  it should "reject a null MidiMessage" in {
    // Given
    val nullMessage: MidiMessage = null

    // When / Then
    an[IllegalArgumentException] should be thrownBy nullMessage.asScala
  }

  behavior of "UnsupportedMidiMsg round-trip"

  it should "round-trip a ShortMessage with an unknown command" in {
    // Given: channel voice command that's not in FromShortMap — there is none; use a system common not recognized.
    // All defined commands are registered, so craft a 2-byte status for a rarely seen value by using a short
    // 1-byte status under 0xF0 won't work (handled as command). Use an unused real-time status: 0xF9 (undefined).
    val msg = new ShortMessage()
    msg.setMessage(0xF9)

    // When / Then
    inside(msg.asScala) {
      case sc: UnsupportedMidiMsg =>
        sc.asJava.getMessage should equal(msg.getMessage)
    }
  }

  it should "round-trip a MetaMessage with an unknown meta type" in {
    // Given: meta type 0x60 is not registered
    val payload = Array[Byte](0x01, 0x02, 0x03)
    val msg = new MetaMessage()
    msg.setMessage(0x60, payload, payload.length)

    // When / Then
    inside(msg.asScala) {
      case sc: UnsupportedMidiMsg =>
        sc.asJava.getMessage should equal(msg.getMessage)
    }
  }

  it should "round-trip a SysexMessage via UnsupportedMidiMsg constructed from raw bytes" in {
    // Given
    val unsupported = UnsupportedMidiMsg(ArraySeq.unsafeWrapArray(sysexBytes))

    // When
    val javaMsg = unsupported.asJava

    // Then
    javaMsg shouldBe a[SysexMessage]
    javaMsg.getMessage should equal(sysexBytes)
  }

  behavior of "JavaMidiConverters.isInputDevice"

  it should "be true for devices with unlimited or positive maximum transmitters and false otherwise" in {
    // Given
    val cases = Table(
      ("maxTransmitters", "expected"),
      (-1, true),
      (0, false),
      (1, true),
      (8, true)
    )

    forAll(cases) { (maxTransmitters, expected) =>
      val device = stub[MidiDevice]
      (() => device.getMaxTransmitters).when().returns(maxTransmitters)

      // When / Then
      device.isInputDevice shouldBe expected
    }
  }

  behavior of "JavaMidiConverters.isOutputDevice"

  it should "be true for devices with unlimited or positive maximum receivers and false otherwise" in {
    // Given
    val cases = Table(
      ("maxReceivers", "expected"),
      (-1, true),
      (0, false),
      (1, true),
      (8, true)
    )

    forAll(cases) { (maxReceivers, expected) =>
      val device = stub[MidiDevice]
      (() => device.getMaxReceivers).when().returns(maxReceivers)

      // When / Then
      device.isOutputDevice shouldBe expected
    }
  }

  behavior of "JavaMidiConverters.asJava availability"

  it should "be defined for Midi1Msg but not for Midi2Msg" in {
    // When / Then
    typeChecks("(??? : Midi1Msg).asJava") shouldBe true
    typeChecks("(??? : Midi2Msg).asJava") shouldBe false
  }
}
