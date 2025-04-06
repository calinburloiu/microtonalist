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

import javax.sound.midi.{MidiMessage, Receiver, Transmitter}

// TODO #134 Make a MidiProcessor be composed from a Receiver and a Transmitter rather than extend them to reduce
//  confusion

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

  class MidiProcessorReceiver extends Receiver {

    override def send(message: MidiMessage, timeStamp: Long): Unit = {
      val outputMessages = process(message)

      for (receiverUsed <- outputReceiver; outputMessage <- outputMessages) {
        receiverUsed.send(outputMessage, timeStamp)
      }
    }

    def send(scMessage: ScMidiMessage, timeStamp: Long = -1L): this.type = {
      send(scMessage.javaMidiMessage, -1)
      this
    }

    override def close(): Unit = {
      _transmitter.close()
      onDisconnect()
    }
  }

  class MidiProcessorTransmitter extends Transmitter {
    private var outputReceiver: Option[Receiver] = None

    override def setReceiver(receiver: Receiver): Unit = {
      if (outputReceiver.isDefined) onDisconnect()
      outputReceiver = Option(receiver)
      onConnect()
    }

    override def getReceiver: Receiver = outputReceiver.orNull

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
    def receiver: Receiver = {
      val result = getReceiver
      if (result == null) {
        throw new IllegalStateException("No receiver was set!")
      }

      result
    }

    /**
     * Scala idiomatic version of [[Transmitter]]'s `setReceiver` method.
     *
     * @see [[javax.sound.midi.Transmitter#setReceiver()]]
     */
    def receiver_=(receiver: Receiver): Unit = setReceiver(receiver)

    def receiverOption: Option[Receiver] = outputReceiver

    override def close(): Unit = {
      _receiver.close()
      onDisconnect()
    }
  }

  def receiver: MidiProcessorReceiver = _receiver

  def transmitter: MidiProcessorTransmitter = _transmitter

  protected def process(message: MidiMessage): Seq[MidiMessage]

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

  private def outputReceiver: Option[Receiver] = _transmitter.receiverOption
}
