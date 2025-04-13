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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.microtonalist.common.concurrency.Locking

import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}
import javax.sound.midi.{MidiMessage, Receiver, Transmitter}

/**
 * MIDI interceptor that can change MIDI events that pass through it.
 *
 * It can only be used after a receiver is set for it via [[MidiProcessor#setReceiver()]] or [[MidiProcessor#receiver]]
 * setters when it is said that it _connects_. When this happens [[MidiProcessor#onConnect()]] callback is called.
 * When the receiver is changed, it first _disconnects_, so [[MidiProcessor#onDisconnect()]] is called before.
 * [[MidiProcessor#onDisconnect()]] is also called with the [[MidiProcessor]] is closed.
 */
trait MidiProcessor extends AutoCloseable {

  private val _receiver: MidiProcessorReceiver = MidiProcessorReceiver()

  private val _transmitter: MidiProcessorTransmitter = MidiProcessorTransmitter()

  class MidiProcessorReceiver private[scmidi] extends Receiver {

    @volatile private var isClosed: Boolean = false

    override def send(message: MidiMessage, timeStamp: Long): Unit = {
      val outputMessages = process(message, timeStamp)

      for (receiverUsed <- transmitter.receiverOption; outputMessage <- outputMessages) {
        receiverUsed.send(outputMessage, timeStamp)
      }
    }

    def send(scMessage: ScMidiMessage, timeStamp: Long = -1L): this.type = {
      send(scMessage.javaMidiMessage, timeStamp)
      this
    }

    override def close(): Unit = if (!isClosed) {
      // Nothing to do for the moment. Add clean-up code here whenever necessary.

      isClosed = true
    }
  }

  class MidiProcessorTransmitter private[scmidi] extends Transmitter, Locking {
    private implicit val lock: ReadWriteLock = ReentrantReadWriteLock()

    private var outputReceiver: Option[Receiver] = None

    @volatile private var isClosed: Boolean = false

    override def setReceiver(receiver: Receiver): Unit = {
      def disconnectBeforeSet(localOutputReceiver: Option[Receiver]): Unit = {
        if (localOutputReceiver.isDefined) {
          onDisconnect()
        }
      }

      val localOutputReceiver = receiverOption
      disconnectBeforeSet(localOutputReceiver)

      withWriteLock {
        disconnectBeforeSet(outputReceiver)
        outputReceiver = Option(receiver)
      }

      onConnect()
    }

    override def getReceiver: Receiver = receiverOption.orNull

    /**
     * Scala idiomatic version of [[Transmitter]]'s `getReceiver` method.
     *
     * As opposed to the Java version, this method throws [[IllegalStateException]] is a [[Receiver]] was not
     * previously set.
     *
     * @return the current receiver. If no receiver is currently set, [[IllegalStateException]] is thrown.
     * @throws IllegalStateException if no receiver is currently set. Use the Java-like getReceiver as a non-throwing
     *                               accessor that may return null
     * @see [[javax.sound.midi.Transmitter#getReceiver()]]
     */
    def receiver: Receiver = receiverOption.getOrElse(throw new IllegalStateException("No receiver was set!"))

    /**
     * Scala idiomatic version of [[Transmitter]]'s `setReceiver` method.
     *
     * @see [[javax.sound.midi.Transmitter#setReceiver()]]
     */
    def receiver_=(receiver: Receiver): Unit = setReceiver(receiver)

    def receiverOption: Option[Receiver] = withReadLock {
      outputReceiver
    }

    override def close(): Unit = if (!isClosed) {
      // Nothing to do for the moment. Add clean-up code here whenever necessary.

      isClosed = true
    }
  }

  def receiver: MidiProcessorReceiver = _receiver

  def transmitter: MidiProcessorTransmitter = _transmitter

  protected def process(message: MidiMessage, timeStamp: Long): Seq[MidiMessage]

  /**
   * Callback called after a receiver is set to allow configuring the output device to be used with the processor.
   *
   * @see [[MidiProcessor#setReceiver()]]
   */
  protected def onConnect(): Unit = {}

  /**
   * Callback called before a new receiver is set to let the receiver being replaced in a consistent state.
   *
   * The processor can't know what was the exact state of the output device before connecting the processor to it.
   * Leaving it in a consistent state means setting the parameters (CCs, RPNs, NRPNs etc.) that were altered by the
   * processor to some convenient/default values.
   *
   * @see [[MidiProcessor#setReceiver()]]
   */
  protected def onDisconnect(): Unit = {}
}
