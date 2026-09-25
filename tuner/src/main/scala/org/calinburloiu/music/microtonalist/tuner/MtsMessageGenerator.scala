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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.{PitchBendSensitivity, clampValue}
import org.calinburloiu.music.scmidi.message.{PitchBendMidiMsg, SysExMidiMsg}

import java.nio.ByteBuffer
import scala.collection.immutable.ArraySeq

/**
 * Represents a generator for MIDI Tuning Standard (MTS) messages based on a given tuning.
 *
 * This trait defines the ability to generate a SysEx (System Exclusive) MIDI message to specify
 * the tuning for a musical instrument. The tuning is defined in terms of the offset (in cents)
 * for each pitch class in an equal-tempered 12-tone scale.
 */
trait MtsMessageGenerator {
  /**
   * Tells whether [[generate]] can encode every offset of the given tuning exactly, without clamping it to the range
   * of a tuning value in the message.
   *
   * @param tuning The tuning instance that specifies the offset in cents for each of the 12 pitch classes in the
   *               octave.
   * @return `true` if every offset of the tuning is within the range of a tuning value, `false` otherwise.
   */
  def canEncode(tuning: Tuning): Boolean

  /**
   * Generates the MTS SysEx message that tunes an instrument to the given tuning.
   *
   * An offset beyond the range of a tuning value in the message, for which [[canEncode]] returns `false`, is clamped
   * to that range.
   *
   * @param tuning The tuning instance that specifies the offset in cents for each of the 12 pitch classes in the
   *               octave.
   * @return the MTS SysEx message.
   */
  def generate(tuning: Tuning): SysExMidiMsg
}

/**
 * Abstract class that generates MIDI Tuning Standard (MTS) SysEx messages for octave-based tunings.
 *
 * In the 1-byte form, a tuning value is a whole number of cents from -64 to +63, to which an offset is rounded. In the
 * 2-byte form, it is a number of cents from -100 to +100.
 *
 * @param isRealTime    Specifies whether the generated SysEx message is real-time or non-real-time.
 * @param isIn2ByteForm Indicates whether tuning values are encoded using the 2-byte or the 1-byte form.
 */
abstract class MtsOctaveMessageGenerator(val isRealTime: Boolean,
                                         val isIn2ByteForm: Boolean) extends MtsMessageGenerator {

  import MtsMessageGenerator.*
  import MtsOctaveMessageGenerator.*

  private val minTuningOutputValue: Int = if (isIn2ByteForm) -8192 else -64
  private val maxTuningOutputValue: Int = if (isIn2ByteForm) 8191 else 63
  private val realTimeByte: Byte = if (isRealTime) 0x7F.toByte else 0x7E.toByte
  private val deviceId: Byte = HeaderByte_AllDevices
  private val form: Byte = if (isIn2ByteForm) 0x09.toByte else 0x08.toByte
  private val byteCount = if (isIn2ByteForm) 33 else 21
  private val putTuningValue: (ByteBuffer, Double) => Unit =
    if (isIn2ByteForm) put2ByteTuningValue else put1ByteTuningValue
  private val canEncodeTuningValue: Double => Boolean =
    if (isIn2ByteForm) canEncode2ByteTuningValue else canEncode1ByteTuningValue

  override def canEncode(tuning: Tuning): Boolean = tuning.offsets.forall(canEncodeTuningValue)

  private val headerBytes: Array[Byte] = Array(
    SysExMidiMsg.StatusByte,
    realTimeByte,
    deviceId,
    HeaderByte_Mts,
    form
  )

  override def generate(tuning: Tuning): SysExMidiMsg = {
    val buffer = ByteBuffer.allocate(byteCount)

    // # Header
    buffer.put(headerBytes)
    buffer.put(HeaderBytes_AllChannels)

    // # Tuning Values
    for (tuningValue <- tuning.offsets) {
      putTuningValue(buffer, tuningValue)
    }

    // # Footer
    buffer.put(SysExMidiMsg.EndOfExclusiveByte)

    SysExMidiMsg(ArraySeq.unsafeWrapArray(buffer.array()))
  }

  private def canEncode1ByteTuningValue(tuningValue: Double): Boolean = {
    val roundedTuningValue = tuningValue.round
    minTuningOutputValue <= roundedTuningValue && roundedTuningValue <= maxTuningOutputValue
  }

  private def canEncode2ByteTuningValue(tuningValue: Double): Boolean =
    Math.abs(tuningValue) <= semitonePitchBendSensitivity.totalCents

  private def put1ByteTuningValue(buffer: ByteBuffer, tuningValue: Double): Unit = {
    val nTuningValue = Math.min(Math.max(minTuningOutputValue, tuningValue.round.toInt), maxTuningOutputValue)
    // Subtracting the min value to make the output value 0 for it
    val tuningValueByte = (nTuningValue - minTuningOutputValue).toByte

    buffer.put(tuningValueByte)
  }

  private def put2ByteTuningValue(buffer: ByteBuffer, tuningValue: Double): Unit = {
    val maxTuningValue = semitonePitchBendSensitivity.totalCents
    val (lsb, msb) = convertTuningValueToBytes(clampValue(tuningValue, -maxTuningValue, maxTuningValue))

    buffer.put(msb)
    buffer.put(lsb)
  }
}

private[tuner] object MtsOctaveMessageGenerator {
  private val HeaderBytes_AllChannels: Array[Byte] = Array(0x03.toByte, 0x7F.toByte, 0x7F.toByte)

  private val semitonePitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(semitones = 1)

  @inline
  private def convertTuningValueToBytes(tuningValue: Double): (Byte, Byte) = {
    val (lsb, msb) = PitchBendMidiMsg.convertCentsToDataBytes(tuningValue, semitonePitchBendSensitivity)
    (lsb.toByte, msb.toByte)
  }
}

/**
 * An object containing predefined implementations of MIDI Tuning Standard (MTS) message generators.
 *
 * This object provides specific generators for creating MTS SysEx messages for octave-based tunings,
 * with varying configurations such as real-time or non-real-time message type and 1-byte or 2-byte forms for each
 * tuning value.
 *
 * @see [[MtsMessageGenerator]]
 */
object MtsMessageGenerator {

  private[tuner] val HeaderByte_AllDevices: Byte = 0x7F.toByte
  private[tuner] val HeaderByte_Mts: Byte = 0x08.toByte

  /**
   * Generates a non-real-time MIDI Tuning Standard (MTS) SysEx message for octave-based tunings,
   * where tuning values are encoded using the 1-byte form.
   */
  case object Octave1ByteNonRealTime
    extends MtsOctaveMessageGenerator(isRealTime = false, isIn2ByteForm = false)

  /**
   * Generates a non-real-time MIDI Tuning Standard (MTS) SysEx message for octave-based tunings,
   * where tuning values are encoded using the 2-byte form.
   */
  case object Octave2ByteNonRealTime
    extends MtsOctaveMessageGenerator(isRealTime = false, isIn2ByteForm = true)

  /**
   * Generates a real-time MIDI Tuning Standard (MTS) SysEx message for octave-based tunings,
   * where tuning values are encoded using the 1-byte form.
   */
  case object Octave1ByteRealTime
    extends MtsOctaveMessageGenerator(isRealTime = true, isIn2ByteForm = false)

  /**
   * Generates a real-time MIDI Tuning Standard (MTS) SysEx message for octave-based tunings,
   * where tuning values are encoded using the 2-byte form.
   */
  case object Octave2ByteRealTime
    extends MtsOctaveMessageGenerator(isRealTime = true, isIn2ByteForm = true)
}
