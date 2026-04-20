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

import javax.sound.midi.{MetaMessage, MidiMessage, ShortMessage, SysexMessage}

import scala.collection.immutable.ArraySeq

/**
 * Scala-idiomatic base trait for MIDI messages, wrapping Java's [[javax.sound.midi.MidiMessage]] hierarchy.
 *
 * Unlike Java's [[javax.sound.midi.MidiMessage]] (and its subclasses like [[javax.sound.midi.ShortMessage]]),
 * which expose raw byte data and mutable state, `ScMidiMessage` subtypes are immutable case classes with named,
 * validated parameters, Scala pattern matching support via `unapply`, and convenient factory methods for
 * bidirectional conversion with Java messages.
 */
sealed trait ScMidiMessage {
  /** The underlying [[javax.sound.midi.MidiMessage]]. */
  def toJavaMidiMessage: MidiMessage
}

/**
 * Type class for converting a Java [[javax.sound.midi.MidiMessage]] into a specific [[ScMidiMessage]] subtype.
 *
 * Implementations are typically provided by companion objects of [[ScMidiMessage]] subtypes, allowing
 * type-safe conversion from the raw Java MIDI representation to the Scala-idiomatic one.
 *
 * @tparam T The target [[ScMidiMessage]] subtype produced by the conversion.
 */
trait FromJavaMidiMessageConverter[T] {
  /**
   * Attempts to convert the given Java [[MidiMessage]] to an instance of `T`.
   *
   * @param message The Java [[MidiMessage]] to convert.
   * @return `Some(T)` if the message matches the expected type and command for `T`; `None` otherwise.
   */
  def fromJavaMessage(message: MidiMessage): Option[T]
}

object ScMidiMessage {
  private val FromJavaMessageMap: Map[Int, MidiMessage => ScMidiMessage] = Map(
    ShortMessage.NOTE_ON -> NoteOnScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.NOTE_OFF -> NoteOffScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.PITCH_BEND -> PitchBendScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.CONTROL_CHANGE -> CcScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.CHANNEL_PRESSURE -> ChannelPressureScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.POLY_PRESSURE -> PolyPressureScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.PROGRAM_CHANGE -> ProgramChangeScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.MIDI_TIME_CODE -> MidiTimeCodeScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.SONG_POSITION_POINTER -> SongPositionPointerScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.SONG_SELECT -> SongSelectScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.TUNE_REQUEST -> ((_: MidiMessage) => TuneRequestScMidiMessage),
    ShortMessage.TIMING_CLOCK -> ((_: MidiMessage) => TimingClockScMidiMessage),
    ShortMessage.START -> ((_: MidiMessage) => StartScMidiMessage),
    ShortMessage.CONTINUE -> ((_: MidiMessage) => ContinueScMidiMessage),
    ShortMessage.STOP -> ((_: MidiMessage) => StopScMidiMessage),
    ShortMessage.ACTIVE_SENSING -> ((_: MidiMessage) => ActiveSensingScMidiMessage),
    ShortMessage.SYSTEM_RESET -> ((_: MidiMessage) => SystemResetScMidiMessage)
  ).withDefaultValue(UnsupportedScMidiMessage.apply)

  private val FromMetaMessageMap: Map[Int, MidiMessage => ScMidiMessage] = Map(
    SequenceNumberMetaScMidiMessage.MetaType -> SequenceNumberMetaScMidiMessage.fromJavaMessage.unlift,
    TextMetaScMidiMessage.MetaType -> TextMetaScMidiMessage.fromJavaMessage.unlift,
    CopyrightNoticeMetaScMidiMessage.MetaType -> CopyrightNoticeMetaScMidiMessage.fromJavaMessage.unlift,
    TrackNameMetaScMidiMessage.MetaType -> TrackNameMetaScMidiMessage.fromJavaMessage.unlift,
    InstrumentNameMetaScMidiMessage.MetaType -> InstrumentNameMetaScMidiMessage.fromJavaMessage.unlift,
    LyricMetaScMidiMessage.MetaType -> LyricMetaScMidiMessage.fromJavaMessage.unlift,
    MarkerMetaScMidiMessage.MetaType -> MarkerMetaScMidiMessage.fromJavaMessage.unlift,
    CuePointMetaScMidiMessage.MetaType -> CuePointMetaScMidiMessage.fromJavaMessage.unlift,
    ProgramNameMetaScMidiMessage.MetaType -> ProgramNameMetaScMidiMessage.fromJavaMessage.unlift,
    DeviceNameMetaScMidiMessage.MetaType -> DeviceNameMetaScMidiMessage.fromJavaMessage.unlift,
    MidiChannelPrefixMetaScMidiMessage.MetaType -> MidiChannelPrefixMetaScMidiMessage.fromJavaMessage.unlift,
    MidiPortMetaScMidiMessage.MetaType -> MidiPortMetaScMidiMessage.fromJavaMessage.unlift,
    EndOfTrackMetaScMidiMessage.MetaType -> ((_: MidiMessage) => EndOfTrackMetaScMidiMessage),
    SetTempoMetaScMidiMessage.MetaType -> SetTempoMetaScMidiMessage.fromJavaMessage.unlift,
    SmpteOffsetMetaScMidiMessage.MetaType -> SmpteOffsetMetaScMidiMessage.fromJavaMessage.unlift,
    TimeSignatureMetaScMidiMessage.MetaType -> TimeSignatureMetaScMidiMessage.fromJavaMessage.unlift,
    KeySignatureMetaScMidiMessage.MetaType -> KeySignatureMetaScMidiMessage.fromJavaMessage.unlift,
    SequencerSpecificMetaScMidiMessage.MetaType -> SequencerSpecificMetaScMidiMessage.fromJavaMessage.unlift
  ).withDefaultValue(UnsupportedScMidiMessage.apply)

  /**
   * Converts a Java [[MidiMessage]] to the corresponding [[ScMidiMessage]] subtype.
   *
   * Supported message types are mapped to their Scala-idiomatic counterparts (e.g.
   * [[javax.sound.midi.ShortMessage]] with command `NOTE_ON` becomes [[NoteOnScMidiMessage]]).
   * Unrecognized messages are wrapped in [[UnsupportedScMidiMessage]].
   *
   * @param message The Java [[MidiMessage]] to convert. Must not be null.
   * @return The [[ScMidiMessage]] instance.
   * @throws IllegalArgumentException if the message is null.
   */
  def fromJavaMessage(message: MidiMessage): ScMidiMessage = {
    require(message != null, "message must not be null")

    message match {
      case shortMessage: ShortMessage =>
        val status = shortMessage.getStatus
        val key = if (status >= 0xF0) status else shortMessage.getCommand
        FromJavaMessageMap(key)(message)
      case sysexMessage: SysexMessage =>
        SysexScMidiMessage.fromJavaMessage(sysexMessage).getOrElse(UnsupportedScMidiMessage(message))
      case metaMessage: MetaMessage =>
        FromMetaMessageMap(metaMessage.getType)(metaMessage)
      case _ => UnsupportedScMidiMessage(message)
    }
  }
}

// ============================================================================
// Channel Voice Messages
// ============================================================================

/**
 * Base class for Note On and Note Off MIDI messages, replacing Java's overloaded use of
 * [[javax.sound.midi.ShortMessage]] with typed `channel`, `midiNote`, and `velocity` parameters
 * that are validated on construction.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note.
 * @param velocity The velocity (0-127).
 */
abstract class NoteScMidiMessage(val channel: Int,
                                 val midiNote: MidiNote,
                                 val velocity: Int = NoteOnScMidiMessage.DefaultVelocity) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  midiNote.assertValid()
  MidiRequirements.requireUnsigned7BitValue("velocity", velocity)

  override def toJavaMidiMessage: ShortMessage = new ShortMessage(midiCommand, channel, midiNote.number, velocity)

  /** The MIDI command for this note message (e.g., [[ShortMessage.NOTE_ON]]). */
  protected val midiCommand: Int
}

/**
 * Represents a MIDI Note On message.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note.
 * @param velocity The velocity (0-127).
 */
case class NoteOnScMidiMessage(override val channel: Int,
                               override val midiNote: MidiNote,
                               override val velocity: Int = NoteOnScMidiMessage.DefaultVelocity)
  extends NoteScMidiMessage(channel, midiNote, velocity) {
  override protected val midiCommand: Int = ShortMessage.NOTE_ON
}

/**
 * Companion object for [[NoteOnScMidiMessage]].
 */
object NoteOnScMidiMessage extends FromJavaMidiMessageConverter[NoteOnScMidiMessage] {
  /** The velocity value representing a Note Off via Note On (0). */
  val NoteOffVelocity: Int = 0x00
  /** The default velocity for Note On messages (64). */
  val DefaultVelocity: Int = 0x40

  /** Extracts the channel, MIDI note, and velocity from a [[MidiMessage]] if it is a Note On message. */
  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.NOTE_ON =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[NoteOnScMidiMessage] =
    unapply(message).map { tuple => NoteOnScMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Note Off message.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note.
 * @param velocity The velocity (0-127).
 */
case class NoteOffScMidiMessage(override val channel: Int,
                                override val midiNote: MidiNote,
                                override val velocity: Int = NoteOffScMidiMessage.DefaultVelocity)
  extends NoteScMidiMessage(channel, midiNote, velocity) {
  override protected val midiCommand: Int = ShortMessage.NOTE_OFF
}

/**
 * Companion object for [[NoteOffScMidiMessage]] used for pattern matching and default values.
 */
object NoteOffScMidiMessage extends FromJavaMidiMessageConverter[NoteOffScMidiMessage] {
  /** The default velocity for Note Off messages (64). */
  val DefaultVelocity: Int = 0x40

  /** Extracts the channel, MIDI note, and velocity from a [[MidiMessage]] if it is a Note Off message. */
  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.NOTE_OFF =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[NoteOffScMidiMessage] =
    unapply(message).map { tuple => NoteOffScMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Polyphonic Key Pressure (Poly Aftertouch) message with typed `midiNote` and validated `value`
 * parameters.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note to which the pressure applies.
 * @param value    The pressure value (0-127).
 */
case class PolyPressureScMidiMessage(channel: Int, midiNote: MidiNote, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  midiNote.assertValid()
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val toJavaMidiMessage: ShortMessage =
    new ShortMessage(ShortMessage.POLY_PRESSURE, channel, midiNote.number, value)
}

/**
 * Companion object for [[PolyPressureScMidiMessage]].
 */
object PolyPressureScMidiMessage extends FromJavaMidiMessageConverter[PolyPressureScMidiMessage] {
  /** Extracts the channel, MIDI note, and value from a [[MidiMessage]] if it is a Poly Pressure message. */
  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.POLY_PRESSURE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[PolyPressureScMidiMessage] =
    unapply(message).map { tuple => PolyPressureScMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Control Change (CC) message with named, validated `number` and `value` parameters.
 *
 * CC number constants are available in the [[ScMidiCc]] object.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param number  The controller number (0-127).
 * @param value   The controller value (0-127).
 */
case class CcScMidiMessage(channel: Int, number: Int, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("number", number)
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number,
    value)
}

/**
 * Companion object for [[CcScMidiMessage]].
 */
object CcScMidiMessage extends FromJavaMidiMessageConverter[CcScMidiMessage] {
  /** Extracts the channel, controller number, and value from a [[MidiMessage]] if it is a Control Change message. */
  def unapply(message: MidiMessage): Option[(Int, Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CONTROL_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[CcScMidiMessage] =
    unapply(message).map { tuple => CcScMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Program Change message with a validated `program` parameter.
 *
 * Unlike Java's [[javax.sound.midi.ShortMessage]], which uses a generic `data1` byte for the program number,
 * this class provides a descriptively named `program` field.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param program The program number (0-127).
 */
case class ProgramChangeScMidiMessage(channel: Int, program: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("program", program)

  override lazy val toJavaMidiMessage: ShortMessage =
    new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0)
}

/**
 * Companion object for [[ProgramChangeScMidiMessage]].
 */
object ProgramChangeScMidiMessage extends FromJavaMidiMessageConverter[ProgramChangeScMidiMessage] {
  /** Extracts the channel and program number from a [[MidiMessage]] if it is a Program Change message. */
  def unapply(message: MidiMessage): Option[(Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.PROGRAM_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ProgramChangeScMidiMessage] =
    unapply(message).map { tuple => ProgramChangeScMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Channel Pressure (Aftertouch) message with a validated `value` parameter.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param value   The pressure value (0-127).
 */
case class ChannelPressureScMidiMessage(channel: Int, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.CHANNEL_PRESSURE, channel, value, 0)
}

/**
 * Companion object for [[ChannelPressureScMidiMessage]].
 */
object ChannelPressureScMidiMessage extends FromJavaMidiMessageConverter[ChannelPressureScMidiMessage] {
  /** Extracts the channel and value from a [[MidiMessage]] if it is a Channel Pressure message. */
  def unapply(message: MidiMessage): Option[(Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CHANNEL_PRESSURE =>
      Some((shortMessage.getChannel, shortMessage.getData1))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ChannelPressureScMidiMessage] =
    unapply(message).map { tuple => ChannelPressureScMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Pitch Bend message with a signed 14-bit value.
 *
 * Unlike Java's [[javax.sound.midi.ShortMessage]], which exposes pitch bend as two raw unsigned data bytes (LSB/MSB),
 * this class provides a single signed `value` (-8192 to 8191) and convenience methods for converting to/from cents
 * based on a [[PitchBendSensitivity]].
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param value   The signed 14-bit pitch bend value (-8192 to 8191).
 */
case class PitchBendScMidiMessage(channel: Int, value: Int) extends ScMidiMessage {

  import PitchBendScMidiMessage.*

  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireSigned14BitValue("value", value)

  override lazy val toJavaMidiMessage: ShortMessage = {
    val (data1, data2) = convertValueToDataBytes(value)
    new ShortMessage(ShortMessage.PITCH_BEND, channel, data1, data2)
  }

  /** Calculates the pitch bend in cents based on the given pitch bend sensitivity. */
  def centsFor(pitchBendSensitivity: PitchBendSensitivity): Double = convertValueToCents(value, pitchBendSensitivity)

  /**
   * Calculates the pitch bend in cents using the implicit pitch bend sensitivity.
   *
   * @param pitchBendSensitivity Implicit parameter that defines the pitch bend range
   *                             in semitones and cents.
   *
   * @return The pitch bend amount in cents.
   */
  def cents(implicit pitchBendSensitivity: PitchBendSensitivity): Double = centsFor(pitchBendSensitivity)
}

/**
 * Companion object for [[PitchBendScMidiMessage]].
 */
object PitchBendScMidiMessage extends FromJavaMidiMessageConverter[PitchBendScMidiMessage] {
  /** The minimum signed 14-bit pitch bend value (-8192). */
  val MinValue: Int = MidiRequirements.MinSigned14BitValue
  /** The value representing no pitch bend (0). */
  val NoPitchBendValue: Int = 0
  /** The maximum signed 14-bit pitch bend value (8191). */
  val MaxValue: Int = MidiRequirements.MaxSigned14BitValue

  /** Extracts the channel and value from a [[MidiMessage]] if it is a Pitch Bend message. */
  def unapply(message: MidiMessage): Option[(Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.PITCH_BEND =>
      Some((shortMessage.getChannel, convertDataBytesToValue(shortMessage.getData1, shortMessage.getData2)))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[PitchBendScMidiMessage] =
    unapply(message).map { tuple => PitchBendScMidiMessage.apply.tupled(tuple) }

  /** Creates a [[PitchBendScMidiMessage]] from a value in cents. */
  def fromCents(channel: Int,
                cents: Int,
                pitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default): PitchBendScMidiMessage = {
    val value = convertCentsToValue(cents, pitchBendSensitivity)
    PitchBendScMidiMessage(channel, value)
  }

  /** Converts MIDI data bytes (LSB, MSB) to a signed 14-bit pitch bend value. */
  def convertDataBytesToValue(data1: Int, data2: Int): Int = {
    val lsb = data1
    val msb = data2
    ((msb << 7) | lsb) + MinValue
  }

  /** Converts a signed 14-bit pitch bend value to MIDI data bytes (LSB, MSB). */
  def convertValueToDataBytes(value: Int): (Int, Int) = {
    val unsignedValue = value - MinValue
    val lsb = unsignedValue & 0x7F
    val msb = (unsignedValue >> 7) & 0x7F
    (lsb, msb)
  }

  /** Converts a pitch bend value in cents to a signed 14-bit pitch bend value. */
  def convertCentsToValue(cents: Double, pitchBendSensitivity: PitchBendSensitivity): Int = {
    require(cents >= -pitchBendSensitivity.totalCents && cents <= pitchBendSensitivity.totalCents,
      "cents should not exceed the limits set by pitchBendSensitivity")

    if (cents == 0) {
      0
    } else {
      val r = Math.abs(cents / pitchBendSensitivity.totalCents)
      if (cents < 0) {
        Math.round(r * MinValue).toInt
      } else {
        Math.round(r * MaxValue).toInt
      }
    }
  }

  /** Converts a signed 14-bit pitch bend value to cents based on the given pitch bend sensitivity. */
  def convertValueToCents(value: Int, pitchBendSensitivity: PitchBendSensitivity): Double = {
    MidiRequirements.requireSigned14BitValue("value", value)

    if (value == 0) {
      0
    } else if (value < 0) {
      -Math.abs(value.toDouble / MinValue) * pitchBendSensitivity.totalCents
    } else { // if (value > 0)
      Math.abs(value.toDouble / MaxValue) * pitchBendSensitivity.totalCents
    }
  }

  /** Converts a pitch bend value in cents to MIDI data bytes (LSB, MSB). */
  def convertCentsToDataBytes(cents: Double, pitchBendSensitivity: PitchBendSensitivity): (Int, Int) = {
    val value = convertCentsToValue(cents, pitchBendSensitivity)
    convertValueToDataBytes(value)
  }
}

// ============================================================================
// System Common Messages
// ============================================================================

/**
 * Represents a MIDI Time Code Quarter Frame message.
 *
 * The single data byte encodes a 3-bit message type in the high nibble (0-7, selecting which part of
 * the SMPTE time is being transmitted) and a 4-bit values field in the low nibble (0-15).
 *
 * @param messageType The message type nibble (0-7).
 * @param values      The values nibble (0-15).
 */
case class MidiTimeCodeScMidiMessage(messageType: Int, values: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned3BitValue("messageType", messageType)
  MidiRequirements.requireUnsigned4BitValue("values", values)

  override lazy val toJavaMidiMessage: ShortMessage =
    new ShortMessage(ShortMessage.MIDI_TIME_CODE, (messageType << 4) | values, 0)
}

/** Companion object for [[MidiTimeCodeScMidiMessage]]. */
object MidiTimeCodeScMidiMessage extends FromJavaMidiMessageConverter[MidiTimeCodeScMidiMessage] {
  /** Extracts the message type and values from a [[MidiMessage]] if it is a MIDI Time Code Quarter Frame message. */
  def unapply(message: MidiMessage): Option[(Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.MIDI_TIME_CODE =>
      val data1 = shortMessage.getData1
      Some(((data1 >> 4) & 0x07, data1 & 0x0F))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[MidiTimeCodeScMidiMessage] =
    unapply(message).map { tuple => MidiTimeCodeScMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Song Position Pointer message, carrying a 14-bit position in MIDI beats
 * (one MIDI beat = six MIDI clocks).
 *
 * @param position The song position in MIDI beats (0-16383).
 */
case class SongPositionPointerScMidiMessage(position: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned14BitValue("position", position)

  override lazy val toJavaMidiMessage: ShortMessage = {
    val lsb = position & 0x7F
    val msb = (position >> 7) & 0x7F
    new ShortMessage(ShortMessage.SONG_POSITION_POINTER, lsb, msb)
  }
}

/** Companion object for [[SongPositionPointerScMidiMessage]]. */
object SongPositionPointerScMidiMessage extends FromJavaMidiMessageConverter[SongPositionPointerScMidiMessage] {
  /** Extracts the 14-bit position from a [[MidiMessage]] if it is a Song Position Pointer message. */
  def unapply(message: MidiMessage): Option[Int] = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.SONG_POSITION_POINTER =>
      Some((shortMessage.getData2 << 7) | shortMessage.getData1)
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[SongPositionPointerScMidiMessage] =
    unapply(message).map(SongPositionPointerScMidiMessage.apply)
}

/**
 * Represents a MIDI Song Select message, selecting which song or sequence is to be played.
 *
 * @param song The song number (0-127).
 */
case class SongSelectScMidiMessage(song: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned7BitValue("song", song)

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.SONG_SELECT, song, 0)
}

/** Companion object for [[SongSelectScMidiMessage]]. */
object SongSelectScMidiMessage extends FromJavaMidiMessageConverter[SongSelectScMidiMessage] {
  /** Extracts the song number from a [[MidiMessage]] if it is a Song Select message. */
  def unapply(message: MidiMessage): Option[Int] = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.SONG_SELECT =>
      Some(shortMessage.getData1)
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[SongSelectScMidiMessage] =
    unapply(message).map(SongSelectScMidiMessage.apply)
}

/**
 * Represents a MIDI Tune Request message, requesting that analog synthesizers retune their oscillators.
 */
case object TuneRequestScMidiMessage extends ScMidiMessage
  with FromJavaMidiMessageConverter[TuneRequestScMidiMessage.type] {

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.TUNE_REQUEST)

  /** Returns `true` if the given [[MidiMessage]] is a Tune Request message. */
  def unapply(message: MidiMessage): Boolean = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.TUNE_REQUEST => true
    case _ => false
  }

  override def fromJavaMessage(message: MidiMessage): Option[TuneRequestScMidiMessage.type] =
    if (unapply(message)) Some(this) else None
}

// ============================================================================
// System Real-Time Messages
// ============================================================================

/** Represents a MIDI Timing Clock message, transmitted at a rate of 24 per quarter note while playing. */
case object TimingClockScMidiMessage extends ScMidiMessage
  with FromJavaMidiMessageConverter[TimingClockScMidiMessage.type] {

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.TIMING_CLOCK)

  /** Returns `true` if the given [[MidiMessage]] is a Timing Clock message. */
  def unapply(message: MidiMessage): Boolean = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.TIMING_CLOCK => true
    case _ => false
  }

  override def fromJavaMessage(message: MidiMessage): Option[TimingClockScMidiMessage.type] =
    if (unapply(message)) Some(this) else None
}

/** Represents a MIDI Start message, instructing the receiver to start playback from the beginning. */
case object StartScMidiMessage extends ScMidiMessage
  with FromJavaMidiMessageConverter[StartScMidiMessage.type] {

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.START)

  /** Returns `true` if the given [[MidiMessage]] is a Start message. */
  def unapply(message: MidiMessage): Boolean = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.START => true
    case _ => false
  }

  override def fromJavaMessage(message: MidiMessage): Option[StartScMidiMessage.type] =
    if (unapply(message)) Some(this) else None
}

/** Represents a MIDI Continue message, instructing the receiver to resume playback from the current position. */
case object ContinueScMidiMessage extends ScMidiMessage
  with FromJavaMidiMessageConverter[ContinueScMidiMessage.type] {

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.CONTINUE)

  /** Returns `true` if the given [[MidiMessage]] is a Continue message. */
  def unapply(message: MidiMessage): Boolean = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.CONTINUE => true
    case _ => false
  }

  override def fromJavaMessage(message: MidiMessage): Option[ContinueScMidiMessage.type] =
    if (unapply(message)) Some(this) else None
}

/** Represents a MIDI Stop message, instructing the receiver to stop playback. */
case object StopScMidiMessage extends ScMidiMessage
  with FromJavaMidiMessageConverter[StopScMidiMessage.type] {

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.STOP)

  /** Returns `true` if the given [[MidiMessage]] is a Stop message. */
  def unapply(message: MidiMessage): Boolean = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.STOP => true
    case _ => false
  }

  override def fromJavaMessage(message: MidiMessage): Option[StopScMidiMessage.type] =
    if (unapply(message)) Some(this) else None
}

/**
 * Represents a MIDI Active Sensing message, sent every 300ms or less to indicate that the connection is still active.
 */
case object ActiveSensingScMidiMessage extends ScMidiMessage
  with FromJavaMidiMessageConverter[ActiveSensingScMidiMessage.type] {

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.ACTIVE_SENSING)

  /** Returns `true` if the given [[MidiMessage]] is an Active Sensing message. */
  def unapply(message: MidiMessage): Boolean = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.ACTIVE_SENSING => true
    case _ => false
  }

  override def fromJavaMessage(message: MidiMessage): Option[ActiveSensingScMidiMessage.type] =
    if (unapply(message)) Some(this) else None
}

/** Represents a MIDI System Reset message, instructing the receiver to reset to its power-up state. */
case object SystemResetScMidiMessage extends ScMidiMessage
  with FromJavaMidiMessageConverter[SystemResetScMidiMessage.type] {

  override lazy val toJavaMidiMessage: ShortMessage = new ShortMessage(ShortMessage.SYSTEM_RESET)

  /** Returns `true` if the given [[MidiMessage]] is a System Reset message. */
  def unapply(message: MidiMessage): Boolean = message match {
    case shortMessage: ShortMessage if shortMessage.getStatus == ShortMessage.SYSTEM_RESET => true
    case _ => false
  }

  override def fromJavaMessage(message: MidiMessage): Option[SystemResetScMidiMessage.type] =
    if (unapply(message)) Some(this) else None
}

// ============================================================================
// System Exclusive
// ============================================================================

/**
 * Represents a MIDI System Exclusive (SysEx) message.
 *
 * The `data` holds the full byte sequence including the leading `0xF0` status byte and the trailing `0xF7`
 * end-of-exclusive byte. [[ArraySeq]] is used (instead of `Array[Byte]`) so structural equality and hashing
 * work correctly with `case class`.
 *
 * @param data The full SysEx byte sequence.
 */
case class SysexScMidiMessage(data: ArraySeq[Byte]) extends ScMidiMessage {
  override lazy val toJavaMidiMessage: SysexMessage = {
    val bytes = data.toArray
    new SysexMessage(bytes, bytes.length)
  }
}

/**
 * Companion object for [[SysexScMidiMessage]].
 */
object SysexScMidiMessage extends FromJavaMidiMessageConverter[SysexScMidiMessage] {
  /** Extracts the full SysEx byte sequence if the given [[MidiMessage]] is a [[SysexMessage]]. */
  def unapply(message: MidiMessage): Option[ArraySeq[Byte]] = message match {
    case sysexMessage: SysexMessage => Some(ArraySeq.unsafeWrapArray(sysexMessage.getMessage))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[SysexScMidiMessage] =
    unapply(message).map(SysexScMidiMessage.apply)
}

// ============================================================================
// Meta Messages (Standard MIDI File)
// ============================================================================

/** Internal helpers for building [[javax.sound.midi.MetaMessage]] instances. */
private object MetaScMidiMessage {
  /**
   * String encoding used by text-bearing SMF meta events. The SMF spec formally specifies ASCII, but 8-bit bytes
   * are used in practice; ISO-8859-1 round-trips every byte losslessly.
   */
  val TextEncoding: String = "ISO-8859-1"

  /** Builds a [[MetaMessage]] from a meta type byte and payload bytes. */
  def build(metaType: Int, data: Array[Byte]): MetaMessage = {
    val msg = new MetaMessage()
    msg.setMessage(metaType, data, data.length)
    msg
  }

  /** Encodes an `Int` as a big-endian byte sequence of the given width. */
  def bigEndian(value: Int, numBytes: Int): Array[Byte] = {
    val bytes = new Array[Byte](numBytes)
    var i = 0
    while (i < numBytes) {
      bytes(i) = ((value >> (8 * (numBytes - 1 - i))) & 0xFF).toByte
      i += 1
    }
    bytes
  }

  /** Decodes a big-endian unsigned integer from the given bytes. */
  def fromBigEndian(bytes: Array[Byte]): Int = {
    var value = 0
    var i = 0
    while (i < bytes.length) {
      value = (value << 8) | (bytes(i) & 0xFF)
      i += 1
    }
    value
  }
}

/** The mode of a musical key signature. */
enum ScMidiKeySignatureMode {
  case Major, Minor
}

/**
 * Represents a Sequence Number SMF meta event (type `0x00`).
 *
 * @param number The sequence number (0 to 65535, 2 bytes big-endian).
 */
case class SequenceNumberMetaScMidiMessage(number: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned16BitValue("number", number)
  override lazy val toJavaMidiMessage: MetaMessage =
    MetaScMidiMessage.build(SequenceNumberMetaScMidiMessage.MetaType, MetaScMidiMessage.bigEndian(number, 2))
}

/** Companion object for [[SequenceNumberMetaScMidiMessage]]. */
object SequenceNumberMetaScMidiMessage extends FromJavaMidiMessageConverter[SequenceNumberMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x00

  /** Extracts the sequence number if the given message is a Sequence Number meta event. */
  def unapply(message: MidiMessage): Option[Int] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(MetaScMidiMessage.fromBigEndian(m.getData))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[SequenceNumberMetaScMidiMessage] =
    unapply(message).map(SequenceNumberMetaScMidiMessage.apply)
}

/**
 * Base case for text-bearing SMF meta events.
 *
 * @param metaType The SMF meta event type byte.
 * @param text     The textual payload.
 */
sealed abstract class TextBearingMetaScMidiMessage(metaType: Int, text: String) extends ScMidiMessage {
  override lazy val toJavaMidiMessage: MetaMessage =
    MetaScMidiMessage.build(metaType, text.getBytes(MetaScMidiMessage.TextEncoding))
}

/** Represents a Text SMF meta event (type `0x01`). */
case class TextMetaScMidiMessage(text: String) extends TextBearingMetaScMidiMessage(TextMetaScMidiMessage.MetaType, text)

/** Companion object for [[TextMetaScMidiMessage]]. */
object TextMetaScMidiMessage extends FromJavaMidiMessageConverter[TextMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x01

  /** Extracts the text if the given message is a Text meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[TextMetaScMidiMessage] =
    unapply(message).map(TextMetaScMidiMessage.apply)
}

/** Represents a Copyright Notice SMF meta event (type `0x02`). */
case class CopyrightNoticeMetaScMidiMessage(text: String)
  extends TextBearingMetaScMidiMessage(CopyrightNoticeMetaScMidiMessage.MetaType, text)

/** Companion object for [[CopyrightNoticeMetaScMidiMessage]]. */
object CopyrightNoticeMetaScMidiMessage extends FromJavaMidiMessageConverter[CopyrightNoticeMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x02

  /** Extracts the text if the given message is a Copyright Notice meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[CopyrightNoticeMetaScMidiMessage] =
    unapply(message).map(CopyrightNoticeMetaScMidiMessage.apply)
}

/** Represents a Track Name SMF meta event (type `0x03`). */
case class TrackNameMetaScMidiMessage(name: String)
  extends TextBearingMetaScMidiMessage(TrackNameMetaScMidiMessage.MetaType, name)

/** Companion object for [[TrackNameMetaScMidiMessage]]. */
object TrackNameMetaScMidiMessage extends FromJavaMidiMessageConverter[TrackNameMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x03

  /** Extracts the name if the given message is a Track Name meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[TrackNameMetaScMidiMessage] =
    unapply(message).map(TrackNameMetaScMidiMessage.apply)
}

/** Represents an Instrument Name SMF meta event (type `0x04`). */
case class InstrumentNameMetaScMidiMessage(name: String)
  extends TextBearingMetaScMidiMessage(InstrumentNameMetaScMidiMessage.MetaType, name)

/** Companion object for [[InstrumentNameMetaScMidiMessage]]. */
object InstrumentNameMetaScMidiMessage extends FromJavaMidiMessageConverter[InstrumentNameMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x04

  /** Extracts the name if the given message is an Instrument Name meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[InstrumentNameMetaScMidiMessage] =
    unapply(message).map(InstrumentNameMetaScMidiMessage.apply)
}

/** Represents a Lyric SMF meta event (type `0x05`). */
case class LyricMetaScMidiMessage(text: String)
  extends TextBearingMetaScMidiMessage(LyricMetaScMidiMessage.MetaType, text)

/** Companion object for [[LyricMetaScMidiMessage]]. */
object LyricMetaScMidiMessage extends FromJavaMidiMessageConverter[LyricMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x05

  /** Extracts the text if the given message is a Lyric meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[LyricMetaScMidiMessage] =
    unapply(message).map(LyricMetaScMidiMessage.apply)
}

/** Represents a Marker SMF meta event (type `0x06`). */
case class MarkerMetaScMidiMessage(text: String)
  extends TextBearingMetaScMidiMessage(MarkerMetaScMidiMessage.MetaType, text)

/** Companion object for [[MarkerMetaScMidiMessage]]. */
object MarkerMetaScMidiMessage extends FromJavaMidiMessageConverter[MarkerMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x06

  /** Extracts the text if the given message is a Marker meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[MarkerMetaScMidiMessage] =
    unapply(message).map(MarkerMetaScMidiMessage.apply)
}

/** Represents a Cue Point SMF meta event (type `0x07`). */
case class CuePointMetaScMidiMessage(text: String)
  extends TextBearingMetaScMidiMessage(CuePointMetaScMidiMessage.MetaType, text)

/** Companion object for [[CuePointMetaScMidiMessage]]. */
object CuePointMetaScMidiMessage extends FromJavaMidiMessageConverter[CuePointMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x07

  /** Extracts the text if the given message is a Cue Point meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[CuePointMetaScMidiMessage] =
    unapply(message).map(CuePointMetaScMidiMessage.apply)
}

/** Represents a Program Name SMF meta event (type `0x08`). */
case class ProgramNameMetaScMidiMessage(name: String)
  extends TextBearingMetaScMidiMessage(ProgramNameMetaScMidiMessage.MetaType, name)

/** Companion object for [[ProgramNameMetaScMidiMessage]]. */
object ProgramNameMetaScMidiMessage extends FromJavaMidiMessageConverter[ProgramNameMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x08

  /** Extracts the name if the given message is a Program Name meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ProgramNameMetaScMidiMessage] =
    unapply(message).map(ProgramNameMetaScMidiMessage.apply)
}

/** Represents a Device Name SMF meta event (type `0x09`). */
case class DeviceNameMetaScMidiMessage(name: String)
  extends TextBearingMetaScMidiMessage(DeviceNameMetaScMidiMessage.MetaType, name)

/** Companion object for [[DeviceNameMetaScMidiMessage]]. */
object DeviceNameMetaScMidiMessage extends FromJavaMidiMessageConverter[DeviceNameMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x09

  /** Extracts the name if the given message is a Device Name meta event. */
  def unapply(message: MidiMessage): Option[String] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(new String(m.getData, MetaScMidiMessage.TextEncoding))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[DeviceNameMetaScMidiMessage] =
    unapply(message).map(DeviceNameMetaScMidiMessage.apply)
}

/**
 * Represents a MIDI Channel Prefix SMF meta event (type `0x20`).
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
case class MidiChannelPrefixMetaScMidiMessage(channel: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  override lazy val toJavaMidiMessage: MetaMessage =
    MetaScMidiMessage.build(MidiChannelPrefixMetaScMidiMessage.MetaType, Array(channel.toByte))
}

/** Companion object for [[MidiChannelPrefixMetaScMidiMessage]]. */
object MidiChannelPrefixMetaScMidiMessage extends FromJavaMidiMessageConverter[MidiChannelPrefixMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x20

  /** Extracts the channel if the given message is a MIDI Channel Prefix meta event. */
  def unapply(message: MidiMessage): Option[Int] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(m.getData()(0) & 0xFF)
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[MidiChannelPrefixMetaScMidiMessage] =
    unapply(message).map(MidiChannelPrefixMetaScMidiMessage.apply)
}

/**
 * Represents a MIDI Port SMF meta event (type `0x21`).
 *
 * @param port The MIDI port number (0-127).
 */
case class MidiPortMetaScMidiMessage(port: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned7BitValue("port", port)
  override lazy val toJavaMidiMessage: MetaMessage =
    MetaScMidiMessage.build(MidiPortMetaScMidiMessage.MetaType, Array(port.toByte))
}

/** Companion object for [[MidiPortMetaScMidiMessage]]. */
object MidiPortMetaScMidiMessage extends FromJavaMidiMessageConverter[MidiPortMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x21

  /** Extracts the port if the given message is a MIDI Port meta event. */
  def unapply(message: MidiMessage): Option[Int] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(m.getData()(0) & 0xFF)
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[MidiPortMetaScMidiMessage] =
    unapply(message).map(MidiPortMetaScMidiMessage.apply)
}

/** Represents the End Of Track SMF meta event (type `0x2F`). */
case object EndOfTrackMetaScMidiMessage extends ScMidiMessage
  with FromJavaMidiMessageConverter[EndOfTrackMetaScMidiMessage.type] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x2F

  override lazy val toJavaMidiMessage: MetaMessage = MetaScMidiMessage.build(MetaType, Array.emptyByteArray)

  /** Returns `true` if the given [[MidiMessage]] is an End Of Track meta event. */
  def unapply(message: MidiMessage): Boolean = message match {
    case m: MetaMessage if m.getType == MetaType => true
    case _ => false
  }

  override def fromJavaMessage(message: MidiMessage): Option[EndOfTrackMetaScMidiMessage.type] =
    if (unapply(message)) Some(this) else None
}

/**
 * Represents a Set Tempo SMF meta event (type `0x51`).
 *
 * @param microsecondsPerQuarterNote Tempo in microseconds per quarter note (0 to 16777215, 24-bit).
 */
case class SetTempoMetaScMidiMessage(microsecondsPerQuarterNote: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned24BitValue("microsecondsPerQuarterNote", microsecondsPerQuarterNote)
  override lazy val toJavaMidiMessage: MetaMessage =
    MetaScMidiMessage.build(SetTempoMetaScMidiMessage.MetaType, MetaScMidiMessage.bigEndian(microsecondsPerQuarterNote, 3))
}

/** Companion object for [[SetTempoMetaScMidiMessage]]. */
object SetTempoMetaScMidiMessage extends FromJavaMidiMessageConverter[SetTempoMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x51

  /** Extracts the tempo value if the given message is a Set Tempo meta event. */
  def unapply(message: MidiMessage): Option[Int] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(MetaScMidiMessage.fromBigEndian(m.getData))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[SetTempoMetaScMidiMessage] =
    unapply(message).map(SetTempoMetaScMidiMessage.apply)
}

/**
 * Represents an SMPTE Offset SMF meta event (type `0x54`).
 *
 * @param hour            Hour byte (includes frame-rate encoding per SMF spec; 0-255).
 * @param minute          Minute (0-255 as stored; typically 0-59).
 * @param second          Second (0-255 as stored; typically 0-59).
 * @param frame           Frame (0-255 as stored).
 * @param fractionalFrame Fractional frame, in hundredths (0-255 as stored; typically 0-99).
 */
case class SmpteOffsetMetaScMidiMessage(hour: Int,
                                        minute: Int,
                                        second: Int,
                                        frame: Int,
                                        fractionalFrame: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned8BitValue("hour", hour)
  MidiRequirements.requireUnsigned8BitValue("minute", minute)
  MidiRequirements.requireUnsigned8BitValue("second", second)
  MidiRequirements.requireUnsigned8BitValue("frame", frame)
  MidiRequirements.requireUnsigned8BitValue("fractionalFrame", fractionalFrame)

  override lazy val toJavaMidiMessage: MetaMessage = MetaScMidiMessage.build(
    SmpteOffsetMetaScMidiMessage.MetaType,
    Array(hour.toByte, minute.toByte, second.toByte, frame.toByte, fractionalFrame.toByte)
  )
}

/** Companion object for [[SmpteOffsetMetaScMidiMessage]]. */
object SmpteOffsetMetaScMidiMessage extends FromJavaMidiMessageConverter[SmpteOffsetMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x54

  /** Extracts the SMPTE offset fields if the given message is an SMPTE Offset meta event. */
  def unapply(message: MidiMessage): Option[(Int, Int, Int, Int, Int)] = message match {
    case m: MetaMessage if m.getType == MetaType =>
      val d = m.getData
      Some((d(0) & 0xFF, d(1) & 0xFF, d(2) & 0xFF, d(3) & 0xFF, d(4) & 0xFF))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[SmpteOffsetMetaScMidiMessage] =
    unapply(message).map { t => SmpteOffsetMetaScMidiMessage.apply.tupled(t) }
}

/**
 * Represents a Time Signature SMF meta event (type `0x58`).
 *
 * @param numerator                        Time signature numerator.
 * @param denominatorPowerOf2              Power-of-2 exponent for the denominator (e.g. 2 means denominator 4).
 * @param midiClocksPerMetronomeTick       MIDI clocks per metronome tick.
 * @param thirtySecondNotesPer24MidiClocks Number of 32nd notes per 24 MIDI clocks (usually 8).
 */
case class TimeSignatureMetaScMidiMessage(numerator: Int,
                                          denominatorPowerOf2: Int,
                                          midiClocksPerMetronomeTick: Int,
                                          thirtySecondNotesPer24MidiClocks: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned8BitValue("numerator", numerator)
  MidiRequirements.requireUnsigned8BitValue("denominatorPowerOf2", denominatorPowerOf2)
  MidiRequirements.requireUnsigned8BitValue("midiClocksPerMetronomeTick", midiClocksPerMetronomeTick)
  MidiRequirements.requireUnsigned8BitValue("thirtySecondNotesPer24MidiClocks", thirtySecondNotesPer24MidiClocks)

  override lazy val toJavaMidiMessage: MetaMessage = MetaScMidiMessage.build(
    TimeSignatureMetaScMidiMessage.MetaType,
    Array(
      numerator.toByte,
      denominatorPowerOf2.toByte,
      midiClocksPerMetronomeTick.toByte,
      thirtySecondNotesPer24MidiClocks.toByte
    )
  )
}

/** Companion object for [[TimeSignatureMetaScMidiMessage]]. */
object TimeSignatureMetaScMidiMessage extends FromJavaMidiMessageConverter[TimeSignatureMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x58

  /** Extracts the time signature fields if the given message is a Time Signature meta event. */
  def unapply(message: MidiMessage): Option[(Int, Int, Int, Int)] = message match {
    case m: MetaMessage if m.getType == MetaType =>
      val d = m.getData
      Some((d(0) & 0xFF, d(1) & 0xFF, d(2) & 0xFF, d(3) & 0xFF))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[TimeSignatureMetaScMidiMessage] =
    unapply(message).map { t => TimeSignatureMetaScMidiMessage.apply.tupled(t) }
}

/**
 * Represents a Key Signature SMF meta event (type `0x59`).
 *
 * @param sharpsOrFlats Number of sharps (positive) or flats (negative); range -7 to 7.
 * @param mode          Whether the key is major or minor.
 */
case class KeySignatureMetaScMidiMessage(sharpsOrFlats: Int, mode: ScMidiKeySignatureMode) extends ScMidiMessage {
  require(sharpsOrFlats >= -7 && sharpsOrFlats <= 7,
    s"sharpsOrFlats must be between -7 and 7; got $sharpsOrFlats")

  override lazy val toJavaMidiMessage: MetaMessage = MetaScMidiMessage.build(
    KeySignatureMetaScMidiMessage.MetaType,
    Array(sharpsOrFlats.toByte, (if (mode == ScMidiKeySignatureMode.Minor) 1 else 0).toByte)
  )
}

/** Companion object for [[KeySignatureMetaScMidiMessage]]. */
object KeySignatureMetaScMidiMessage extends FromJavaMidiMessageConverter[KeySignatureMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x59

  /** Extracts the key signature fields if the given message is a Key Signature meta event. */
  def unapply(message: MidiMessage): Option[(Int, ScMidiKeySignatureMode)] = message match {
    case m: MetaMessage if m.getType == MetaType =>
      val d = m.getData
      val sharps = d(0).toInt // signed
      val mode = if ((d(1) & 0xFF) == 1) ScMidiKeySignatureMode.Minor else ScMidiKeySignatureMode.Major
      Some((sharps, mode))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[KeySignatureMetaScMidiMessage] =
    unapply(message).map { t => KeySignatureMetaScMidiMessage.apply.tupled(t) }
}

/**
 * Represents a Sequencer-Specific SMF meta event (type `0x7F`).
 *
 * @param data Opaque sequencer-specific payload.
 */
case class SequencerSpecificMetaScMidiMessage(data: ArraySeq[Byte]) extends ScMidiMessage {
  override lazy val toJavaMidiMessage: MetaMessage =
    MetaScMidiMessage.build(SequencerSpecificMetaScMidiMessage.MetaType, data.toArray)
}

/** Companion object for [[SequencerSpecificMetaScMidiMessage]]. */
object SequencerSpecificMetaScMidiMessage extends FromJavaMidiMessageConverter[SequencerSpecificMetaScMidiMessage] {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x7F

  /** Extracts the payload bytes if the given message is a Sequencer-Specific meta event. */
  def unapply(message: MidiMessage): Option[ArraySeq[Byte]] = message match {
    case m: MetaMessage if m.getType == MetaType => Some(ArraySeq.unsafeWrapArray(m.getData))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[SequencerSpecificMetaScMidiMessage] =
    unapply(message).map(SequencerSpecificMetaScMidiMessage.apply)
}

// ============================================================================
// Fallback
// ============================================================================

/** Wraps a [[javax.sound.midi.MidiMessage]] that has no dedicated Scala-idiomatic counterpart. */
case class UnsupportedScMidiMessage(override val toJavaMidiMessage: MidiMessage) extends ScMidiMessage
