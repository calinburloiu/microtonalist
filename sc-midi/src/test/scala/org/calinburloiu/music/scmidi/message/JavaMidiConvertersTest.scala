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
import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import javax.sound.midi.{MetaMessage, MidiMessage, ShortMessage, SysexMessage}
import scala.collection.immutable.ArraySeq

class JavaMidiConvertersTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers {

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

  private val cases = Table[ScMidiMessage, MidiMessage](
    ("ScMidiMessage", "Java MidiMessage"),
    // Channel Voice
    (NoteOnScMidiMessage(5, MidiNote(62), 102), shortMsgC(ShortMessage.NOTE_ON, 5, 62, 102)),
    (NoteOffScMidiMessage(5, MidiNote(62), 102), shortMsgC(ShortMessage.NOTE_OFF, 5, 62, 102)),
    (PolyPressureScMidiMessage(2, MidiNote(60), 80), shortMsgC(ShortMessage.POLY_PRESSURE, 2, 60, 80)),
    (CcScMidiMessage(15, 67, 64), shortMsgC(ShortMessage.CONTROL_CHANGE, 15, 67, 64)),
    (ProgramChangeScMidiMessage(1, 42), shortMsgC(ShortMessage.PROGRAM_CHANGE, 1, 42, 0)),
    (ChannelPressureScMidiMessage(3, 100), shortMsgC(ShortMessage.CHANNEL_PRESSURE, 3, 100, 0)),
    (PitchBendScMidiMessage(3, 0), shortMsgC(ShortMessage.PITCH_BEND, 3, 0x00, 0x40)),
    (PitchBendScMidiMessage(3, -8192), shortMsgC(ShortMessage.PITCH_BEND, 3, 0x00, 0x00)),
    (PitchBendScMidiMessage(3, 8191), shortMsgC(ShortMessage.PITCH_BEND, 3, 0x7F, 0x7F)),
    (PitchBendScMidiMessage(3, 1050), shortMsgC(ShortMessage.PITCH_BEND, 3, 0x1A, 0x48)),
    // System Common
    (MidiTimeCodeScMidiMessage(3, 5), shortMsg(ShortMessage.MIDI_TIME_CODE, (3 << 4) | 5, 0)),
    (SongPositionPointerScMidiMessage(1000), shortMsg(ShortMessage.SONG_POSITION_POINTER, 1000 & 0x7F, (1000 >> 7) & 0x7F)),
    (SongSelectScMidiMessage(7), shortMsg(ShortMessage.SONG_SELECT, 7, 0)),
    (TuneRequestScMidiMessage, new ShortMessage(ShortMessage.TUNE_REQUEST)),
    // System Real-Time
    (TimingClockScMidiMessage, new ShortMessage(ShortMessage.TIMING_CLOCK)),
    (StartScMidiMessage, new ShortMessage(ShortMessage.START)),
    (ContinueScMidiMessage, new ShortMessage(ShortMessage.CONTINUE)),
    (StopScMidiMessage, new ShortMessage(ShortMessage.STOP)),
    (ActiveSensingScMidiMessage, new ShortMessage(ShortMessage.ACTIVE_SENSING)),
    (SystemResetScMidiMessage, new ShortMessage(ShortMessage.SYSTEM_RESET)),
    // Sysex
    (SysExScMidiMessage(ArraySeq.unsafeWrapArray(sysexBytes)), new SysexMessage(sysexBytes, sysexBytes.length)),
    // Meta
    (SequenceNumberMetaScMidiMessage(0x1234), metaMsg(0x00, Array(0x12.toByte, 0x34.toByte))),
    (TextMetaScMidiMessage("hello"), metaMsg(0x01, textBytes("hello"))),
    (CopyrightNoticeMetaScMidiMessage("(c) 2026"), metaMsg(0x02, textBytes("(c) 2026"))),
    (TrackNameMetaScMidiMessage("Track 1"), metaMsg(0x03, textBytes("Track 1"))),
    (InstrumentNameMetaScMidiMessage("Piano"), metaMsg(0x04, textBytes("Piano"))),
    (LyricMetaScMidiMessage("la"), metaMsg(0x05, textBytes("la"))),
    (MarkerMetaScMidiMessage("A"), metaMsg(0x06, textBytes("A"))),
    (CuePointMetaScMidiMessage("cue"), metaMsg(0x07, textBytes("cue"))),
    (ProgramNameMetaScMidiMessage("Prog"), metaMsg(0x08, textBytes("Prog"))),
    (DeviceNameMetaScMidiMessage("Dev"), metaMsg(0x09, textBytes("Dev"))),
    (MidiChannelPrefixMetaScMidiMessage(9), metaMsg(0x20, Array(9.toByte))),
    (MidiPortMetaScMidiMessage(3), metaMsg(0x21, Array(3.toByte))),
    (EndOfTrackMetaScMidiMessage, metaMsg(0x2F, Array.emptyByteArray)),
    (SetTempoMetaScMidiMessage(500000), metaMsg(0x51, Array(0x07.toByte, 0xA1.toByte, 0x20.toByte))),
    (
      SmpteOffsetMetaScMidiMessage(1, 2, 3, 4, 5),
      metaMsg(0x54, Array(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte))
    ),
    (
      TimeSignatureMetaScMidiMessage(4, 2, 24, 8),
      metaMsg(0x58, Array(4.toByte, 2.toByte, 24.toByte, 8.toByte))
    ),
    (
      KeySignatureMetaScMidiMessage(-3, ScMidiKeySignatureMode.Minor),
      metaMsg(0x59, Array((-3).toByte, 1.toByte))
    ),
    (
      KeySignatureMetaScMidiMessage(2, ScMidiKeySignatureMode.Major),
      metaMsg(0x59, Array(2.toByte, 0.toByte))
    ),
    (
      SequencerSpecificMetaScMidiMessage(ArraySeq(0x00.toByte, 0x12.toByte, 0x34.toByte)),
      metaMsg(0x7F, Array(0x00.toByte, 0x12.toByte, 0x34.toByte))
    )
  )

  behavior of "JavaMidiConverters.asJava"

  it should "produce Java bytes equal to the expected Java MidiMessage for every ScMidiMessage subtype" in {
    forAll(cases) { (scMsg, javaMsg) =>
      // When
      val actual = scMsg.asJava

      // Then
      actual.getMessage should equal(javaMsg.getMessage)
    }
  }

  behavior of "JavaMidiConverters.asScala"

  it should "produce the expected ScMidiMessage for every Java MidiMessage" in {
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

  behavior of "UnsupportedScMidiMessage round-trip"

  it should "round-trip a ShortMessage with an unknown command" in {
    // Given: channel voice command that's not in FromShortMap — there is none; use a system common not recognized.
    // All defined commands are registered, so craft a 2-byte status for a rarely seen value by using a short
    // 1-byte status under 0xF0 won't work (handled as command). Use an unused real-time status: 0xF9 (undefined).
    val msg = new ShortMessage()
    msg.setMessage(0xF9)

    // When
    val sc = msg.asScala
    val back = sc.asJava

    // Then
    sc shouldBe a[UnsupportedScMidiMessage]
    back.getMessage should equal(msg.getMessage)
  }

  it should "round-trip a MetaMessage with an unknown meta type" in {
    // Given: meta type 0x60 is not registered
    val payload = Array[Byte](0x01, 0x02, 0x03)
    val msg = new MetaMessage()
    msg.setMessage(0x60, payload, payload.length)

    // When
    val sc = msg.asScala
    val back = sc.asJava

    // Then
    sc shouldBe a[UnsupportedScMidiMessage]
    back.getMessage should equal(msg.getMessage)
  }

  it should "round-trip a SysexMessage via UnsupportedScMidiMessage constructed from raw bytes" in {
    // Given
    val unsupported = UnsupportedScMidiMessage(ArraySeq.unsafeWrapArray(sysexBytes))

    // When
    val javaMsg = unsupported.asJava

    // Then
    javaMsg shouldBe a[SysexMessage]
    javaMsg.getMessage should equal(sysexBytes)
  }
}
