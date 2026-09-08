/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.MidiMsg

/**
 * MIDI interceptor that can change the MIDI messages that pass through it.
 *
 * Its input is the [[receiver]] and its output the [[transmitter]]. A subclass implements [[process]] to filter,
 * change or generate messages: every message the receiver gets is processed once and each resulting message is
 * forwarded to every receiver of the transmitter, in order. A processor whose transmitter has no receivers is not
 * processing: such a message is dropped without reaching [[process]].
 *
 * The processor is ''connected'' while its transmitter has at least one receiver. Whenever the receiver sequence
 * changes:
 *
 *   1. if the current sequence is non-empty, [[onDisconnect]] is called, with the old receivers still in place;
 *   1. the sequence is replaced;
 *   1. if the new sequence is non-empty, [[onConnect]] is called.
 *
 * Setting the same sequence again does nothing. The hooks run inside the transmitter's write lock, so a message
 * arriving on another thread cannot interleave with the messages the hooks emit. A hook may send downstream through
 * `transmitter.receivers` (the lock is reentrant); it must not wait for another thread.
 */
trait MidiProcessor extends AutoCloseable {

  private val _receiver: MidiProcessorReceiver = MidiProcessorReceiver()

  private val _transmitter: MidiProcessorTransmitter = MidiProcessorTransmitter()

  /**
   * The [[MidiReceiver]] of a [[MidiProcessor]]: processes every incoming message and forwards the results to every
   * receiver of the [[transmitter]]. Once closed, it ignores everything.
   */
  class MidiProcessorReceiver private[scmidi] extends MidiReceiver {

    @volatile private var _isClosed: Boolean = false

    override def send(message: MidiMsg, timeStamp: Long): Unit = if (!_isClosed) {
      val outputReceivers = transmitter.receivers
      if (outputReceivers.nonEmpty) {
        for (outputMessage <- process(message, timeStamp); outputReceiver <- outputReceivers) {
          outputReceiver.send(outputMessage, timeStamp)
        }
      }
    }

    override def close(): Unit = {
      _isClosed = true
    }

    /** @return whether [[close]] was called; a closed receiver drops every message. */
    def isClosed: Boolean = _isClosed
  }

  /**
   * The [[ConcurrentMidiTransmitter]] of a [[MidiProcessor]]: runs the connect / disconnect protocol described on
   * [[MidiProcessor]] around every change of its receivers, whether made through a modifier or by assignment.
   */
  class MidiProcessorTransmitter private[scmidi] extends ConcurrentMidiTransmitter() {

    // Taken explicitly: a direct `receivers = …` assignment reaches this override before the superclass takes the
    // lock, whereas a modifier reaches it with the lock already held. The lock is reentrant, so both paths are fine.
    override def receivers_=(newReceivers: Seq[MidiReceiver]): Unit = withWriteLock {
      val currentReceivers = receivers
      if (currentReceivers != newReceivers) {
        if (currentReceivers.nonEmpty) {
          onDisconnect()
        }
        super.receivers_=(newReceivers)
        if (newReceivers.nonEmpty) {
          onConnect()
        }
      }
    }
  }

  /**
   * @return the receiver that takes the messages to be processed and forwarded to the output.
   */
  def receiver: MidiProcessorReceiver = _receiver

  /**
   * @return the transmitter that forwards the processed messages to its receivers.
   */
  def transmitter: MidiProcessorTransmitter = _transmitter

  /**
   * Processes a MIDI message and returns the resulting sequence of MIDI messages.
   *
   * The input messages can be filtered, modified or used to generate new MIDI output messages.
   *
   * @param message   The MIDI message to process.
   * @param timeStamp The time-stamp of the MIDI message.
   * @return A sequence of processed MIDI messages.
   */
  protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg]

  /**
   * Callback called after the transmitter's receivers changed to a non-empty sequence, to let the processor configure
   * the output it is now connected to.
   */
  protected def onConnect(): Unit = {}

  /**
   * Callback called before the transmitter's receivers change away from a non-empty sequence, to let the processor
   * leave the output it was connected to in a consistent state.
   *
   * The processor can't know what was the exact state of the output device before connecting the processor to it.
   * Leaving it in a consistent state means setting the parameters (CCs, RPNs, NRPNs etc.) that were altered by the
   * processor to some convenient/default values.
   */
  protected def onDisconnect(): Unit = {}
}
