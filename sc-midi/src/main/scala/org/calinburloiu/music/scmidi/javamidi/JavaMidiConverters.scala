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
import org.calinburloiu.music.scmidi.message.*

import javax.sound.midi.{MetaMessage, MidiDevice, MidiMessage, ShortMessage, SysexMessage}
import scala.collection.immutable.ArraySeq

/**
 * Bidirectional converters between [[Midi1Msg]] / [[MidiMsg]] and [[javax.sound.midi.MidiMessage]] modelled after
 * [[scala.jdk.CollectionConverters]].
 *
 * It also builds the device-level API values from Java Sound: `device.asMidiDeviceInfo`, `info.asMidiDeviceId` and
 * [[connectionLimit]].
 *
 * Import the members of this object to enable the `asJava` and `asScala` extension methods:
 *
 * {{{
 *   import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
 *
 *   val java: MidiMessage = NoteOnMidiMsg(0, 60, 100).asJava
 *   val scala: MidiMsg = java.asScala
 * }}}
 *
 * Both directions dispatch through lookup tables: `asJava` by concrete subtype [[Class]] (cheaper than pattern
 * matching on a closed sealed hierarchy of 30+ cases), `asScala` by MIDI command / status / meta-type byte.
 * A Control Change is the one status byte both tables split further: its controller number decides between a
 * [[CcMidiMsg]] and a [[ChannelModeMidiMsg]] subtype.
 */
object JavaMidiConverters {
  /**
   * The string encoding used by text-bearing SMF meta events. The SMF spec formally specifies ASCII, but 8-bit bytes
   * are used in practice; ISO-8859-1 round-trips every byte losslessly.
   */
  private val TextEncoding: String = "ISO-8859-1"

  extension (message: Midi1Msg) {
    /** Converts this [[Midi1Msg]] into the equivalent [[javax.sound.midi.MidiMessage]]. */
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
     * Converts this [[javax.sound.midi.MidiMessage]] into the corresponding [[MidiMsg]] subtype.
     *
     * Supported message types are mapped to their Scala-idiomatic counterparts. Unrecognised messages are wrapped in
     * [[UnsupportedMidiMsg]] holding the raw bytes.
     */
    def asScala: MidiMsg = {
      require(message != null, "message must not be null")

      message match {
        case shortMessage: ShortMessage =>
          val status = shortMessage.getStatus
          val key = if (status >= 0xF0) status else shortMessage.getCommand
          FromShortMap(key)(shortMessage)
        case sysexMessage: SysexMessage =>
          SysExMidiMsg(ArraySeq.unsafeWrapArray(sysexMessage.getMessage))
        case metaMessage: MetaMessage =>
          FromMetaMap(metaMessage.getType)(metaMessage)
        case _ => toUnsupported(message)
      }
    }
  }

  /**
   * Converts a Java Sound connection count into a [[MidiConnectionLimit]]: `-1`, Java Sound's encoding of
   * "unlimited", becomes [[MidiConnectionLimit.Unlimited]]; any other count becomes [[MidiConnectionLimit.Limited]].
   *
   * @param javaMaxConnections the value of `MidiDevice.getMaxTransmitters` or `MidiDevice.getMaxReceivers`.
   */
  def connectionLimit(javaMaxConnections: Int): MidiConnectionLimit = {
    if (javaMaxConnections == -1) MidiConnectionLimit.Unlimited else MidiConnectionLimit.Limited(javaMaxConnections)
  }

  extension (info: MidiDevice.Info) {
    /** The [[MidiDeviceId]] of the device this Java Sound info describes: its name and vendor. */
    def asMidiDeviceId: MidiDeviceId = MidiDeviceId(info.getName, info.getVendor)
  }

  extension (device: MidiDevice) {
    /**
     * Builds the [[MidiDeviceInfo]] of this Java Sound device. It needs the device rather than its
     * `MidiDevice.Info` alone, because the connection limits come from `getMaxTransmitters` / `getMaxReceivers`.
     */
    def asMidiDeviceInfo: MidiDeviceInfo = {
      val info = device.getDeviceInfo
      MidiDeviceInfo(
        name = info.getName,
        vendor = info.getVendor,
        description = info.getDescription,
        version = info.getVersion,
        maxTransmitters = connectionLimit(device.getMaxTransmitters),
        maxReceivers = connectionLimit(device.getMaxReceivers)
      )
    }
  }

  // ============================================================================
  // Midi1Msg -> MidiMessage dispatch
  // ============================================================================

  private def entry[M <: Midi1Msg](cls: Class[M])(build: M => MidiMessage): (Class[?], Midi1Msg => MidiMessage) =
    (cls, (m: Midi1Msg) => build(m.asInstanceOf[M]))

  private val ToJavaMap: Map[Class[?], Midi1Msg => MidiMessage] = Map[Class[?], Midi1Msg => MidiMessage](
    entry(classOf[NoteOnMidiMsg]) { m =>
      new ShortMessage(ShortMessage.NOTE_ON, m.channel, m.midiNote.number, m.velocity)
    },
    entry(classOf[NoteOffMidiMsg]) { m =>
      new ShortMessage(ShortMessage.NOTE_OFF, m.channel, m.midiNote.number, m.velocity)
    },
    entry(classOf[PolyPressureMidiMsg]) { m =>
      new ShortMessage(ShortMessage.POLY_PRESSURE, m.channel, m.midiNote.number, m.value)
    },
    entry(classOf[CcMidiMsg]) { m =>
      new ShortMessage(ShortMessage.CONTROL_CHANGE, m.channel, m.number, m.value)
    },
    entry(classOf[AllSoundOffMidiMsg]) { m => channelModeMessage(m.channel, AllSoundOffMidiMsg.Number) },
    entry(classOf[ResetAllControllersMidiMsg]) { m =>
      channelModeMessage(m.channel, ResetAllControllersMidiMsg.Number)
    },
    entry(classOf[LocalControlMidiMsg]) { m =>
      val dataByte = if (m.isOn) LocalControlMidiMsg.OnValue else LocalControlMidiMsg.OffValue
      channelModeMessage(m.channel, LocalControlMidiMsg.Number, dataByte)
    },
    entry(classOf[AllNotesOffMidiMsg]) { m => channelModeMessage(m.channel, AllNotesOffMidiMsg.Number) },
    entry(classOf[OmniModeOffMidiMsg]) { m => channelModeMessage(m.channel, OmniModeOffMidiMsg.Number) },
    entry(classOf[OmniModeOnMidiMsg]) { m => channelModeMessage(m.channel, OmniModeOnMidiMsg.Number) },
    entry(classOf[MonoModeOnMidiMsg]) { m =>
      channelModeMessage(m.channel, MonoModeOnMidiMsg.Number, m.channelCount)
    },
    entry(classOf[PolyModeOnMidiMsg]) { m => channelModeMessage(m.channel, PolyModeOnMidiMsg.Number) },
    entry(classOf[ProgramChangeMidiMsg]) { m =>
      new ShortMessage(ShortMessage.PROGRAM_CHANGE, m.channel, m.program, 0)
    },
    entry(classOf[ChannelPressureMidiMsg]) { m =>
      new ShortMessage(ShortMessage.CHANNEL_PRESSURE, m.channel, m.value, 0)
    },
    entry(classOf[PitchBendMidiMsg]) { m =>
      val (data1, data2) = PitchBendMidiMsg.convertValueToDataBytes(m.value)
      new ShortMessage(ShortMessage.PITCH_BEND, m.channel, data1, data2)
    },
    entry(classOf[MidiTimeCodeMidiMsg]) { m =>
      new ShortMessage(ShortMessage.MIDI_TIME_CODE, (m.messageType << 4) | m.values, 0)
    },
    entry(classOf[SongPositionPointerMidiMsg]) { m =>
      val lsb = m.position & 0x7F
      val msb = (m.position >> 7) & 0x7F
      new ShortMessage(ShortMessage.SONG_POSITION_POINTER, lsb, msb)
    },
    entry(classOf[SongSelectMidiMsg]) { m =>
      new ShortMessage(ShortMessage.SONG_SELECT, m.song, 0)
    },
    entry(TuneRequestMidiMsg.getClass) { _ => new ShortMessage(ShortMessage.TUNE_REQUEST) },
    entry(TimingClockMidiMsg.getClass) { _ => new ShortMessage(ShortMessage.TIMING_CLOCK) },
    entry(StartMidiMsg.getClass) { _ => new ShortMessage(ShortMessage.START) },
    entry(ContinueMidiMsg.getClass) { _ => new ShortMessage(ShortMessage.CONTINUE) },
    entry(StopMidiMsg.getClass) { _ => new ShortMessage(ShortMessage.STOP) },
    entry(ActiveSensingMidiMsg.getClass) { _ => new ShortMessage(ShortMessage.ACTIVE_SENSING) },
    entry(SystemResetMidiMsg.getClass) { _ => new ShortMessage(ShortMessage.SYSTEM_RESET) },
    entry(classOf[SysExMidiMsg]) { m =>
      val bytes = m.data.toArray
      new SysexMessage(bytes, bytes.length)
    },
    entry(classOf[SequenceNumberMetaMidiMsg]) { m =>
      buildMeta(SequenceNumberMetaMidiMsg.MetaType, bigEndian(m.number, 2))
    },
    entry(classOf[TextMetaMidiMsg]) { m => buildTextMeta(TextMetaMidiMsg.MetaType, m.text) },
    entry(classOf[CopyrightNoticeMetaMidiMsg]) { m =>
      buildTextMeta(CopyrightNoticeMetaMidiMsg.MetaType, m.text)
    },
    entry(classOf[TrackNameMetaMidiMsg]) { m => buildTextMeta(TrackNameMetaMidiMsg.MetaType, m.name) },
    entry(classOf[InstrumentNameMetaMidiMsg]) { m =>
      buildTextMeta(InstrumentNameMetaMidiMsg.MetaType, m.name)
    },
    entry(classOf[LyricMetaMidiMsg]) { m => buildTextMeta(LyricMetaMidiMsg.MetaType, m.text) },
    entry(classOf[MarkerMetaMidiMsg]) { m => buildTextMeta(MarkerMetaMidiMsg.MetaType, m.text) },
    entry(classOf[CuePointMetaMidiMsg]) { m => buildTextMeta(CuePointMetaMidiMsg.MetaType, m.text) },
    entry(classOf[ProgramNameMetaMidiMsg]) { m =>
      buildTextMeta(ProgramNameMetaMidiMsg.MetaType, m.name)
    },
    entry(classOf[DeviceNameMetaMidiMsg]) { m => buildTextMeta(DeviceNameMetaMidiMsg.MetaType, m.name) },
    entry(classOf[MidiChannelPrefixMetaMidiMsg]) { m =>
      buildMeta(MidiChannelPrefixMetaMidiMsg.MetaType, Array(m.channel.toByte))
    },
    entry(classOf[MidiPortMetaMidiMsg]) { m =>
      buildMeta(MidiPortMetaMidiMsg.MetaType, Array(m.port.toByte))
    },
    entry(EndOfTrackMetaMidiMsg.getClass) { _ =>
      buildMeta(EndOfTrackMetaMidiMsg.MetaType, Array.emptyByteArray)
    },
    entry(classOf[SetTempoMetaMidiMsg]) { m =>
      buildMeta(SetTempoMetaMidiMsg.MetaType, bigEndian(m.microsecondsPerQuarterNote, 3))
    },
    entry(classOf[SmpteOffsetMetaMidiMsg]) { m =>
      buildMeta(
        SmpteOffsetMetaMidiMsg.MetaType,
        Array(m.hour.toByte, m.minute.toByte, m.second.toByte, m.frame.toByte, m.fractionalFrame.toByte)
      )
    },
    entry(classOf[TimeSignatureMetaMidiMsg]) { m =>
      buildMeta(
        TimeSignatureMetaMidiMsg.MetaType,
        Array(
          m.numerator.toByte,
          m.denominatorPowerOf2.toByte,
          m.midiClocksPerMetronomeTick.toByte,
          m.thirtySecondNotesPer24MidiClocks.toByte
        )
      )
    },
    entry(classOf[KeySignatureMetaMidiMsg]) { m =>
      buildMeta(
        KeySignatureMetaMidiMsg.MetaType,
        Array(m.sharpsOrFlats.toByte, (if (m.mode == MidiKeySignatureMode.Minor) 1 else 0).toByte)
      )
    },
    entry(classOf[SequencerSpecificMetaMidiMsg]) { m =>
      buildMeta(SequencerSpecificMetaMidiMsg.MetaType, m.data.toArray)
    },
    entry(classOf[UnsupportedMidiMsg]) { m => reconstructUnsupported(m.data) }
  )

  // ============================================================================
  // MidiMessage -> MidiMsg dispatch
  // ============================================================================

  private val FromShortMap: Map[Int, ShortMessage => MidiMsg] = Map[Int, ShortMessage => MidiMsg](
    ShortMessage.NOTE_ON -> { s =>
      NoteOnMidiMsg(s.getChannel, MidiNote(s.getData1), s.getData2)
    },
    ShortMessage.NOTE_OFF -> { s =>
      NoteOffMidiMsg(s.getChannel, MidiNote(s.getData1), s.getData2)
    },
    ShortMessage.POLY_PRESSURE -> { s =>
      PolyPressureMidiMsg(s.getChannel, MidiNote(s.getData1), s.getData2)
    },
    ShortMessage.CONTROL_CHANGE -> { s => fromControlChange(s) },
    ShortMessage.PROGRAM_CHANGE -> { s =>
      ProgramChangeMidiMsg(s.getChannel, s.getData1)
    },
    ShortMessage.CHANNEL_PRESSURE -> { s =>
      ChannelPressureMidiMsg(s.getChannel, s.getData1)
    },
    ShortMessage.PITCH_BEND -> { s =>
      PitchBendMidiMsg(
        s.getChannel,
        PitchBendMidiMsg.convertDataBytesToValue(s.getData1, s.getData2)
      )
    },
    ShortMessage.MIDI_TIME_CODE -> { s =>
      val d = s.getData1
      MidiTimeCodeMidiMsg((d >> 4) & 0x07, d & 0x0F)
    },
    ShortMessage.SONG_POSITION_POINTER -> { s =>
      SongPositionPointerMidiMsg((s.getData2 << 7) | s.getData1)
    },
    ShortMessage.SONG_SELECT -> { s => SongSelectMidiMsg(s.getData1) },
    ShortMessage.TUNE_REQUEST -> { _ => TuneRequestMidiMsg },
    ShortMessage.TIMING_CLOCK -> { _ => TimingClockMidiMsg },
    ShortMessage.START -> { _ => StartMidiMsg },
    ShortMessage.CONTINUE -> { _ => ContinueMidiMsg },
    ShortMessage.STOP -> { _ => StopMidiMsg },
    ShortMessage.ACTIVE_SENSING -> { _ => ActiveSensingMidiMsg },
    ShortMessage.SYSTEM_RESET -> { _ => SystemResetMidiMsg }
  ).withDefaultValue(toUnsupported)

  private val FromMetaMap: Map[Int, MetaMessage => MidiMsg] = Map[Int, MetaMessage => MidiMsg](
    SequenceNumberMetaMidiMsg.MetaType -> { m =>
      SequenceNumberMetaMidiMsg(fromBigEndian(m.getData))
    },
    TextMetaMidiMsg.MetaType -> { m => TextMetaMidiMsg(decodeText(m.getData)) },
    CopyrightNoticeMetaMidiMsg.MetaType -> { m => CopyrightNoticeMetaMidiMsg(decodeText(m.getData)) },
    TrackNameMetaMidiMsg.MetaType -> { m => TrackNameMetaMidiMsg(decodeText(m.getData)) },
    InstrumentNameMetaMidiMsg.MetaType -> { m => InstrumentNameMetaMidiMsg(decodeText(m.getData)) },
    LyricMetaMidiMsg.MetaType -> { m => LyricMetaMidiMsg(decodeText(m.getData)) },
    MarkerMetaMidiMsg.MetaType -> { m => MarkerMetaMidiMsg(decodeText(m.getData)) },
    CuePointMetaMidiMsg.MetaType -> { m => CuePointMetaMidiMsg(decodeText(m.getData)) },
    ProgramNameMetaMidiMsg.MetaType -> { m => ProgramNameMetaMidiMsg(decodeText(m.getData)) },
    DeviceNameMetaMidiMsg.MetaType -> { m => DeviceNameMetaMidiMsg(decodeText(m.getData)) },
    MidiChannelPrefixMetaMidiMsg.MetaType -> { m =>
      MidiChannelPrefixMetaMidiMsg(m.getData()(0) & 0xFF)
    },
    MidiPortMetaMidiMsg.MetaType -> { m => MidiPortMetaMidiMsg(m.getData()(0) & 0xFF) },
    EndOfTrackMetaMidiMsg.MetaType -> { _ => EndOfTrackMetaMidiMsg },
    SetTempoMetaMidiMsg.MetaType -> { m => SetTempoMetaMidiMsg(fromBigEndian(m.getData)) },
    SmpteOffsetMetaMidiMsg.MetaType -> { m =>
      val d = m.getData
      SmpteOffsetMetaMidiMsg(d(0) & 0xFF, d(1) & 0xFF, d(2) & 0xFF, d(3) & 0xFF, d(4) & 0xFF)
    },
    TimeSignatureMetaMidiMsg.MetaType -> { m =>
      val d = m.getData
      TimeSignatureMetaMidiMsg(d(0) & 0xFF, d(1) & 0xFF, d(2) & 0xFF, d(3) & 0xFF)
    },
    KeySignatureMetaMidiMsg.MetaType -> { m =>
      val d = m.getData
      val sharps = d(0).toInt
      val mode =
        if ((d(1) & 0xFF) == 1) MidiKeySignatureMode.Minor else MidiKeySignatureMode.Major
      KeySignatureMetaMidiMsg(sharps, mode)
    },
    SequencerSpecificMetaMidiMsg.MetaType -> { m =>
      SequencerSpecificMetaMidiMsg(ArraySeq.unsafeWrapArray(m.getData))
    }
  ).withDefaultValue(toUnsupported)

  private def toUnsupported(message: MidiMessage): UnsupportedMidiMsg =
    UnsupportedMidiMsg(ArraySeq.unsafeWrapArray(message.getMessage))

  /**
   * The message a Control Change status byte carries: an ordinary [[CcMidiMsg]] below
   * [[ChannelModeMidiMsg.NumberRange]], and the matching [[ChannelModeMidiMsg]] subtype inside it.
   *
   * A Mono Mode On asking for more channels than MIDI 1.0 allows is malformed; it is wrapped in an
   * [[UnsupportedMidiMsg]] rather than rejected, because `asScala` runs on the device's own thread, where a thrown
   * exception would take the inbound stream down with it.
   */
  private def fromControlChange(shortMessage: ShortMessage): MidiMsg = {
    val channel = shortMessage.getChannel
    val number = shortMessage.getData1
    val dataByte = shortMessage.getData2

    number match {
      case AllSoundOffMidiMsg.Number => AllSoundOffMidiMsg(channel)
      case ResetAllControllersMidiMsg.Number => ResetAllControllersMidiMsg(channel)
      case LocalControlMidiMsg.Number => LocalControlMidiMsg(channel, dataByte >= LocalControlMidiMsg.OnThreshold)
      case AllNotesOffMidiMsg.Number => AllNotesOffMidiMsg(channel)
      case OmniModeOffMidiMsg.Number => OmniModeOffMidiMsg(channel)
      case OmniModeOnMidiMsg.Number => OmniModeOnMidiMsg(channel)
      case MonoModeOnMidiMsg.Number if MonoModeOnMidiMsg.ChannelCountRange.contains(dataByte) =>
        MonoModeOnMidiMsg(channel, dataByte)
      case MonoModeOnMidiMsg.Number => toUnsupported(shortMessage)
      case PolyModeOnMidiMsg.Number => PolyModeOnMidiMsg(channel)
      case _ => CcMidiMsg(channel, number, dataByte)
    }
  }

  /** Renders a Channel Mode message: the Control Change status byte, the message's number, and its data byte. */
  private def channelModeMessage(channel: Int, number: Int, dataByte: Int = 0): ShortMessage =
    new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number, dataByte)

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

  private[javamidi] def bigEndian(value: Int, numBytes: Int): Array[Byte] = {
    val bytes = new Array[Byte](numBytes)
    var i = 0
    while (i < numBytes) {
      bytes(i) = ((value >> (8 * (numBytes - 1 - i))) & 0xFF).toByte
      i += 1
    }
    bytes
  }

  private[javamidi] def fromBigEndian(bytes: Array[Byte]): Int = {
    var value = 0
    var i = 0
    while (i < bytes.length) {
      value = (value << 8) | (bytes(i) & 0xFF)
      i += 1
    }
    value
  }

  /**
   * Reconstructs the original Java [[MidiMessage]] from the raw bytes stored in an [[UnsupportedMidiMsg]].
   *
   * The kind of message (`ShortMessage` / `SysexMessage` / `MetaMessage`) is detected from the status byte.
   * `MetaMessage` payloads use the SMF format with a variable-length quantity (VLQ) encoded length.
   */
  private def reconstructUnsupported(data: ArraySeq[Byte]): MidiMessage = {
    val bytes = data.toArray
    require(bytes.nonEmpty, "UnsupportedMidiMsg data must not be empty")
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
