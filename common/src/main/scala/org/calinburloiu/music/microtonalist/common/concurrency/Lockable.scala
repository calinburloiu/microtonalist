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

package org.calinburloiu.music.microtonalist.common.concurrency

import java.util.concurrent.locks.{Lock, ReadWriteLock}

/**
 * A trait that provides utility methods to execute blocks of code within various types of locks.
 *
 * A lock instance may be provided as an implicit value where the methods of this trait are used.
 *
 * Example usage:
 *
 * {{{
 * class Test extends Lockable {
 *   private var _x: Int
 *
 *   private implicit val lock: ReadWriteLock = new ReentrantReadWriteLock()
 *
 *   def x: Int = withReadLock {
 *     _x
 *   }
 *
 *   def x_=(value: Int): Unit = withWriteLock {
 *     _x = value
 *   }
 * }
 * }}}
 */
trait Lockable {

  /**
   * Executes the given block of code while holding the provided lock, ensuring proper lock acquisition and release,
   * before and after the execution of the block of code, respectively.
   *
   * @param block The block of code to be executed while the lock is held.
   * @param lock  The lock to be acquired and released during the execution of the block.
   * @return The result of executing the block.
   */
  @inline
  def withLock[R](block: => R)(implicit lock: Lock): R = {
    lock.lock()
    try {
      block
    } finally {
      lock.unlock()
    }
  }

  /**
   * Executes the given block of code while holding the read lock of the provided [[ReadWriteLock]],
   * ensuring proper lock acquisition and release around the block's execution.
   *
   * @param block         The block of code to be executed while the read lock is held.
   * @param readWriteLock The [[ReadWriteLock]] instance whose read lock will be used during execution.
   * @return The result of executing the block.
   */
  @inline
  def withReadLock[R](block: => R)(implicit readWriteLock: ReadWriteLock): R = {
    readWriteLock.readLock().lock()
    try {
      block
    } finally {
      readWriteLock.readLock().unlock()
    }
  }

  /**
   * Executes the given block of code while holding the write lock of the provided [[ReadWriteLock]],
   * ensuring proper lock acquisition and release around the block's execution.
   *
   * @param block         The block of code to be executed while the write lock is held.
   * @param readWriteLock The [[ReadWriteLock]] instance whose write lock will be used during execution.
   * @return The result of executing the block.
   */
  @inline
  def withWriteLock[R](block: => R)(implicit readWriteLock: ReadWriteLock): R = {
    readWriteLock.writeLock().lock()
    try {
      block
    } finally {
      readWriteLock.writeLock().unlock()
    }
  }
}
