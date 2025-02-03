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

import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.music.scmidi.ScCcMidiMessage

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.collection.mutable

class PedalTuningChanger(val previousTuningCc: Int = ScCcMidiMessage.SoftPedal,
                         val nextTuningCc: Int = ScCcMidiMessage.SostenutoPedal,
                         val threshold: Int = 0) extends TuningChanger with LazyLogging {
  override val typeName: String = "pedal"

  private val ccDepressed: mutable.Map[Int, Boolean] = mutable.Map(
    previousTuningCc -> false, nextTuningCc -> false)

  override def decide(message: MidiMessage): TuningChange = message match {
    case shortMessage: ShortMessage =>
      val command = shortMessage.getCommand
      val cc = shortMessage.getData1
      val ccValue = shortMessage.getData2

      // Capture Control Change messages used for switching and forward any other message
      if (command == ShortMessage.CONTROL_CHANGE && ccDepressed.contains(cc)) {
        if (!ccDepressed(cc) && ccValue > threshold) {
          start(cc)
        } else if (ccDepressed(cc) && ccValue <= threshold) {
          stop(cc)
        } else {
          logger.warn(s"Illegal state: ccDepressed($cc)=${ccDepressed(cc)}, ccValue=$ccValue, threshold=$threshold")
          NoTuningChange
        }
      } else {
        // No trigger detected; ignoring.
        NoTuningChange
      }
    case _ =>
      // No trigger detected; ignoring.
      NoTuningChange
  }

  private def start(cc: Int): TuningChange = {
    ccDepressed(cc) = true

    if (cc == previousTuningCc) {
      PreviousTuningChange
    } else if (cc == nextTuningCc) {
      NextTuningChange
    } else {
      NoTuningChange
    }
  }

  private def stop(cc: Int): TuningChange = {
    ccDepressed(cc) = false

    NoTuningChange
  }
}

object PedalTuningChanger {
  def apply(previousTuningCc: Int = ScCcMidiMessage.SoftPedal,
            nextTuningCc: Int = ScCcMidiMessage.SostenutoPedal,
            threshold: Int = 0): PedalTuningChanger =
    new PedalTuningChanger(previousTuningCc, nextTuningCc, threshold)
}