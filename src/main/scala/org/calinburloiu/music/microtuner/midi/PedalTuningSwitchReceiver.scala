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

package org.calinburloiu.music.microtuner.midi

import com.typesafe.scalalogging.StrictLogging
import javax.sound.midi.{MidiMessage, Receiver, ShortMessage}
import org.calinburloiu.music.microtuner.TuningSwitch

import scala.collection.mutable

class PedalTuningSwitchReceiver(tuningSwitch: TuningSwitch,
                                outputReceiver: Option[Receiver],
                                ccTriggers: CcTriggers) extends Receiver with StrictLogging {

  private val ccPrev = ccTriggers.prevTuningCc
  private val ccNext = ccTriggers.nextTuningCc
  private val ccTriggerThreshold = ccTriggers.ccThreshold
  private val isFilteringCcTriggersThru: Boolean = ccTriggers.isFilteringThru
  private val ccDepressed: mutable.Map[Int, Boolean] = mutable.Map(ccPrev -> false, ccNext -> false)

  override def send(message: MidiMessage, timeStamp: Long): Unit = message match {
    case shortMessage: ShortMessage =>
      val command = shortMessage.getCommand
      val cc = shortMessage.getData1
      val ccValue = shortMessage.getData2

      // Capture Control Change messages used for switching and forward any other message
      if (command == ShortMessage.CONTROL_CHANGE && ccDepressed.contains(cc)) {
        if (!ccDepressed(cc) && ccValue > ccTriggerThreshold) {
          ccDepressed(cc) = true
          if (cc == ccPrev)
            tuningSwitch.prev()
          else
            tuningSwitch.next()
        } else if (ccDepressed(cc) && ccValue <= ccTriggerThreshold) {
          ccDepressed(cc) = false
        }

        // Forward if configured so
        if (!isFilteringCcTriggersThru) {
          outputReceiver.foreach(_.send(message, timeStamp))
        }
      } else {
        // Forward
        outputReceiver.foreach(_.send(message, timeStamp))
      }
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getCanonicalName}...")
}
