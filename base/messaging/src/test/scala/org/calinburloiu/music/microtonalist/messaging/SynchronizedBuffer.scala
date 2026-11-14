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

package org.calinburloiu.music.microtonalist.messaging

import javax.annotation.concurrent.GuardedBy
import scala.collection.mutable

/**
 * A buffer that several threads may append to at once, and that a test reads as a snapshot.
 *
 * @tparam T the type of the elements
 */
final class SynchronizedBuffer[T] {

  @GuardedBy("this")
  private val buffer: mutable.ArrayBuffer[T] = mutable.ArrayBuffer()

  /**
   * Appends `element`.
   */
  def +=(element: T): Unit = synchronized {
    buffer += element
  }

  /**
   * Appends `elements`, in their order.
   */
  def ++=(elements: IterableOnce[T]): Unit = synchronized {
    buffer ++= elements
  }

  /**
   * Removes every element.
   */
  def clear(): Unit = synchronized {
    buffer.clear()
  }

  /**
   * The elements, in append order, as of the call.
   */
  def toSeq: Seq[T] = synchronized {
    buffer.toSeq
  }
}
