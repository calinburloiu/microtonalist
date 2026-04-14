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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.ScNoteOnMidiMessage.DefaultVelocity

import javax.sound.midi.{MidiMessage, ShortMessage}

/**
 * Scala-idiomatic base trait for MIDI messages, wrapping Java's [[javax.sound.midi.MidiMessage]] hierarchy.
 *
 * Unlike Java's [[javax.sound.midi.MidiMessage]] (and its subclasses like [[javax.sound.midi.ShortMessage]]),
 * which expose raw byte data and mutable state, `ScMidiMessage` subtypes are immutable case classes with named,
 * validated parameters, Scala pattern matching support via `unapply`, and convenient factory methods for
 * bidirectional conversion with Java messages.
 */
trait ScMidiMessage {
  /** The underlying [[javax.sound.midi.MidiMessage]]. */
  def javaMessage: MidiMessage
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
    ShortMessage.NOTE_ON -> ScNoteOnMidiMessage.fromJavaMessage.unlift,
    ShortMessage.NOTE_OFF -> ScNoteOffMidiMessage.fromJavaMessage.unlift,
    ShortMessage.PITCH_BEND -> ScPitchBendMidiMessage.fromJavaMessage.unlift,
    ShortMessage.CONTROL_CHANGE -> ScCcMidiMessage.fromJavaMessage.unlift,
    ShortMessage.CHANNEL_PRESSURE -> ScChannelPressureMidiMessage.fromJavaMessage.unlift,
    ShortMessage.POLY_PRESSURE -> ScPolyPressureMidiMessage.fromJavaMessage.unlift,
    ShortMessage.PROGRAM_CHANGE -> ScProgramChangeMidiMessage.fromJavaMessage.unlift
  ).withDefaultValue(ScUnsupportedMidiMessage.apply)

  /**
   * Converts a Java [[MidiMessage]] to the corresponding [[ScMidiMessage]] subtype.
   *
   * Supported message types are mapped to their Scala-idiomatic counterparts (e.g.
   * [[javax.sound.midi.ShortMessage]] with command `NOTE_ON` becomes [[ScNoteOnMidiMessage]]).
   * Unrecognized messages are wrapped in [[ScUnsupportedMidiMessage]].
   *
   * @param message The Java [[MidiMessage]] to convert. Must not be null.
   * @return The [[ScMidiMessage]] instance.
   * @throws IllegalArgumentException if the message is null.
   */
  def fromJavaMessage(message: MidiMessage): ScMidiMessage = {
    require(message != null, "message must not be null")

    message match {
      case shortMessage: ShortMessage => FromJavaMessageMap(shortMessage.getCommand)(message)
      case _ => ScUnsupportedMidiMessage(message)
    }
  }
}

/** Wraps a [[javax.sound.midi.MidiMessage]] that has no dedicated Scala-idiomatic counterpart. */
case class ScUnsupportedMidiMessage(override val javaMessage: MidiMessage) extends ScMidiMessage

/**
 * Base class for Note On and Note Off MIDI messages, replacing Java's overloaded use of
 * [[javax.sound.midi.ShortMessage]] with typed `channel`, `midiNote`, and `velocity` parameters
 * that are validated on construction.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note.
 * @param velocity The velocity (0-127).
 */
abstract class ScNoteMidiMessage(val channel: Int,
                                 val midiNote: MidiNote,
                                 val velocity: Int = DefaultVelocity) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  midiNote.assertValid()
  MidiRequirements.requireUnsigned7BitValue("velocity", velocity)

  override def javaMessage: ShortMessage = new ShortMessage(midiCommand, channel, midiNote.number, velocity)

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
case class ScNoteOnMidiMessage(override val channel: Int,
                               override val midiNote: MidiNote,
                               override val velocity: Int = DefaultVelocity)
  extends ScNoteMidiMessage(channel, midiNote, velocity) {
  override protected val midiCommand: Int = ShortMessage.NOTE_ON
}

/**
 * Companion object for [[ScNoteOnMidiMessage]].
 */
object ScNoteOnMidiMessage extends FromJavaMidiMessageConverter[ScNoteOnMidiMessage] {
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

  override def fromJavaMessage(message: MidiMessage): Option[ScNoteOnMidiMessage] =
    unapply(message).map { tuple => ScNoteOnMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Note Off message.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note.
 * @param velocity The velocity (0-127).
 */
case class ScNoteOffMidiMessage(override val channel: Int,
                                override val midiNote: MidiNote,
                                override val velocity: Int = DefaultVelocity)
  extends ScNoteMidiMessage(channel, midiNote, velocity) {
  override protected val midiCommand: Int = ShortMessage.NOTE_OFF
}

/**
 * Companion object for [[ScNoteOffMidiMessage]] used for pattern matching and default values.
 */
object ScNoteOffMidiMessage extends FromJavaMidiMessageConverter[ScNoteOffMidiMessage] {
  /** The default velocity for Note Off messages (64). */
  val DefaultVelocity: Int = 0x40

  /** Extracts the channel, MIDI note, and velocity from a [[MidiMessage]] if it is a Note Off message. */
  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.NOTE_OFF =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ScNoteOffMidiMessage] =
    unapply(message).map { tuple => ScNoteOffMidiMessage.apply.tupled(tuple) }
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
case class ScPitchBendMidiMessage(channel: Int, value: Int) extends ScMidiMessage {

  import ScPitchBendMidiMessage.*

  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireSigned14BitValue("value", value)

  override lazy val javaMessage: ShortMessage = {
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
 * Companion object for [[ScPitchBendMidiMessage]].
 */
object ScPitchBendMidiMessage extends FromJavaMidiMessageConverter[ScPitchBendMidiMessage] {
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

  override def fromJavaMessage(message: MidiMessage): Option[ScPitchBendMidiMessage] =
    unapply(message).map { tuple => ScPitchBendMidiMessage.apply.tupled(tuple) }

  /** Creates an [[ScPitchBendMidiMessage]] from a value in cents. */
  def fromCents(channel: Int,
                cents: Int,
                pitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default): ScPitchBendMidiMessage = {
    val value = convertCentsToValue(cents, pitchBendSensitivity)
    ScPitchBendMidiMessage(channel, value)
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

/**
 * Represents a MIDI Control Change (CC) message with named, validated `number` and `value` parameters.
 *
 * The companion object provides constants for commonly used controller numbers (e.g. [[ScCcMidiMessage.SustainPedal]],
 * [[ScCcMidiMessage.RpnMsb]]), avoiding the magic numbers typical of raw Java MIDI code.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param number  The controller number (0-127).
 * @param value   The controller value (0-127).
 */
case class ScCcMidiMessage(channel: Int, number: Int, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("number", number)
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val javaMessage: ShortMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number,
    value)
}

/**
 * Companion object for [[ScCcMidiMessage]].
 */
object ScCcMidiMessage extends FromJavaMidiMessageConverter[ScCcMidiMessage] {
  /** Registered Parameter Number (RPN) MSB controller number (#101). */
  val RpnMsb: Int = 101
  /** Registered Parameter Number (RPN) LSB controller number (#100). */
  val RpnLsb: Int = 100
  /** Data Entry MSB controller number (#6). */
  val DataEntryMsb: Int = 6
  /** Data Entry LSB controller number (#38). */
  val DataEntryLsb: Int = 38
  /** Data Increment controller number (#96). */
  val DataIncrement: Int = 96
  /** Data Decrement controller number (#97). */
  val DataDecrement: Int = 97
  /** All Sound Off controller number (#120). */
  val AllSoundOff: Int = 120
  /** Reset All Controllers controller number (#121). */
  val ResetAllControllers: Int = 121
  /** All Notes Off controller number (#123). */
  val AllNotesOff: Int = 123

  /** Bank Select MSB controller number (#0). */
  val BankSelectMsb: Int = 0
  /** Bank Select LSB controller number (#32). */
  val BankSelectLsb: Int = 32

  /** Modulation Wheel controller number (#1). */
  val Modulation: Int = 1
  /** Sustain Pedal (Damper) controller number (#64). */
  val SustainPedal: Int = 64
  /** Sostenuto Pedal controller number (#66). */
  val SostenutoPedal: Int = 66
  /** Soft Pedal controller number (#67). */
  val SoftPedal: Int = 67

  /**
   * Represents the MPE (MIDI Polyphonic Expression) Slide controller number (#74), also known as Timbre or Brightness.
   */
  val MpeSlide: Int = 74

  /** Extracts the channel, controller number, and value from a [[MidiMessage]] if it is a Control Change message. */
  def unapply(message: MidiMessage): Option[(Int, Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CONTROL_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ScCcMidiMessage] =
    unapply(message).map { tuple => ScCcMidiMessage.apply.tupled(tuple) }
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
case class ScProgramChangeMidiMessage(channel: Int, program: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("program", program)

  override lazy val javaMessage: ShortMessage =
    new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0)
}

/**
 * Companion object for [[ScProgramChangeMidiMessage]].
 */
object ScProgramChangeMidiMessage extends FromJavaMidiMessageConverter[ScProgramChangeMidiMessage] {
  /** Extracts the channel and program number from a [[MidiMessage]] if it is a Program Change message. */
  def unapply(message: MidiMessage): Option[(Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.PROGRAM_CHANGE =>
      Some((shortMessage.getChannel, shortMessage.getData1))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ScProgramChangeMidiMessage] =
    unapply(message).map { tuple => ScProgramChangeMidiMessage.apply.tupled(tuple) }
}

/**
 * MIDI Registered Parameter Numbers (RPN) utilities and constants.
 */
object Rpn {
  /** Pitch Bend Sensitivity RPN MSB (#0). */
  val PitchBendSensitivityMsb: Int = 0x00
  /** Pitch Bend Sensitivity RPN LSB (#0). */
  val PitchBendSensitivityLsb: Int = 0x00

  /** Coarse Tuning RPN MSB (#0). */
  val CoarseTuningMsb: Int = 0x00
  /** Coarse Tuning RPN LSB (#2). */
  val CoarseTuningLsb: Int = 0x02

  /** Fine Tuning RPN MSB (#0). */
  val FineTuningMsb: Int = 0x00
  /** Fine Tuning RPN LSB (#1). */
  val FineTuningLsb: Int = 0x01

  /** Tuning Bank Select RPN MSB (#0). */
  val TuningBankSelectMsb: Int = 0x00
  /** Tuning Bank Select RPN LSB (#4). */
  val TuningBankSelectLsb: Int = 0x04

  /** Tuning Program Select RPN MSB (#0). */
  val TuningProgramSelectMsb: Int = 0x00
  /** Tuning Program Select RPN LSB (#3). */
  val TuningProgramSelectLsb: Int = 0x03

  val MpeConfigurationMessageLsb: Int = 0x06

  val MpeConfigurationMessageMsb: Int = 0x00

  /** Null RPN MSB (#127). */
  val NullMsb: Int = 0x7F
  /** Null RPN LSB (#127). */
  val NullLsb: Int = 0x7F
}

/**
 * Represents a MIDI Channel Pressure (Aftertouch) message with a validated `value` parameter.
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 * @param value   The pressure value (0-127).
 */
case class ScChannelPressureMidiMessage(channel: Int, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val javaMessage: ShortMessage = new ShortMessage(ShortMessage.CHANNEL_PRESSURE, channel, value, 0)
}

/**
 * Companion object for [[ScChannelPressureMidiMessage]].
 */
object ScChannelPressureMidiMessage extends FromJavaMidiMessageConverter[ScChannelPressureMidiMessage] {
  def unapply(message: MidiMessage): Option[(Int, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.CHANNEL_PRESSURE =>
      Some((shortMessage.getChannel, shortMessage.getData1))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ScChannelPressureMidiMessage] =
    unapply(message).map { tuple => ScChannelPressureMidiMessage.apply.tupled(tuple) }
}

/**
 * Represents a MIDI Polyphonic Key Pressure (Poly Aftertouch) message with typed `midiNote` and validated `value`
 * parameters.
 *
 * @param channel  The 0-indexed MIDI channel (0-15).
 * @param midiNote The MIDI note to which the pressure applies.
 * @param value    The pressure value (0-127).
 */
case class ScPolyPressureMidiMessage(channel: Int, midiNote: MidiNote, value: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
  midiNote.assertValid()
  MidiRequirements.requireUnsigned7BitValue("value", value)

  override lazy val javaMessage: ShortMessage =
    new ShortMessage(ShortMessage.POLY_PRESSURE, channel, midiNote.number, value)
}

/**
 * Companion object for [[ScPolyPressureMidiMessage]].
 */
object ScPolyPressureMidiMessage extends FromJavaMidiMessageConverter[ScPolyPressureMidiMessage] {
  def unapply(message: MidiMessage): Option[(Int, MidiNote, Int)] = message match {
    case shortMessage: ShortMessage if shortMessage.getCommand == ShortMessage.POLY_PRESSURE =>
      Some((shortMessage.getChannel, shortMessage.getData1, shortMessage.getData2))
    case _ => None
  }

  override def fromJavaMessage(message: MidiMessage): Option[ScPolyPressureMidiMessage] =
    unapply(message).map { tuple => ScPolyPressureMidiMessage.apply.tupled(tuple) }
}

/**
 * MIDI value validation and requirement utility.
 */
object MidiRequirements {
  /** The minimum signed 14-bit value (-8192). */
  val MinSigned14BitValue: Int = -(1 << 13)
  /** The maximum signed 14-bit value (8191). */
  val MaxSigned14BitValue: Int = (1 << 13) - 1

  /** Requires that the given channel is between 0 and 15. */
  def requireChannel(channel: Int): Unit =
    require((channel & 0xFFFFFFF0) == 0, s"channel must be between 0 and 15; got $channel")

  /** Requires that the given value is an unsigned 7-bit integer (0 to 127). */
  def requireUnsigned7BitValue(name: String, value: Int): Unit =
    require((value & 0xFFFFFF80) == 0, s"$name must be between 0 and 127; got $value")

  /** Requires that the given value is a signed 14-bit integer (-8192 to 8191). */
  def requireSigned14BitValue(name: String, value: Int): Unit = {
    require(
      value >= MinSigned14BitValue && value <= MaxSigned14BitValue,
      s"$name must be between $MinSigned14BitValue and $MaxSigned14BitValue; got $value"
    )
  }
}
