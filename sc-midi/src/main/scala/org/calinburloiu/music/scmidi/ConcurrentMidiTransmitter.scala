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

import org.calinburloiu.music.microtonalist.common.concurrency.Locking

import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.concurrent.ThreadSafe

/**
 * A [[MutableMidiTransmitter]] that may be read and changed from any thread.
 *
 * Every accessor and modifier of the mutable class is overridden to run under a [[ReentrantReadWriteLock]]: reads
 * take the read lock, changes take the write lock for the whole read-modify-write, so concurrent `addReceiver`s never
 * lose an update. Because the mutable base funnels every modifier through `receivers_=`, and each modifier override
 * here already holds the write lock when it calls `super`, a subclass that overrides `receivers_=` is reached
 * **inside** the write lock for every change made through a modifier. A subclass override reached by a direct
 * `receivers = …` assignment runs before this class's own `receivers_=` takes the lock, so such an override must take
 * the write lock itself (the lock is reentrant and `protected` for that purpose).
 *
 * An override that needs the current receivers — to compare them with the incoming ones, say — must take the
 * **write** lock first and read **inside** it. A [[ReentrantReadWriteLock]] cannot upgrade, so the read-then-write
 * shape `withReadLock { … withWriteLock { … } }` deadlocks the calling thread against itself. The opposite nesting is
 * allowed, which makes taking the write lock first always safe, whether the override was reached through a modifier
 * (which already holds it) or by a direct assignment.
 *
 * Extends the mutable class so that a caller which only needs "something it can add a receiver to" has one static
 * type, whatever the threading policy.
 *
 * @param initialReceivers the receivers messages are forwarded to at construction; defaults to none.
 */
@ThreadSafe
class ConcurrentMidiTransmitter(initialReceivers: Seq[MidiReceiver] = Seq.empty)
  extends MutableMidiTransmitter(initialReceivers), Locking {

  /**
   * Guards the receivers. Reentrant, so an override of `receivers_=` may take the write lock again; it must never take
   * the read lock while intending to write, as read-to-write upgrade is not supported.
   */
  protected implicit val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

  override def receivers: Seq[MidiReceiver] = withReadLock {
    super.receivers
  }

  override def receivers_=(newReceivers: Seq[MidiReceiver]): Unit = withWriteLock {
    super.receivers_=(newReceivers)
  }

  override def addReceiver(receiver: MidiReceiver): Unit = withWriteLock {
    super.addReceiver(receiver)
  }

  override def addReceivers(newReceivers: Seq[MidiReceiver]): Unit = withWriteLock {
    super.addReceivers(newReceivers)
  }

  override def removeReceiver(receiver: MidiReceiver): Unit = withWriteLock {
    super.removeReceiver(receiver)
  }

  override def clearReceivers(): Unit = withWriteLock {
    super.clearReceivers()
  }
}
