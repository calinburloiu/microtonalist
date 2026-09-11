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

import org.calinburloiu.music.scmidi.{MidiConnectionLimit, MidiDeviceId, MidiDeviceInfo, MidiNote}
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

  private def shortMessage(status: Int, data1: Int, data2: Int): ShortMessage =
    new ShortMessage(status, data1, data2)

  private def shortMsgC(command: Int, channel: Int, data1: Int, data2: Int): ShortMessage =
    new ShortMessage(command, channel, data1, data2)

  private def metaMessage(metaType: Int, data: Array[Byte]): MetaMessage = {
    val m = new MetaMessage()
    m.setMessage(metaType, data, data.length)
    m
  }

  private def textBytes(s: String): Array[Byte] = s.getBytes("ISO-8859-1")

  /** `MidiDevice.Info` has a protected constructor; this is the four-line subclass tests need to build one. */
  private class TestDeviceInfo(name: String, vendor: String, description: String, version: String)
    extends MidiDevice.Info(name, vendor, description, version)

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
    // Channel Mode
    (AllSoundOffMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 120, 0)),
    (ResetAllControllersMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 121, 0)),
    (LocalControlMidiMsg(4, isOn = false), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 122, 0)),
    (LocalControlMidiMsg(4, isOn = true), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 122, 127)),
    (AllNotesOffMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 123, 0)),
    (OmniModeOffMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 124, 0)),
    (OmniModeOnMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 125, 0)),
    (MonoModeOnMidiMsg(4, channelCount = 4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 126, 4)),
    (MonoModeOnMidiMsg(4, channelCount = 0), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 126, 0)),
    (MonoModeOnMidiMsg(4, channelCount = 16), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 126, 16)),
    (PolyModeOnMidiMsg(4), shortMsgC(ShortMessage.CONTROL_CHANGE, 4, 127, 0)),
    // System Common
    (MidiTimeCodeMidiMsg(3, 5), shortMessage(ShortMessage.MIDI_TIME_CODE, (3 << 4) | 5, 0)),
    (
      SongPositionPointerMidiMsg(1000),
      shortMessage(ShortMessage.SONG_POSITION_POINTER, 1000 & 0x7F, (1000 >> 7) & 0x7F)
    ),
    (SongSelectMidiMsg(7), shortMessage(ShortMessage.SONG_SELECT, 7, 0)),
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
    (SequenceNumberMetaMidiMsg(0x1234), metaMessage(0x00, Array(0x12.toByte, 0x34.toByte))),
    (TextMetaMidiMsg("hello"), metaMessage(0x01, textBytes("hello"))),
    (CopyrightNoticeMetaMidiMsg("(c) 2026"), metaMessage(0x02, textBytes("(c) 2026"))),
    (TrackNameMetaMidiMsg("Track 1"), metaMessage(0x03, textBytes("Track 1"))),
    (InstrumentNameMetaMidiMsg("Piano"), metaMessage(0x04, textBytes("Piano"))),
    (LyricMetaMidiMsg("la"), metaMessage(0x05, textBytes("la"))),
    (MarkerMetaMidiMsg("A"), metaMessage(0x06, textBytes("A"))),
    (CuePointMetaMidiMsg("cue"), metaMessage(0x07, textBytes("cue"))),
    (ProgramNameMetaMidiMsg("Prog"), metaMessage(0x08, textBytes("Prog"))),
    (DeviceNameMetaMidiMsg("Dev"), metaMessage(0x09, textBytes("Dev"))),
    (MidiChannelPrefixMetaMidiMsg(9), metaMessage(0x20, Array(9.toByte))),
    (MidiPortMetaMidiMsg(3), metaMessage(0x21, Array(3.toByte))),
    (EndOfTrackMetaMidiMsg, metaMessage(0x2F, Array.emptyByteArray)),
    (SetTempoMetaMidiMsg(500000), metaMessage(0x51, Array(0x07.toByte, 0xA1.toByte, 0x20.toByte))),
    (
      SmpteOffsetMetaMidiMsg(1, 2, 3, 4, 5),
      metaMessage(0x54, Array(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte))
    ),
    (
      TimeSignatureMetaMidiMsg(4, 2, 24, 8),
      metaMessage(0x58, Array(4.toByte, 2.toByte, 24.toByte, 8.toByte))
    ),
    (
      KeySignatureMetaMidiMsg(-3, MidiKeySignatureMode.Minor),
      metaMessage(0x59, Array((-3).toByte, 1.toByte))
    ),
    (
      KeySignatureMetaMidiMsg(2, MidiKeySignatureMode.Major),
      metaMessage(0x59, Array(2.toByte, 0.toByte))
    ),
    (
      SequencerSpecificMetaMidiMsg(ArraySeq(0x00.toByte, 0x12.toByte, 0x34.toByte)),
      metaMessage(0x7F, Array(0x00.toByte, 0x12.toByte, 0x34.toByte))
    )
  )

  behavior of "JavaMidiConverters.asJava"

  it should "produce Java bytes equal to the expected Java MidiMessage for every MidiMsg subtype" in {
    forAll(cases) { (scalaMessage, javaMessage) =>
      // When
      val actual = scalaMessage.asJava

      // Then
      actual.getMessage should equal(javaMessage.getMessage)
    }
  }

  behavior of "JavaMidiConverters.asScala"

  it should "produce the expected MidiMsg for every Java MidiMessage" in {
    forAll(cases) { (scalaMessage, javaMessage) =>
      // When
      val actual = javaMessage.asScala

      // Then
      actual should equal(scalaMessage)
    }
  }

  it should "reject a null MidiMessage" in {
    // Given
    val nullMessage: MidiMessage = null

    // When / Then
    an[IllegalArgumentException] should be thrownBy nullMessage.asScala
  }

  behavior of "JavaMidiConverters Channel Mode messages"

  it should "decode a valueless Channel Mode message whatever data byte it carries" in {
    // Given
    val valuelessMessages = Table[Int, ChannelModeMidiMsg](
      ("number", "message"),
      (AllSoundOffMidiMsg.Number, AllSoundOffMidiMsg(2)),
      (ResetAllControllersMidiMsg.Number, ResetAllControllersMidiMsg(2)),
      (AllNotesOffMidiMsg.Number, AllNotesOffMidiMsg(2)),
      (OmniModeOffMidiMsg.Number, OmniModeOffMidiMsg(2)),
      (OmniModeOnMidiMsg.Number, OmniModeOnMidiMsg(2)),
      (PolyModeOnMidiMsg.Number, PolyModeOnMidiMsg(2))
    )
    val dataBytes = Seq(0, 1, 64, 127)

    forAll(valuelessMessages) { (number, message) =>
      for (dataByte <- dataBytes) {
        // When / Then
        withClue(s"data byte $dataByte:") {
          shortMsgC(ShortMessage.CONTROL_CHANGE, 2, number, dataByte).asScala shouldEqual message
        }
      }
    }
  }

  it should "decode Local Control by the switch convention: 0-63 off, 64-127 on" in {
    // Given
    val dataBytes = Table(
      ("dataByte", "isOn"),
      (0, false),
      (63, false),
      (64, true),
      (127, true)
    )

    forAll(dataBytes) { (dataByte, isOn) =>
      // When / Then
      shortMsgC(ShortMessage.CONTROL_CHANGE, 2, LocalControlMidiMsg.Number, dataByte).asScala shouldEqual
        LocalControlMidiMsg(2, isOn)
    }
  }

  it should "keep a Control Change below the Channel Mode range a CcMidiMsg" in {
    // When / Then
    shortMsgC(ShortMessage.CONTROL_CHANGE, 2, MidiRequirements.MaxControllerNumber, 100).asScala shouldEqual
      CcMidiMsg(2, MidiRequirements.MaxControllerNumber, 100)
  }

  it should "wrap a Mono Mode On carrying more channels than MIDI 1.0 allows in an UnsupportedMidiMsg" in {
    // Given
    val javaMessage = shortMsgC(ShortMessage.CONTROL_CHANGE, 2, MonoModeOnMidiMsg.Number, 17)

    // When
    val actual = javaMessage.asScala

    // Then — asScala runs on the device's own thread, so a malformed message must not throw out of it
    actual shouldEqual UnsupportedMidiMsg(ArraySeq.unsafeWrapArray(javaMessage.getMessage))
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
    val javaMessage = unsupported.asJava

    // Then
    javaMessage shouldBe a[SysexMessage]
    javaMessage.getMessage should equal(sysexBytes)
  }

  behavior of "JavaMidiConverters.connectionLimit"

  it should "map Java Sound's -1 to Unlimited and any other count to Limited" in {
    // Given
    val cases = Table[Int, MidiConnectionLimit](
      ("javaMaxConnections", "expected"),
      (-1, MidiConnectionLimit.Unlimited),
      (0, MidiConnectionLimit.Limited(0)),
      (1, MidiConnectionLimit.Limited(1)),
      (8, MidiConnectionLimit.Limited(8))
    )

    forAll(cases) { (javaMaxConnections, expected) =>
      // When / Then
      JavaMidiConverters.connectionLimit(javaMaxConnections) shouldEqual expected
    }
  }

  behavior of "JavaMidiConverters.asMidiDeviceId"

  it should "take the name and vendor of the Java Sound device info" in {
    // Given
    val javaInfo = TestDeviceInfo("CoreMIDI4J - FP-90", "Roland", "Digital piano", "1.0")

    // When / Then
    javaInfo.asMidiDeviceId shouldEqual MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  }

  behavior of "JavaMidiConverters.asMidiDeviceInfo"

  it should "copy the Java Sound device info fields and convert the connection limits" in {
    // Given
    val device = stub[MidiDevice]
    (() => device.getDeviceInfo).when().returns(TestDeviceInfo("CoreMIDI4J - FP-90", "Roland", "Digital piano", "1.0"))
    (() => device.getMaxTransmitters).when().returns(-1)
    (() => device.getMaxReceivers).when().returns(1)

    // When
    val info = device.asMidiDeviceInfo

    // Then
    info shouldEqual MidiDeviceInfo(
      name = "CoreMIDI4J - FP-90",
      vendor = "Roland",
      description = "Digital piano",
      version = "1.0",
      transmittersLimit = MidiConnectionLimit.Unlimited,
      receiversLimit = MidiConnectionLimit.Limited(1)
    )
    info.id shouldEqual MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  }

  behavior of "JavaMidiConverters.asJava availability"

  it should "be defined for Midi1Msg but not for Midi2Msg" in {
    // When / Then
    typeChecks("(??? : Midi1Msg).asJava") shouldBe true
    typeChecks("(??? : Midi2Msg).asJava") shouldBe false
    // Proves Midi2Msg resolves in this file, so the assertion above fails for the right reason.
    typeChecks("val m: Midi2Msg = ???") shouldBe true
  }
}
