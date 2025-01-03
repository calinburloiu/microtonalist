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

package org.calinburloiu.music.scmidi

import javax.sound.midi.{Receiver, Transmitter}

/**
 * MIDI interceptor that can changes MIDI events that pass through it.
 *
 * It can only be used after a receiver is set for it via [[MidiProcessor#setReceiver()]] or [[MidiProcessor#receiver]]
 * setters when it is said that it _connects_. When this happens [[MidiProcessor#onConnect()]] callback is called.
 * When the receiver is changed, it first _disconnects_, so [[MidiProcessor#onDisconnect()]] is called before.
 */
trait MidiProcessor extends Transmitter with Receiver {
  private var _receiver: Option[Receiver] = None

  override def setReceiver(receiver: Receiver): Unit = {
    if (_receiver.isDefined) onDisconnect()
    _receiver = Some(receiver)
    onConnect()
  }

  override def getReceiver: Receiver = _receiver.getOrElse(throw new IllegalStateException("No receiver was set!"))

  /**
   * Scala idiomatic version of [[Transmitter]]'s `getReceiver` method.
   *
   * @see [[javax.sound.midi.Transmitter#getReceiver()]]
   */
  final def receiver: Receiver = getReceiver

  /**
   * Scala idiomatic version of [[Transmitter]]'s `setReceiver` method.
   *
   * @see [[javax.sound.midi.Transmitter#setReceiver()]]
   */
  final def receiver_=(receiver: Receiver): Unit = setReceiver(receiver)

  final def send(scMessage: ScMidiMessage, timeStamp: Long = -1L): this.type = {
    send(scMessage.javaMidiMessage, timeStamp)
    this
  }

  override def close(): Unit = onDisconnect()

  /**
   * Callback called after a receiver is set to allow configuring the output device to be used with the processor.
   *
   * @see [[MidiProcessor#setReceiver()]]
   */
  protected def onConnect(): Unit

  /**
   * Callback called before a new receiver is set to let the receiver being replaced in a consistent state.
   *
   * The processor can't know what was the exact state of the output device before connecting the processor to it.
   * Leaving it in a consistent state means setting the parameters (CCs, RPNs, NRPNs etc.) that were altered by the
   * processor to some convenient/default values.
   *
   * @see [[MidiProcessor#setReceiver()]]
   */
  protected def onDisconnect(): Unit
}
