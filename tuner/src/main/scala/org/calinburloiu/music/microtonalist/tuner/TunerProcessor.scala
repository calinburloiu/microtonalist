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
import org.calinburloiu.music.scmidi.MidiProcessor

import javax.annotation.concurrent.NotThreadSafe
import javax.sound.midi.MidiMessage

/**
 * A MIDI processor that integrates with a Tuner to manage MIDI messages and tuning operations.
 *
 * This class extends [[MidiProcessor]] and facilitates the application of tunings to the output and to MIDI messages
 * sent through it. It encapsulates the logic for interacting with the provided [[Tuner]] instance
 * to perform tuning and processing operations, while ensuring proper connection and disconnection
 * handling through the lifecycle events of the processor.
 *
 * The primary responsibilities of this class include:
 * - Forwarding MIDI messages to the [[Tuner]] for processing and sending the resultant messages to the receiver.
 * - Applying the tuning when requested and sending the corresponding MIDI tuning messages, if any.
 * - Properly resetting the tuner and sending initialization messages when connected.
 * - Restoring the default tuning and ensuring a clean state upon disconnection.
 * - Safeguarding message transmission to the MIDI receiver and handling any transmission errors.
 *
 * This processor assumes non-thread-safe behavior and must be used on a [[Track]] thread which ensures
 * external synchronization.
 *
 * @param tuner The [[Tuner]] instance used to handle tuning operations and modify MIDI messages.
 */
@NotThreadSafe
class TunerProcessor(tuner: Tuner) extends MidiProcessor with StrictLogging {

  /**
   * Tunes the output instrument using the specified tuning.
   * The method generates the corresponding MIDI messages, if any, for the given tuning
   * and sends them to the configured receiver.
   *
   * @param tuning The instance that contains the tuning information,
   *               including the deviation in cents for each of the 12 pitch classes.
   */
  def tune(tuning: Tuning): Unit = {
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

    // Reset the output instrument to the standard tuning
    tune(Tuning.Standard)

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
