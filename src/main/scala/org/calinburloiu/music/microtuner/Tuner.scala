/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner

import com.typesafe.scalalogging.StrictLogging

import javax.sound.midi.{MidiMessage, Receiver}
import org.calinburloiu.music.microtuner.midi.{MidiProcessor, MidiTuningFormat}
import org.calinburloiu.music.tuning.Tuning

trait Tuner extends MidiProcessor {

  def tune(tuning: Tuning): Unit
}

trait LoggerTuner extends Tuner with StrictLogging {

  import org.calinburloiu.music.tuning.PianoKeyboardTuningUtils._

  abstract override def tune(tuning: Tuning): Unit = {
    logger.info(s"Tuning to ${tuning.toPianoKeyboardString}")

    super.tune(tuning)
  }
}

/**
 * MIDI Tuning Standard (MTS) `Tuner` implementation.
 * @param tuningFormat one of the MTS formats supported
 */
class MtsTuner(val receiver: Receiver,
               val tuningFormat: MidiTuningFormat) extends Tuner {

  private val tuningMessageGenerator = tuningFormat.messageGenerator

  @throws[MidiTunerException]
  override def tune(tuning: Tuning): Unit = {
    val sysexMessage = tuningMessageGenerator.generate(tuning)
    try {
      receiver.send(sysexMessage, -1)
    } catch {
      case e: IllegalStateException => throw new MidiTunerException(e)
    }
  }

  override def processMessage(message: MidiMessage, timeStamp: Long): Seq[MidiMessage] = Seq(message)
}

class MidiTunerException(cause: Throwable) extends RuntimeException(
  "Failed to send tune message to device! Did you disconnect the device?", cause)
