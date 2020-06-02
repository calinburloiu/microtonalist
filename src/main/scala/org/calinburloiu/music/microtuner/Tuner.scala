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
import javax.sound.midi.Receiver
import org.calinburloiu.music.microtuner.midi.MidiTuningFormat
import org.calinburloiu.music.tuning.Tuning

trait Tuner {

  def tune(tuning: Tuning, baseNote: Int = 0): Unit
}

trait LoggerTuner extends Tuner with StrictLogging {

  import org.calinburloiu.music.tuning.PianoKeyboardTuningUtils._

  abstract override def tune(tuning: Tuning, baseNote: Int = 0): Unit = {
    logger.info(s"Tuning to ${tuning.toPianoKeyboardString}")

    super.tune(tuning)
  }
}

class MidiTuner(val receiver: Receiver,
                val tuningFormat: MidiTuningFormat) extends Tuner {

  private val tuningMessageGenerator = tuningFormat.messageGenerator

  override def tune(tuning: Tuning, baseNote: Int = 0): Unit = {
    val sysexMessage = tuningMessageGenerator.generate(tuning)
    receiver.send(sysexMessage, -1)
  }

  // TODO Rethink which code component has the transpose responsibility
  def transpose(tuningValues: Array[Double], baseNote: Int): Array[Double] = {
    Stream.range(0, 12)
      .map { index =>
        val transposedIndex = (index + baseNote) % 12
        tuningValues(transposedIndex)
      }
      .toArray
  }
}
