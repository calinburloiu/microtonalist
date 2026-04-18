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

import org.calinburloiu.music.scmidi.PitchBendSensitivity

import javax.sound.midi.{MidiMessage, ShortMessage}

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
