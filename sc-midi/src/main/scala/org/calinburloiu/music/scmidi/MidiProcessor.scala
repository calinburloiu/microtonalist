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
 * Whenever the receiver sequence changes, [[onDisconnect]] and [[onConnect]] fire for exactly the receivers the
 * change affects, not for the whole sequence, and [[onReceiversChanged]] fires last for the sequence as a whole:
 *
 *   1. the receivers being dropped — present in the current sequence but absent from the incoming one — are passed
 *      to [[onDisconnect]], with the old sequence still in place;
 *   1. the sequence is replaced;
 *   1. the receivers being added — absent from the old sequence but present in the incoming one — are passed to
 *      [[onConnect]];
 *   1. the new sequence is passed to [[onReceiversChanged]].
 *
 * A receiver present on both sides of the change (e.g. adding one more receiver to an already-connected processor)
 * triggers neither of the first two hooks: it was already initialized and stays that way. Neither is ever called with
 * an empty sequence, whereas [[onReceiversChanged]] is called on every change — a reordering included, which the
 * other two cannot report. Setting the same sequence again calls none of the three. The hooks run inside the
 * transmitter's write lock, so the
 * receiver sequence cannot change under them and a send that has not yet read the receivers is held off; a fan-out
 * already in flight is not, since [[MidiProcessorReceiver.send]] holds the read lock only long enough to snapshot
 * the receivers. A hook may send downstream through `transmitter.receivers` (the lock is reentrant); it must not wait
 * for another thread.
 */
trait MidiProcessor {

  private val _receiver: MidiProcessorReceiver = MidiProcessorReceiver()

  private val _transmitter: MidiProcessorTransmitter = MidiProcessorTransmitter()

  /**
   * The [[MidiReceiver]] of a [[MidiProcessor]]: processes every incoming message and forwards the results to every
   * receiver of the [[transmitter]].
   */
  class MidiProcessorReceiver private[scmidi] extends MidiReceiver {

    override def send(message: MidiMsg, timeStamp: Long): Unit = {
      val outputReceivers = transmitter.receivers
      if (outputReceivers.nonEmpty) {
        for (outputMessage <- process(message, timeStamp); outputReceiver <- outputReceivers) {
          outputReceiver.send(outputMessage, timeStamp)
        }
      }
    }
  }

  /**
   * The [[ConcurrentMidiTransmitter]] of a [[MidiProcessor]]: runs the connect / disconnect protocol described on
   * [[MidiProcessor]] around every change of its receivers, whether made through a modifier or by assignment.
   */
  class MidiProcessorTransmitter private[scmidi] extends ConcurrentMidiTransmitter() {

    override protected def setReceivers(newReceivers: Seq[MidiReceiver]): Unit = {
      val currentReceivers = receivers
      if (currentReceivers != newReceivers) {
        val removedReceivers = currentReceivers.filterNot(newReceivers.contains)
        val addedReceivers = newReceivers.filterNot(currentReceivers.contains)
        if (removedReceivers.nonEmpty) {
          onDisconnect(removedReceivers)
        }
        super.setReceivers(newReceivers)
        if (addedReceivers.nonEmpty) {
          onConnect(addedReceivers)
        }
        onReceiversChanged(newReceivers)
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
   * Callback called after receivers are added to the transmitter, to let the processor configure the output it is
   * now connected to.
   *
   * @param receivers the receivers newly added; never empty, and disjoint from the receivers already connected
   *                  before the change, which this callback is not invoked for.
   */
  protected def onConnect(receivers: Seq[MidiReceiver]): Unit = {}

  /**
   * Callback called before receivers are removed from the transmitter, to let the processor leave the output it was
   * connected to in a consistent state.
   *
   * The processor can't know what was the exact state of the output device before connecting the processor to it.
   * Leaving it in a consistent state means setting the parameters (CCs, RPNs, NRPNs etc.) that were altered by the
   * processor to some convenient/default values.
   *
   * @param receivers the receivers being removed; never empty, and disjoint from the receivers that remain connected
   *                  after the change, which this callback is not invoked for.
   */
  protected def onDisconnect(receivers: Seq[MidiReceiver]): Unit = {}

  /**
   * Callback called last on every change of the transmitter's receivers, once the new sequence is in place, whether
   * or not [[onConnect]] and [[onDisconnect]] were called for it.
   *
   * This is the callback to override to keep something else in step with the whole sequence, as
   * [[MidiSerialProcessor]] does for the last processor of its chain; a change that only reorders the receivers, or
   * that repeats one already connected, is reported here and nowhere else. To initialize or clean up an individual
   * receiver, override [[onConnect]] / [[onDisconnect]] instead: they say which receivers the change affects, and
   * this one does not.
   *
   * @param receivers the receivers messages are forwarded to from now on, in order; may be empty.
   */
  protected def onReceiversChanged(receivers: Seq[MidiReceiver]): Unit = {}
}
