/*
 * Copyright 2021 Calin-Andrei Burloiu
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

import org.calinburloiu.music.microtonalist.composition.OctaveTuning

import java.nio.ByteBuffer
import javax.sound.midi.{ShortMessage, SysexMessage}

trait MtsMessageGenerator {
  def generate(tuning: OctaveTuning): SysexMessage
}

abstract class MtsOctaveMessageGenerator(val isRealTime: Boolean,
                                         val isIn2ByteForm: Boolean) extends MtsMessageGenerator {

  import MtsMessageGenerator._

  private val minTuningOutputValue: Int = if (isIn2ByteForm) -8192 else -64
  private val maxTuningOutputValue: Int = if (isIn2ByteForm) 8191 else 63
  private val realTimeByte: Byte = if (isRealTime) 0x7F.toByte else 0x7E.toByte
  private val deviceId: Byte = HeaderByte_AllDevices
  private val form: Byte = if (isIn2ByteForm) 0x09.toByte else 0x08.toByte
  private val byteCount = if (isIn2ByteForm) 33 else 21
  private val putTuningValue: (ByteBuffer, Double) => Unit = if (isIn2ByteForm) {
    put2ByteTuningValue
  } else {
    put1ByteTuningValue
  }

  private val headerBytes: Array[Byte] = Array(
    SysexMessage.SYSTEM_EXCLUSIVE.toByte,
    realTimeByte,
    deviceId,
    HeaderByte_Mts,
    form
  )

  override def generate(tuning: OctaveTuning): SysexMessage = {
    val buffer = ByteBuffer.allocate(byteCount)

    // # Header
    buffer.put(headerBytes)
    buffer.put(MtsOctaveMessageGenerator.HeaderBytes_AllChannels)

    // # Tuning Values
    for (tuningValue <- tuning.deviations) {
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
    val scaledTuningValue = -minTuningOutputValue / 100.0 * tuningValue
    val nScaledTuningValue = Math.min(
      Math.max(minTuningOutputValue, scaledTuningValue.round.toInt), maxTuningOutputValue)
    // Subtracting the min value to make the output value 0 for it
    val nTuningOutputValue = nScaledTuningValue - minTuningOutputValue
    val byte1 = (nTuningOutputValue >> 7).toByte
    val byte2 = (nTuningOutputValue & 0x7F).toByte

    buffer.put(byte1)
    buffer.put(byte2)
  }
}

private[tuner] object MtsOctaveMessageGenerator {
  private val HeaderBytes_AllChannels: Array[Byte] = Array(0x03.toByte, 0x7F.toByte, 0x7F.toByte)
}

object MtsMessageGenerator {

  private[tuner] val HeaderByte_AllDevices: Byte = 0x7F.toByte
  private[tuner] val HeaderByte_Mts: Byte = 0x08.toByte

  case object Octave1ByteNonRealTime
    extends MtsOctaveMessageGenerator(isRealTime = false, isIn2ByteForm = false)

  case object Octave2ByteNonRealTime
    extends MtsOctaveMessageGenerator(isRealTime = false, isIn2ByteForm = true)

  case object Octave1ByteRealTime
    extends MtsOctaveMessageGenerator(isRealTime = true, isIn2ByteForm = false)

  case object Octave2ByteRealTime
    extends MtsOctaveMessageGenerator(isRealTime = true, isIn2ByteForm = true)
}
