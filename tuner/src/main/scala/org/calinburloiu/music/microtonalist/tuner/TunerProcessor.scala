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
import org.calinburloiu.music.scmidi.{MidiProcessor, MidiReceiver}
import org.calinburloiu.music.scmidi.message.MidiMsg

import javax.annotation.concurrent.NotThreadSafe

/**
 * A MIDI processor that integrates with a Tuner to manage MIDI messages and tuning operations.
 *
 * This class extends [[MidiProcessor]] and facilitates the application of tunings to the output and to MIDI messages
 * sent through it. It encapsulates the logic for interacting with the provided [[Tuner]] instance
 * to perform tuning and processing operations, while ensuring proper connection and disconnection
 * handling through the lifecycle events of the processor.
 *
 * The primary responsibilities of this class include:
 * - Forwarding MIDI messages to the [[Tuner]] for processing and sending the resultant messages to the receivers.
 * - Applying the tuning when requested and sending the corresponding MIDI tuning messages, if any.
 * - Properly resetting the tuner and sending initialization messages when connected.
 * - Restoring the default tuning and ensuring a clean state upon disconnection.
 * - Safeguarding message transmission to the MIDI receivers and handling any transmission errors.
 *
 * This processor assumes non-thread-safe behavior and must be used on a [[Track]] thread which ensures
 * external synchronization.
 *
 * @param tuner The [[Tuner]] plugin used to handle tuning operations and modify MIDI messages.
 */
@NotThreadSafe
class TunerProcessor(tuner: Tuner) extends MidiProcessor with StrictLogging {

  /**
   * Tunes the output instrument using the specified tuning.
   * The method generates the corresponding MIDI messages, if any, for the given tuning
   * and sends them to every receiver of the transmitter.
   *
   * @param tuning The instance that contains the tuning information,
   *               including the offset in cents for each of the 12 pitch classes.
   */
  def tune(tuning: Tuning): Unit = {
    val tuningMessages = tuner.tune(tuning)
    sendToReceivers(tuningMessages, -1)
  }

  override def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = tuner.process(message)

  override protected def onConnect(receivers: Seq[MidiReceiver]): Unit = {
    super.onConnect(receivers)

    // TODO #121 tuner.reset() mutates state shared by every receiver of this processor, not just the ones newly
    //  connected here. Harmless today because a multi-receiver TunerProcessor is only ever torn down and rebuilt as
    //  a whole (TrackManager.replaceAllTracks); revisit once a track can be rewired incrementally while running.
    val initMessages = tuner.reset()
    sendTo(receivers, initMessages, -1)

    logger.info(s"Connected the processor for tuner $tuner to ${receivers.size} new receiver(s).")
  }

  override protected def onDisconnect(receivers: Seq[MidiReceiver]): Unit = {
    super.onDisconnect(receivers)

    // TODO #121 tuner.tune(Tuning.Standard) mutates state shared by every receiver of this processor, so it would
    //  also flip the tuning applied to the receivers that remain connected if this processor ever has more than one
    //  receiver left after a partial disconnect. See the onConnect TODO above for the same caveat on the other side.
    val standardTuningMessages = tuner.tune(Tuning.Standard)
    sendTo(receivers, standardTuningMessages, -1)

    logger.info(s"Disconnected the processor for tuner $tuner from ${receivers.size} receiver(s).")
  }

  private def sendToReceivers(messages: Seq[MidiMsg], timeStamp: Long): Unit = sendTo(transmitter.receivers,
    messages, timeStamp)

  private def sendTo(receivers: Seq[MidiReceiver], messages: Seq[MidiMsg], timeStamp: Long): Unit = {
    // TODO #97 Handle the try differently
    try {
      for (message <- messages; outputReceiver <- receivers) {
        outputReceiver.send(message, timeStamp)
      }
    } catch {
      case e: IllegalStateException => throw TunerException(e)
    }
  }
}

class TunerException(cause: Throwable) extends RuntimeException(
  "Failed to send message to device! Did you disconnect the device?", cause)
