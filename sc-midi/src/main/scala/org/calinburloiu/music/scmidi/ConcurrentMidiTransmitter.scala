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
 * It supplies the two hooks of the mutable class with a [[ReentrantReadWriteLock]]: reads take the read lock, and the
 * change guard takes the write lock for the whole read-modify-write, so concurrent `addReceiver`s never lose an
 * update. The modifiers themselves are inherited unchanged — the mutable class already routes every one of them, and
 * a direct `receivers = …` assignment, through the guard.
 *
 * A subclass therefore overrides `setReceivers` without knowing that a lock exists: the write lock is always held by
 * the time the hook runs, whatever the entry point, and the hook may read [[receivers]] re-entrantly to compare the
 * incoming sequence with the current one. Holding the write lock while taking the read lock is a downgrade, which is
 * permitted; it is the reverse order that a `ReentrantReadWriteLock` cannot do, and no path here produces it.
 *
 * Extends the mutable class so that a caller which only needs "something it can add a receiver to" has one static
 * type, whatever the threading policy.
 *
 * @param initialReceivers the receivers messages are forwarded to at construction; defaults to none.
 */
@ThreadSafe
class ConcurrentMidiTransmitter(initialReceivers: Seq[MidiReceiver] = Seq.empty)
  extends MutableMidiTransmitter(initialReceivers), Locking {

  /** Guards the receivers. Reentrant, so a `setReceivers` override may read [[receivers]] while the guard holds it. */
  protected implicit val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

  override def receivers: Seq[MidiReceiver] = withReadLock {
    super.receivers
  }

  override protected def withChangeGuard[R](body: => R): R = withWriteLock {
    body
  }
}
