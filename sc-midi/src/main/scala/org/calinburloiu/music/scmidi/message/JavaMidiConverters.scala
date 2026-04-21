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

import javax.sound.midi.{MetaMessage, MidiMessage, ShortMessage, SysexMessage}
import scala.collection.immutable.ArraySeq

/**
 * Bidirectional converters between [[ScMidiMessage]] and [[javax.sound.midi.MidiMessage]] modelled after
 * [[scala.jdk.CollectionConverters]].
 *
 * Import the members of this object to enable the `asJava` and `asScala` extension methods:
 *
 * {{{
 *   import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*
 *
 *   val java: MidiMessage = NoteOnScMidiMessage(0, 60, 100).asJava
 *   val scala: ScMidiMessage = java.asScala
 * }}}
 *
 * Both directions dispatch through lookup tables: `asJava` by concrete subtype [[Class]] (cheaper than pattern
 * matching on a closed sealed hierarchy of 30+ cases), `asScala` by MIDI command / status / meta-type byte.
 */
object JavaMidiConverters {
  /**
   * The string encoding used by text-bearing SMF meta events. The SMF spec formally specifies ASCII, but 8-bit bytes
   * are used in practice; ISO-8859-1 round-trips every byte losslessly.
   */
  private val TextEncoding: String = "ISO-8859-1"

  extension (message: ScMidiMessage) {
    /** Converts this [[ScMidiMessage]] into the equivalent [[javax.sound.midi.MidiMessage]]. */
    def asJava: MidiMessage = {
      val builder = ToJavaMap.getOrElse(
        message.getClass,
        throw new IllegalStateException(s"No Java MIDI message builder registered for ${message.getClass}")
      )
      builder(message)
    }
  }

  extension (message: MidiMessage) {
    /**
     * Converts this [[javax.sound.midi.MidiMessage]] into the corresponding [[ScMidiMessage]] subtype.
     *
     * Supported message types are mapped to their Scala-idiomatic counterparts. Unrecognised messages are wrapped in
     * [[UnsupportedScMidiMessage]] holding the raw bytes.
     */
    def asScala: ScMidiMessage = {
      require(message != null, "message must not be null")

      message match {
        case shortMessage: ShortMessage =>
          val status = shortMessage.getStatus
          val key = if (status >= 0xF0) status else shortMessage.getCommand
          FromShortMap(key)(shortMessage)
        case sysexMessage: SysexMessage =>
          SysExScMidiMessage(ArraySeq.unsafeWrapArray(sysexMessage.getMessage))
        case metaMessage: MetaMessage =>
          FromMetaMap(metaMessage.getType)(metaMessage)
        case _ => toUnsupported(message)
      }
    }
  }

  // ============================================================================
  // ScMidiMessage -> MidiMessage dispatch
  // ============================================================================

  private def entry[M <: ScMidiMessage](cls: Class[M])(build: M => MidiMessage): (Class[?], ScMidiMessage => MidiMessage) =
    (cls, (m: ScMidiMessage) => build(m.asInstanceOf[M]))

  private val ToJavaMap: Map[Class[?], ScMidiMessage => MidiMessage] = Map[Class[?], ScMidiMessage => MidiMessage](
    entry(classOf[NoteOnScMidiMessage]) { m =>
      new ShortMessage(ShortMessage.NOTE_ON, m.channel, m.midiNote.number, m.velocity)
    },
    entry(classOf[NoteOffScMidiMessage]) { m =>
      new ShortMessage(ShortMessage.NOTE_OFF, m.channel, m.midiNote.number, m.velocity)
    },
    entry(classOf[PolyPressureScMidiMessage]) { m =>
      new ShortMessage(ShortMessage.POLY_PRESSURE, m.channel, m.midiNote.number, m.value)
    },
    entry(classOf[CcScMidiMessage]) { m =>
      new ShortMessage(ShortMessage.CONTROL_CHANGE, m.channel, m.number, m.value)
    },
    entry(classOf[ProgramChangeScMidiMessage]) { m =>
      new ShortMessage(ShortMessage.PROGRAM_CHANGE, m.channel, m.program, 0)
    },
    entry(classOf[ChannelPressureScMidiMessage]) { m =>
      new ShortMessage(ShortMessage.CHANNEL_PRESSURE, m.channel, m.value, 0)
    },
    entry(classOf[PitchBendScMidiMessage]) { m =>
      val (data1, data2) = PitchBendScMidiMessage.convertValueToDataBytes(m.value)
      new ShortMessage(ShortMessage.PITCH_BEND, m.channel, data1, data2)
    },
    entry(classOf[MidiTimeCodeScMidiMessage]) { m =>
      new ShortMessage(ShortMessage.MIDI_TIME_CODE, (m.messageType << 4) | m.values, 0)
    },
    entry(classOf[SongPositionPointerScMidiMessage]) { m =>
      val lsb = m.position & 0x7F
      val msb = (m.position >> 7) & 0x7F
      new ShortMessage(ShortMessage.SONG_POSITION_POINTER, lsb, msb)
    },
    entry(classOf[SongSelectScMidiMessage]) { m =>
      new ShortMessage(ShortMessage.SONG_SELECT, m.song, 0)
    },
    entry(TuneRequestScMidiMessage.getClass) { _ => new ShortMessage(ShortMessage.TUNE_REQUEST) },
    entry(TimingClockScMidiMessage.getClass) { _ => new ShortMessage(ShortMessage.TIMING_CLOCK) },
    entry(StartScMidiMessage.getClass) { _ => new ShortMessage(ShortMessage.START) },
    entry(ContinueScMidiMessage.getClass) { _ => new ShortMessage(ShortMessage.CONTINUE) },
    entry(StopScMidiMessage.getClass) { _ => new ShortMessage(ShortMessage.STOP) },
    entry(ActiveSensingScMidiMessage.getClass) { _ => new ShortMessage(ShortMessage.ACTIVE_SENSING) },
    entry(SystemResetScMidiMessage.getClass) { _ => new ShortMessage(ShortMessage.SYSTEM_RESET) },
    entry(classOf[SysExScMidiMessage]) { m =>
      val bytes = m.data.toArray
      new SysexMessage(bytes, bytes.length)
    },
    entry(classOf[SequenceNumberMetaScMidiMessage]) { m =>
      buildMeta(SequenceNumberMetaScMidiMessage.MetaType, bigEndian(m.number, 2))
    },
    entry(classOf[TextMetaScMidiMessage]) { m => buildTextMeta(TextMetaScMidiMessage.MetaType, m.text) },
    entry(classOf[CopyrightNoticeMetaScMidiMessage]) { m =>
      buildTextMeta(CopyrightNoticeMetaScMidiMessage.MetaType, m.text)
    },
    entry(classOf[TrackNameMetaScMidiMessage]) { m => buildTextMeta(TrackNameMetaScMidiMessage.MetaType, m.name) },
    entry(classOf[InstrumentNameMetaScMidiMessage]) { m =>
      buildTextMeta(InstrumentNameMetaScMidiMessage.MetaType, m.name)
    },
    entry(classOf[LyricMetaScMidiMessage]) { m => buildTextMeta(LyricMetaScMidiMessage.MetaType, m.text) },
    entry(classOf[MarkerMetaScMidiMessage]) { m => buildTextMeta(MarkerMetaScMidiMessage.MetaType, m.text) },
    entry(classOf[CuePointMetaScMidiMessage]) { m => buildTextMeta(CuePointMetaScMidiMessage.MetaType, m.text) },
    entry(classOf[ProgramNameMetaScMidiMessage]) { m =>
      buildTextMeta(ProgramNameMetaScMidiMessage.MetaType, m.name)
    },
    entry(classOf[DeviceNameMetaScMidiMessage]) { m => buildTextMeta(DeviceNameMetaScMidiMessage.MetaType, m.name) },
    entry(classOf[MidiChannelPrefixMetaScMidiMessage]) { m =>
      buildMeta(MidiChannelPrefixMetaScMidiMessage.MetaType, Array(m.channel.toByte))
    },
    entry(classOf[MidiPortMetaScMidiMessage]) { m =>
      buildMeta(MidiPortMetaScMidiMessage.MetaType, Array(m.port.toByte))
    },
    entry(EndOfTrackMetaScMidiMessage.getClass) { _ =>
      buildMeta(EndOfTrackMetaScMidiMessage.MetaType, Array.emptyByteArray)
    },
    entry(classOf[SetTempoMetaScMidiMessage]) { m =>
      buildMeta(SetTempoMetaScMidiMessage.MetaType, bigEndian(m.microsecondsPerQuarterNote, 3))
    },
    entry(classOf[SmpteOffsetMetaScMidiMessage]) { m =>
      buildMeta(
        SmpteOffsetMetaScMidiMessage.MetaType,
        Array(m.hour.toByte, m.minute.toByte, m.second.toByte, m.frame.toByte, m.fractionalFrame.toByte)
      )
    },
    entry(classOf[TimeSignatureMetaScMidiMessage]) { m =>
      buildMeta(
        TimeSignatureMetaScMidiMessage.MetaType,
        Array(
          m.numerator.toByte,
          m.denominatorPowerOf2.toByte,
          m.midiClocksPerMetronomeTick.toByte,
          m.thirtySecondNotesPer24MidiClocks.toByte
        )
      )
    },
    entry(classOf[KeySignatureMetaScMidiMessage]) { m =>
      buildMeta(
        KeySignatureMetaScMidiMessage.MetaType,
        Array(m.sharpsOrFlats.toByte, (if (m.mode == ScMidiKeySignatureMode.Minor) 1 else 0).toByte)
      )
    },
    entry(classOf[SequencerSpecificMetaScMidiMessage]) { m =>
      buildMeta(SequencerSpecificMetaScMidiMessage.MetaType, m.data.toArray)
    },
    entry(classOf[UnsupportedScMidiMessage]) { m => reconstructUnsupported(m.data) }
  )

  // ============================================================================
  // MidiMessage -> ScMidiMessage dispatch
  // ============================================================================

  private val FromShortMap: Map[Int, ShortMessage => ScMidiMessage] = Map[Int, ShortMessage => ScMidiMessage](
    ShortMessage.NOTE_ON -> { s =>
      NoteOnScMidiMessage(s.getChannel, MidiNote(s.getData1), s.getData2)
    },
    ShortMessage.NOTE_OFF -> { s =>
      NoteOffScMidiMessage(s.getChannel, MidiNote(s.getData1), s.getData2)
    },
    ShortMessage.POLY_PRESSURE -> { s =>
      PolyPressureScMidiMessage(s.getChannel, MidiNote(s.getData1), s.getData2)
    },
    ShortMessage.CONTROL_CHANGE -> { s =>
      CcScMidiMessage(s.getChannel, s.getData1, s.getData2)
    },
    ShortMessage.PROGRAM_CHANGE -> { s =>
      ProgramChangeScMidiMessage(s.getChannel, s.getData1)
    },
    ShortMessage.CHANNEL_PRESSURE -> { s =>
      ChannelPressureScMidiMessage(s.getChannel, s.getData1)
    },
    ShortMessage.PITCH_BEND -> { s =>
      PitchBendScMidiMessage(
        s.getChannel,
        PitchBendScMidiMessage.convertDataBytesToValue(s.getData1, s.getData2)
      )
    },
    ShortMessage.MIDI_TIME_CODE -> { s =>
      val d = s.getData1
      MidiTimeCodeScMidiMessage((d >> 4) & 0x07, d & 0x0F)
    },
    ShortMessage.SONG_POSITION_POINTER -> { s =>
      SongPositionPointerScMidiMessage((s.getData2 << 7) | s.getData1)
    },
    ShortMessage.SONG_SELECT -> { s => SongSelectScMidiMessage(s.getData1) },
    ShortMessage.TUNE_REQUEST -> { _ => TuneRequestScMidiMessage },
    ShortMessage.TIMING_CLOCK -> { _ => TimingClockScMidiMessage },
    ShortMessage.START -> { _ => StartScMidiMessage },
    ShortMessage.CONTINUE -> { _ => ContinueScMidiMessage },
    ShortMessage.STOP -> { _ => StopScMidiMessage },
    ShortMessage.ACTIVE_SENSING -> { _ => ActiveSensingScMidiMessage },
    ShortMessage.SYSTEM_RESET -> { _ => SystemResetScMidiMessage }
  ).withDefaultValue(toUnsupported)

  private val FromMetaMap: Map[Int, MetaMessage => ScMidiMessage] = Map[Int, MetaMessage => ScMidiMessage](
    SequenceNumberMetaScMidiMessage.MetaType -> { m =>
      SequenceNumberMetaScMidiMessage(fromBigEndian(m.getData))
    },
    TextMetaScMidiMessage.MetaType -> { m => TextMetaScMidiMessage(decodeText(m.getData)) },
    CopyrightNoticeMetaScMidiMessage.MetaType -> { m => CopyrightNoticeMetaScMidiMessage(decodeText(m.getData)) },
    TrackNameMetaScMidiMessage.MetaType -> { m => TrackNameMetaScMidiMessage(decodeText(m.getData)) },
    InstrumentNameMetaScMidiMessage.MetaType -> { m => InstrumentNameMetaScMidiMessage(decodeText(m.getData)) },
    LyricMetaScMidiMessage.MetaType -> { m => LyricMetaScMidiMessage(decodeText(m.getData)) },
    MarkerMetaScMidiMessage.MetaType -> { m => MarkerMetaScMidiMessage(decodeText(m.getData)) },
    CuePointMetaScMidiMessage.MetaType -> { m => CuePointMetaScMidiMessage(decodeText(m.getData)) },
    ProgramNameMetaScMidiMessage.MetaType -> { m => ProgramNameMetaScMidiMessage(decodeText(m.getData)) },
    DeviceNameMetaScMidiMessage.MetaType -> { m => DeviceNameMetaScMidiMessage(decodeText(m.getData)) },
    MidiChannelPrefixMetaScMidiMessage.MetaType -> { m =>
      MidiChannelPrefixMetaScMidiMessage(m.getData()(0) & 0xFF)
    },
    MidiPortMetaScMidiMessage.MetaType -> { m => MidiPortMetaScMidiMessage(m.getData()(0) & 0xFF) },
    EndOfTrackMetaScMidiMessage.MetaType -> { _ => EndOfTrackMetaScMidiMessage },
    SetTempoMetaScMidiMessage.MetaType -> { m => SetTempoMetaScMidiMessage(fromBigEndian(m.getData)) },
    SmpteOffsetMetaScMidiMessage.MetaType -> { m =>
      val d = m.getData
      SmpteOffsetMetaScMidiMessage(d(0) & 0xFF, d(1) & 0xFF, d(2) & 0xFF, d(3) & 0xFF, d(4) & 0xFF)
    },
    TimeSignatureMetaScMidiMessage.MetaType -> { m =>
      val d = m.getData
      TimeSignatureMetaScMidiMessage(d(0) & 0xFF, d(1) & 0xFF, d(2) & 0xFF, d(3) & 0xFF)
    },
    KeySignatureMetaScMidiMessage.MetaType -> { m =>
      val d = m.getData
      val sharps = d(0).toInt
      val mode =
        if ((d(1) & 0xFF) == 1) ScMidiKeySignatureMode.Minor else ScMidiKeySignatureMode.Major
      KeySignatureMetaScMidiMessage(sharps, mode)
    },
    SequencerSpecificMetaScMidiMessage.MetaType -> { m =>
      SequencerSpecificMetaScMidiMessage(ArraySeq.unsafeWrapArray(m.getData))
    }
  ).withDefaultValue(toUnsupported)

  private def toUnsupported(message: MidiMessage): UnsupportedScMidiMessage =
    UnsupportedScMidiMessage(ArraySeq.unsafeWrapArray(message.getMessage))

  // ============================================================================
  // Helpers
  // ============================================================================

  private def buildMeta(metaType: Int, data: Array[Byte]): MetaMessage = {
    val msg = new MetaMessage()
    msg.setMessage(metaType, data, data.length)
    msg
  }

  private def buildTextMeta(metaType: Int, text: String): MetaMessage =
    buildMeta(metaType, text.getBytes(TextEncoding))

  private def decodeText(data: Array[Byte]): String = new String(data, TextEncoding)

  private[message] def bigEndian(value: Int, numBytes: Int): Array[Byte] = {
    val bytes = new Array[Byte](numBytes)
    var i = 0
    while (i < numBytes) {
      bytes(i) = ((value >> (8 * (numBytes - 1 - i))) & 0xFF).toByte
      i += 1
    }
    bytes
  }

  private[message] def fromBigEndian(bytes: Array[Byte]): Int = {
    var value = 0
    var i = 0
    while (i < bytes.length) {
      value = (value << 8) | (bytes(i) & 0xFF)
      i += 1
    }
    value
  }

  /**
   * Reconstructs the original Java [[MidiMessage]] from the raw bytes stored in an [[UnsupportedScMidiMessage]].
   *
   * The kind of message (`ShortMessage` / `SysexMessage` / `MetaMessage`) is detected from the status byte.
   * `MetaMessage` payloads use the SMF format with a variable-length quantity (VLQ) encoded length.
   */
  private def reconstructUnsupported(data: ArraySeq[Byte]): MidiMessage = {
    val bytes = data.toArray
    require(bytes.nonEmpty, "UnsupportedScMidiMessage data must not be empty")
    val status = bytes(0) & 0xFF
    status match {
      case 0xF0 | 0xF7 =>
        new SysexMessage(bytes, bytes.length)
      case 0xFF =>
        val metaType = bytes(1) & 0x7F
        val (length, lengthSize) = readVlq(bytes, 2)
        val payloadStart = 2 + lengthSize
        val payload = new Array[Byte](length)
        System.arraycopy(bytes, payloadStart, payload, 0, length)
        buildMeta(metaType, payload)
      case _ =>
        val msg = new ShortMessage()
        bytes.length match {
          case 1 => msg.setMessage(status)
          case 2 => msg.setMessage(status, bytes(1) & 0xFF, 0)
          case _ => msg.setMessage(status, bytes(1) & 0xFF, bytes(2) & 0xFF)
        }
        msg
    }
  }

  /** Reads a variable-length quantity from `bytes` starting at `offset`. Returns `(value, bytesRead)`. */
  private def readVlq(bytes: Array[Byte], offset: Int): (Int, Int) = {
    var value = 0
    var i = offset
    var done = false
    while (!done && i < bytes.length) {
      val b = bytes(i) & 0xFF
      value = (value << 7) | (b & 0x7F)
      i += 1
      if ((b & 0x80) == 0) done = true
    }
    (value, i - offset)
  }
}
