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

import javax.sound.midi.Receiver

/**
 * A MIDI transmitter that allows sending MIDI messages to multiple [[Receiver]]s.
 *
 * @see [[javax.sound.midi.Transmitter]] which only allows a single [[Receiver]] to be set.
 */
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.sound.midi.Receiver

trait MultiTransmitter extends AutoCloseable {
  private var _receivers: Seq[Receiver] = Seq.empty

  private val lock = new ReentrantReadWriteLock()

  /**
   * Retrieves the current sequence of MIDI [[Receiver]]s to which all messages are forwarded.
   *
   * @return A sequence of [[Receiver]] instances.
   */
  def receivers: Seq[Receiver] = {
    lock.readLock().lock()
    try {
      _receivers
    } finally {
      lock.readLock().unlock()
    }
  }

  /**
   * Replaces the sequence of [[Receiver]] instances to which MIDI messages are forwarded.
   *
   * @param receivers Sequence of new [[Receiver]] instances.
   */
  def receivers_=(receivers: Seq[Receiver]): Unit = writeWithLock {
    _receivers = receivers
  }

  /**
   * Adds a new [[Receiver]] to the list of MIDI `Receiver`s.
   *
   * @param receiver Receiver instance to be added to the list.
   */
  def addReceiver(receiver: Receiver): Unit = writeWithLock {
    _receivers = _receivers :+ receiver
  }

  /**
   * Adds a sequence of new [[Receiver]] instances to the current list of receivers.
   *
   * @param newReceivers Sequence of [[Receiver]] instances to be added.
   */
  def addReceivers(newReceivers: Seq[Receiver]): Unit = writeWithLock {
    _receivers = _receivers :++ newReceivers
  }

  /**
   * Removes a specified [[Receiver]] from the list of MIDI receivers.
   *
   * @param receiver Receiver instance to be removed from the list.
   */
  def removeReceiver(receiver: Receiver): Unit = writeWithLock {
    _receivers = _receivers.filterNot(_ == receiver)
  }

  /**
   * Clears the list of MIDI receivers.
   */
  def clearReceivers(): Unit = writeWithLock {
    _receivers = Seq.empty
  }

  @inline
  private def writeWithLock(action: => Unit): Unit = {
    lock.writeLock().lock()
    try {
      action
    } finally {
      lock.writeLock().unlock()
    }
  }
}
