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

    @volatile private var _isClosed: Boolean = false

    override def send(message: MidiMessage, timeStamp: Long): Unit = if (!_isClosed) {
      val outputMessages = process(message, timeStamp)

      for (destReceiver <- transmitter.receiver; outputMessage <- outputMessages) {
        destReceiver.send(outputMessage, timeStamp)
      }
    }

    def send(scMessage: ScMidiMessage, timeStamp: Long = -1L): this.type = {
      if (!_isClosed) {
        send(scMessage.javaMidiMessage, timeStamp)
      }

      this
    }

    override def close(): Unit = if (!_isClosed) {
      // Nothing to do for the moment. Add clean-up code here whenever necessary.

      _isClosed = true
    }

    def isClosed: Boolean = _isClosed
  }

  class MidiProcessorTransmitter private[scmidi] extends Transmitter, Locking {
    private implicit val lock: ReadWriteLock = ReentrantReadWriteLock()

    private var outputReceiver: Option[Receiver] = None

    @volatile private var _isClosed: Boolean = false

    override def setReceiver(value: Receiver): Unit = {
      def disconnectBeforeSet(localOutputReceiver: Option[Receiver]): Boolean = {
        if (localOutputReceiver.isDefined) {
          onDisconnect()
          true
        } else {
          false
        }
      }

      val localOutputReceiver = receiver

      if (!receiver.contains(value)) {
        val wasOnDisconnectCalled = disconnectBeforeSet(localOutputReceiver)

        withWriteLock {
          if (!wasOnDisconnectCalled) {
            disconnectBeforeSet(outputReceiver)
          }

          outputReceiver = Option(value)
        }

        onConnect()
      }
    }

    override def getReceiver: Receiver = receiver.orNull

    /**
     * Scala idiomatic version of [[Transmitter]]'s `getReceiver` method.
     *
     * @return an [[Option]] of the current receiver.
     * @see [[javax.sound.midi.Transmitter#getReceiver()]]
     */
    def receiver: Option[Receiver] = withReadLock {
      outputReceiver
    }

    /**
     * Scala idiomatic version of [[Transmitter]]'s `setReceiver` method.
     *
     * @see [[javax.sound.midi.Transmitter#setReceiver()]]
     */
    def receiver_=(value: Option[Receiver]): Unit = setReceiver(value.orNull)

    override def close(): Unit = if (!_isClosed) {
      // Nothing to do for the moment. Add clean-up code here whenever necessary.

      _isClosed = true
    }

    def isClosed: Boolean = _isClosed
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
