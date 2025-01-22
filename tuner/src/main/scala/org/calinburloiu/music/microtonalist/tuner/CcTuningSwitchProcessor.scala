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

import com.typesafe.scalalogging.StrictLogging

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.collection.mutable

case class CcTriggers(enabled: Boolean = false,
                      prevTuningCc: Int = 67,
                      nextTuningCc: Int = 66,
                      ccThreshold: Int = 0,
                      isFilteringThru: Boolean = true)

object CcTriggers {
  val default: CcTriggers = CcTriggers()
}

class CcTuningSwitchProcessor(tuningSwitcher: TuningSwitcher,
                              ccTriggers: CcTriggers) extends TuningSwitchProcessor with StrictLogging {

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
          if (cc == ccPrev) {
            tuningSwitcher.prev()
          } else if (cc == ccNext) {
            tuningSwitcher.next()
          }
        } else if (ccDepressed(cc) && ccValue <= ccTriggerThreshold) {
          ccDepressed(cc) = false
        }

        // Forward if configured so
        if (!isFilteringCcTriggersThru) {
          receiver.send(message, timeStamp)
        }
      } else {
        // Forward
        receiver.send(message, timeStamp)
      }
  }

  override def close(): Unit = {
    super.close()
    logger.info(s"Closing ${this.getClass.getCanonicalName}...")
  }

  override protected def onConnect(): Unit = {
    logger.info(s"Connected the CC tuning switch MIDI processor.")
  }

  override protected def onDisconnect(): Unit = {
    logger.info(s"Disconnected the CC tuning switch MIDI processor.")
  }
}
