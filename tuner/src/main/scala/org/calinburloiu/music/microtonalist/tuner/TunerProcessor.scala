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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.composition.OctaveTuning
import org.calinburloiu.music.scmidi.MidiProcessor

import javax.annotation.concurrent.NotThreadSafe
import javax.sound.midi.MidiMessage

@NotThreadSafe
class TunerProcessor(tuner: Tuner) extends MidiProcessor with StrictLogging {

  def tune(tuning: OctaveTuning): Unit = {
    val tuningMessages = tuner.tune(tuning)
    sendToReceiver(tuningMessages, -1)
  }

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    val messages = tuner.process(message)
    sendToReceiver(messages, timeStamp)
  }

  override protected def onConnect(): Unit = {
    super.onConnect()

    val initMessages = tuner.reset()
    sendToReceiver(initMessages, -1)

    logger.info(s"Connected the processor for tuner $tuner.")
  }

  override protected def onDisconnect(): Unit = {
    super.onDisconnect()

    tune(OctaveTuning.Edo12)

    logger.info(s"Disconnected the processor for tuner $tuner.")
  }

  override def close(): Unit = {
    logger.info(s"Closing the processor for tuner $tuner...")

    super.close()
  }

  private def sendToReceiver(messages: Seq[MidiMessage], timeStamp: Long): Unit = {
    // TODO #64 Handle the try differently
    try {
      for (message <- messages) {
        receiver.send(message, timeStamp)
      }
    } catch {
      case e: IllegalStateException => throw new TunerException(e)
    }
  }
}

class TunerException(cause: Throwable) extends RuntimeException(
  "Failed to send message to device! Did you disconnect the device?", cause)
