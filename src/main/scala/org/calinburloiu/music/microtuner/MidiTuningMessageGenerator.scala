package org.calinburloiu.music.microtuner

import java.nio.ByteBuffer
import javax.sound.midi.{ShortMessage, SysexMessage}

import org.calinburloiu.music.tuning.Tuning

/**
  * @see [[org.calinburloiu.music.microtuner.MidiTuningFormat]]
  */
sealed trait MidiTuningMessageGenerator {
  def generate(tuning: Tuning): SysexMessage
}

object MidiTuningMessageGenerator {
  case object NonRealTime1BOctave extends MidiTuningMessageGenerator {

    val headerBytes: Array[Byte] = Array(SysexMessage.SYSTEM_EXCLUSIVE.toByte, 0x7E, 0x7F, 0x08, 0x08).map(_.toByte)
    val allChannelsBytes: Array[Byte] = Array(0x03, 0x7F, 0x7F).map(_.toByte)

    override def generate(tuning: Tuning): SysexMessage = {
      val tuningValuesBytes: Array[Byte] = tuning.deviations.map { noteTuning =>
        val nNoteTuning = Math.min(Math.max(-64, noteTuning.round.toInt), 63)

        (0x40 + nNoteTuning).toByte
      }.toArray

      val buffer = ByteBuffer.allocate(21)

      buffer.put(headerBytes)
      buffer.put(allChannelsBytes)
      buffer.put(tuningValuesBytes)
      buffer.put(ShortMessage.END_OF_EXCLUSIVE.toByte)

      val data = buffer.array()

      val sysexMessage = new SysexMessage(data, data.length)

      sysexMessage
    }
  }
}
