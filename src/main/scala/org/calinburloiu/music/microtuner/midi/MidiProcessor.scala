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

package org.calinburloiu.music.microtuner.midi

import javax.sound.midi.{MidiMessage, Receiver, Transmitter}

trait MidiProcessor extends Transmitter with Receiver {
  private var _receiver: Option[Receiver] = None

  override def setReceiver(receiver: Receiver): Unit = {
    _receiver = Some(receiver)
  }

  override def getReceiver: Receiver = _receiver.getOrElse(throw new IllegalStateException("No receiver was set!"))

  /**
   * Scala idiomatic version of [[Transmitter]]'s `getReceiver` method.
   * @see [[javax.sound.midi.Transmitter#getReceiver()]]
   */
  def receiver: Receiver = getReceiver
  /**
   * Scala idiomatic version of [[Transmitter]]'s `setReceiver` method.
   * @see [[javax.sound.midi.Transmitter#setReceiver()]]
   */
  def receiver_=(receiver: Receiver): Unit = setReceiver(receiver)

  /**
   * @see [[javax.sound.midi.Receiver#send()]]
   */
  def send(message: MidiMessage, timeStamp: Long = -1L): Unit = send(message, timeStamp)

  def send(scMessage: ScMidiMessage, timeStamp: Long = -1L): this.type = {
    send(scMessage.javaMidiMessage, timeStamp)
    this
  }
}
