/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.calinburloiu.music.scmidi.{PitchBendSensitivity, ScPitchBendMidiMessage}

import java.nio.ByteBuffer
import javax.sound.midi.{ShortMessage, SysexMessage}

/**
 * Represents a generator for MIDI Tuning Standard (MTS) messages based on a given tuning.
 *
 * This trait defines the ability to generate a SysEx (System Exclusive) MIDI message to specify
 * the tuning for a musical instrument. The tuning is defined in terms of the offset (in cents)
 * for each pitch class in an equal-tempered 12-tone scale.
 */
trait MtsMessageGenerator {
  def generate(tuning: Tuning): SysexMessage
}

/**
 * Abstract class that generates MIDI Tuning Standard (MTS) SysEx messages for octave-based tunings.
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

  private val headerBytes: Array[Byte] = Array(
    SysexMessage.SYSTEM_EXCLUSIVE.toByte,
    realTimeByte,
    deviceId,
    HeaderByte_Mts,
    form
  )

  override def generate(tuning: Tuning): SysexMessage = {
    val buffer = ByteBuffer.allocate(byteCount)

    // # Header
    buffer.put(headerBytes)
    buffer.put(HeaderBytes_AllChannels)

    // # Tuning Values
    for (tuningValue <- tuning.offsets) {
      putTuningValue(buffer, tuningValue)
    }

    // # Footer
    buffer.put(ShortMessage.END_OF_EXCLUSIVE.toByte)

    val data = buffer.array()
    val sysexMessage = new SysexMessage(data, data.length)

    sysexMessage
  }

  private def put1ByteTuningValue(buffer: ByteBuffer, tuningValue: Double): Unit = {
    val nTuningValue = Math.min(Math.max(minTuningOutputValue, tuningValue.round.toInt), maxTuningOutputValue)
    // Subtracting the min value to make the output value 0 for it
    val tuningValueByte = (nTuningValue - minTuningOutputValue).toByte

    buffer.put(tuningValueByte)
  }

  private def put2ByteTuningValue(buffer: ByteBuffer, tuningValue: Double): Unit = {
    val (lsb, msb) = convertTuningValueToBytes(tuningValue)

    buffer.put(msb)
    buffer.put(lsb)
  }
}

private[tuner] object MtsOctaveMessageGenerator {
  private val HeaderBytes_AllChannels: Array[Byte] = Array(0x03.toByte, 0x7F.toByte, 0x7F.toByte)

  private val semitonePitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(semitones = 1)

  @inline
  private def convertTuningValueToBytes(tuningValue: Double): (Byte, Byte) = {
    val (lsb, msb) = ScPitchBendMidiMessage.convertCentsToDataBytes(tuningValue, semitonePitchBendSensitivity)
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
