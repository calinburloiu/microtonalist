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

import scala.collection.immutable.ArraySeq

/**
 * Scala-idiomatic base trait for MIDI messages, wrapping Java's [[javax.sound.midi.MidiMessage]] hierarchy.
 *
 * Unlike Java's [[javax.sound.midi.MidiMessage]] (and its subclasses like [[javax.sound.midi.ShortMessage]]),
 * which expose raw byte data and mutable state, `ScMidiMessage` subtypes are immutable case classes with named,
 * validated parameters and Scala pattern matching support.
 *
 * Use [[JavaMidiConverters]] to convert between `ScMidiMessage` and [[javax.sound.midi.MidiMessage]].
 */
sealed trait ScMidiMessage

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
  extends NoteScMidiMessage(channel, midiNote, velocity)

/**
 * Companion object for [[NoteOnScMidiMessage]].
 */
object NoteOnScMidiMessage {
  /** The velocity value representing a Note Off via Note On (0). */
  val NoteOffVelocity: Int = 0x00
  /** The default velocity for Note On messages (64). */
  val DefaultVelocity: Int = 0x40
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
  extends NoteScMidiMessage(channel, midiNote, velocity)

/**
 * Companion object for [[NoteOffScMidiMessage]] used for default values.
 */
object NoteOffScMidiMessage {
  /** The default velocity for Note Off messages (64). */
  val DefaultVelocity: Int = 0x40
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
object PitchBendScMidiMessage {
  /** The minimum signed 14-bit pitch bend value (-8192). */
  val MinValue: Int = MidiRequirements.MinSigned14BitValue
  /** The value representing no pitch bend (0). */
  val NoPitchBendValue: Int = 0
  /** The maximum signed 14-bit pitch bend value (8191). */
  val MaxValue: Int = MidiRequirements.MaxSigned14BitValue

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
}

/**
 * Represents a MIDI Song Position Pointer message, carrying a 14-bit position in MIDI beats
 * (one MIDI beat = six MIDI clocks).
 *
 * @param position The song position in MIDI beats (0-16383).
 */
case class SongPositionPointerScMidiMessage(position: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned14BitValue("position", position)
}

/**
 * Represents a MIDI Song Select message, selecting which song or sequence is to be played.
 *
 * @param song The song number (0-127).
 */
case class SongSelectScMidiMessage(song: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned7BitValue("song", song)
}

/**
 * Represents a MIDI Tune Request message, requesting that analog synthesizers retune their oscillators.
 */
case object TuneRequestScMidiMessage extends ScMidiMessage

// ============================================================================
// System Real-Time Messages
// ============================================================================

/** Represents a MIDI Timing Clock message, transmitted at a rate of 24 per quarter note while playing. */
case object TimingClockScMidiMessage extends ScMidiMessage

/** Represents a MIDI Start message, instructing the receiver to start playback from the beginning. */
case object StartScMidiMessage extends ScMidiMessage

/** Represents a MIDI Continue message, instructing the receiver to resume playback from the current position. */
case object ContinueScMidiMessage extends ScMidiMessage

/** Represents a MIDI Stop message, instructing the receiver to stop playback. */
case object StopScMidiMessage extends ScMidiMessage

/**
 * Represents a MIDI Active Sensing message, sent every 300ms or less to indicate that the connection is still active.
 */
case object ActiveSensingScMidiMessage extends ScMidiMessage

/** Represents a MIDI System Reset message, instructing the receiver to reset to its power-up state. */
case object SystemResetScMidiMessage extends ScMidiMessage

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
case class SysexScMidiMessage(data: ArraySeq[Byte]) extends ScMidiMessage

// ============================================================================
// Meta Messages (Standard MIDI File)
// ============================================================================

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
}

/** Companion object for [[SequenceNumberMetaScMidiMessage]]. */
object SequenceNumberMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x00
}

/**
 * Base case for text-bearing SMF meta events.
 */
sealed abstract class TextBearingMetaScMidiMessage extends ScMidiMessage

/** Represents a Text SMF meta event (type `0x01`). */
case class TextMetaScMidiMessage(text: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[TextMetaScMidiMessage]]. */
object TextMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x01
}

/** Represents a Copyright Notice SMF meta event (type `0x02`). */
case class CopyrightNoticeMetaScMidiMessage(text: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[CopyrightNoticeMetaScMidiMessage]]. */
object CopyrightNoticeMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x02
}

/** Represents a Track Name SMF meta event (type `0x03`). */
case class TrackNameMetaScMidiMessage(name: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[TrackNameMetaScMidiMessage]]. */
object TrackNameMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x03
}

/** Represents an Instrument Name SMF meta event (type `0x04`). */
case class InstrumentNameMetaScMidiMessage(name: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[InstrumentNameMetaScMidiMessage]]. */
object InstrumentNameMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x04
}

/** Represents a Lyric SMF meta event (type `0x05`). */
case class LyricMetaScMidiMessage(text: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[LyricMetaScMidiMessage]]. */
object LyricMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x05
}

/** Represents a Marker SMF meta event (type `0x06`). */
case class MarkerMetaScMidiMessage(text: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[MarkerMetaScMidiMessage]]. */
object MarkerMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x06
}

/** Represents a Cue Point SMF meta event (type `0x07`). */
case class CuePointMetaScMidiMessage(text: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[CuePointMetaScMidiMessage]]. */
object CuePointMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x07
}

/** Represents a Program Name SMF meta event (type `0x08`). */
case class ProgramNameMetaScMidiMessage(name: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[ProgramNameMetaScMidiMessage]]. */
object ProgramNameMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x08
}

/** Represents a Device Name SMF meta event (type `0x09`). */
case class DeviceNameMetaScMidiMessage(name: String) extends TextBearingMetaScMidiMessage

/** Companion object for [[DeviceNameMetaScMidiMessage]]. */
object DeviceNameMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x09
}

/**
 * Represents a MIDI Channel Prefix SMF meta event (type `0x20`).
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
case class MidiChannelPrefixMetaScMidiMessage(channel: Int) extends ScMidiMessage {
  MidiRequirements.requireChannel(channel)
}

/** Companion object for [[MidiChannelPrefixMetaScMidiMessage]]. */
object MidiChannelPrefixMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x20
}

/**
 * Represents a MIDI Port SMF meta event (type `0x21`).
 *
 * @param port The MIDI port number (0-127).
 */
case class MidiPortMetaScMidiMessage(port: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned7BitValue("port", port)
}

/** Companion object for [[MidiPortMetaScMidiMessage]]. */
object MidiPortMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x21
}

/** Represents the End Of Track SMF meta event (type `0x2F`). */
case object EndOfTrackMetaScMidiMessage extends ScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x2F
}

/**
 * Represents a Set Tempo SMF meta event (type `0x51`).
 *
 * @param microsecondsPerQuarterNote Tempo in microseconds per quarter note (0 to 16777215, 24-bit).
 */
case class SetTempoMetaScMidiMessage(microsecondsPerQuarterNote: Int) extends ScMidiMessage {
  MidiRequirements.requireUnsigned24BitValue("microsecondsPerQuarterNote", microsecondsPerQuarterNote)
}

/** Companion object for [[SetTempoMetaScMidiMessage]]. */
object SetTempoMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x51
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
}

/** Companion object for [[SmpteOffsetMetaScMidiMessage]]. */
object SmpteOffsetMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x54
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
}

/** Companion object for [[TimeSignatureMetaScMidiMessage]]. */
object TimeSignatureMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x58
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
}

/** Companion object for [[KeySignatureMetaScMidiMessage]]. */
object KeySignatureMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x59
}

/**
 * Represents a Sequencer-Specific SMF meta event (type `0x7F`).
 *
 * @param data Opaque sequencer-specific payload.
 */
case class SequencerSpecificMetaScMidiMessage(data: ArraySeq[Byte]) extends ScMidiMessage

/** Companion object for [[SequencerSpecificMetaScMidiMessage]]. */
object SequencerSpecificMetaScMidiMessage {
  /** The SMF meta event type byte. */
  val MetaType: Int = 0x7F
}

// ============================================================================
// Fallback
// ============================================================================

/**
 * Wraps the raw bytes of a [[javax.sound.midi.MidiMessage]] that has no dedicated Scala-idiomatic counterpart.
 *
 * [[JavaMidiConverters]] can reconstruct the original Java message (a `ShortMessage`, `SysexMessage`, or
 * `MetaMessage`, detected from the status byte) via `asJava`.
 *
 * @param data The full byte sequence of the original Java `MidiMessage` (as returned by `MidiMessage.getMessage`).
 */
case class UnsupportedScMidiMessage(data: ArraySeq[Byte]) extends ScMidiMessage
