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
import javax.sound.midi.Receiver

/**
 * A MIDI transmitter that allows sending MIDI messages to multiple [[Receiver]]s.
 *
 * @see [[javax.sound.midi.Transmitter]] which only allows a single [[Receiver]] to be set.
 */
trait MultiTransmitter extends AutoCloseable, Locking {
  private implicit val lock: ReadWriteLock = new ReentrantReadWriteLock()

  private var _receivers: Seq[Receiver] = Seq.empty

  /**
   * Retrieves the current sequence of MIDI [[Receiver]]s to which all messages are forwarded.
   *
   * @return A sequence of [[Receiver]] instances.
   */
  def receivers: Seq[Receiver] = withReadLock {
    _receivers
  }

  /**
   * Replaces the sequence of [[Receiver]] instances to which MIDI messages are forwarded.
   *
   * @param receivers Sequence of new [[Receiver]] instances.
   */
  def receivers_=(receivers: Seq[Receiver]): Unit = withWriteLock {
    _receivers = receivers
  }

  /**
   * Adds a new [[Receiver]] to the list of MIDI `Receiver`s.
   *
   * @param receiver Receiver instance to be added to the list.
   */
  def addReceiver(receiver: Receiver): Unit = withWriteLock {
    _receivers = _receivers :+ receiver
  }

  /**
   * Adds a sequence of new [[Receiver]] instances to the current list of receivers.
   *
   * @param newReceivers Sequence of [[Receiver]] instances to be added.
   */
  def addReceivers(newReceivers: Seq[Receiver]): Unit = withWriteLock {
    _receivers = _receivers :++ newReceivers
  }

  /**
   * Removes a specified [[Receiver]] from the list of MIDI receivers.
   *
   * @param receiver Receiver instance to be removed from the list.
   */
  def removeReceiver(receiver: Receiver): Unit = withWriteLock {
    _receivers = _receivers.filterNot(_ == receiver)
  }

  /**
   * Clears the list of MIDI receivers.
   */
  def clearReceivers(): Unit = withWriteLock {
    _receivers = Seq.empty
  }
}
