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

import javax.sound.midi.{MidiMessage, Receiver}
import scala.collection.mutable

/**
 * `MidiSplitter` is a MIDI [[Receiver]] that forwards all MIDI messages to a configurable set of [[Receiver]]s.
 *
 * @param initialReceivers Sequence of initial [[Receiver]] instances to which MIDI messages will be sent. The
 *                         sequence may be mutated if necessary after the class instantiation.
 */
class MidiSplitter(initialReceivers: Seq[Receiver] = Seq.empty) extends Receiver {
  private val _receivers: mutable.ArrayBuffer[Receiver] = mutable.ArrayBuffer(initialReceivers *)

  override def send(message: MidiMessage, timeStamp: Long): Unit = receivers.foreach(_.send(message, timeStamp))

  /**
   * Retrieves the current sequence of MIDI [[Receiver]]s to which all messages are forwarded.
   *
   * @return A sequence of [[Receiver]] instances.
   */
  def receivers: Seq[Receiver] = _receivers.toSeq

  /**
   * Replaces the sequence of [[Receiver]] instances to which MIDI messages are forwarded.
   *
   * @param receivers Sequence of new [[Receiver]] instances.
   */
  def receivers_=(receivers: Seq[Receiver]): Unit = {
    _receivers.clear()
    _receivers ++= receivers
  }

  /**
   * Adds a new [[Receiver]] to the list of MIDI `Receiver`s.
   *
   * @param receiver Receiver instance to be added to the list.
   */
  def addReceiver(receiver: Receiver): Unit = {
    _receivers += receiver
  }

  /**
   * Adds a sequence of new [[Receiver]] instances to the current list of receivers.
   *
   * @param newReceivers Sequence of [[Receiver]] instances to be added.
   */
  def addReceivers(newReceivers: Seq[Receiver]): Unit = {
    _receivers ++= newReceivers
  }

  /**
   * Removes a specified [[Receiver]] from the list of MIDI receivers.
   *
   * @param receiver Receiver instance to be removed from the list.
   */
  def removeReceiver(receiver: Receiver): Unit = {
    _receivers -= receiver
  }

  /**
   * Clears the list of MIDI receivers.
   */
  def clearReceivers(): Unit = {
    _receivers.clear()
  }

  override def close(): Unit = {
    // Nothing to do here
  }
}
