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
import org.calinburloiu.music.microtonalist.core.OctaveTuning

import javax.sound.midi.MidiMessage

/**
 * MIDI Tuning Standard (MTS) `Tuner` implementation.
 *
 * @param tuningFormat One of the MTS formats supported.
 * @param thru         Whether to redirect input messages to the output. Note that this can be false because MTS
 *                     sends SysEx
 *                     MIDI messages that change the tuning for a batch of notes.
 */
class MtsTuner(val tuningFormat: MtsTuningFormat,
               val thru: Boolean) extends Tuner with StrictLogging {

  private val tuningMessageGenerator = tuningFormat.messageGenerator

  @throws[TunerException]
  override def tune(tuning: OctaveTuning): Unit = {
    val sysexMessage = tuningMessageGenerator.generate(tuning)
    // TODO Handle the try differently
    try {
      receiver.send(sysexMessage, -1)
    } catch {
      case e: IllegalStateException => throw new TunerException(e)
    }
  }

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    if (thru) {
      try {
        receiver.send(message, timeStamp)
      } catch {
        case e: IllegalStateException => throw new TunerException(e)
      }
    }
  }

  override def close(): Unit = {
    super.close()
    logger.info(s"Closing ${this.getClass.getCanonicalName}...")
  }

  override protected def onConnect(): Unit = {
    logger.info(s"Connected the MTS tuner.")
  }

  override protected def onDisconnect(): Unit = {
    tune(OctaveTuning.Edo12)
    logger.info("Disconnected the MTS tuner.")
  }
}
