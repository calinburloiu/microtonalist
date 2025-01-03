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

package org.calinburloiu.music.microtonalist.composition

/**
 * Describes the tuning of a keyed instrument, typically with a piano keyboard, by specifying a deviation in cents
 * for each 12-EDO key.
 *
 * A typical piano tuning might be specified only for each of the 12 pitch classes: C, C#\Db, ..., B. A tuning
 * might use a different value for each 88 piano keys.
 *
 * @tparam U
 */
trait Tuning[U] extends Iterable[U] {
  require(deviations.nonEmpty, "Expecting a non-empty list of deviations")

  def name: String

  def deviations: Seq[U]

  def apply(index: Int): U

  /**
   * @return the size of the tuning
   */
  override def size: Int = deviations.size

  override def iterator: Iterator[U] = deviations.iterator

  override def toString: String = {
    val superString = super.toString
    s"$name = $superString"
  }
}
