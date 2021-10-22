/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.tuning

import com.google.common.base.Preconditions._

/**
 * Describes the tuning of keyed instrument, typically with a piano keyboard, by specifying a deviation in cents
 * for each 12-EDO key.
 *
 * A typically piano tuning might be specified only for each of the 12 pitch classes: C, C#\Db, ..., B. A tunings
 * might use a different value for each 88 piano keys.
 *
 * @param name       a human-friendly name for the tuning
 * @param deviations deviation in cents for each key
 */
case class Tuning(name: String,
                  override val deviations: Seq[Double]) extends TuningBase[Double] {

  /** Returns the deviation in cents for a particular key 0-based index. */
  def apply(index: Int): Double = {
    checkElementIndex(index, size)
    deviations(index)
  }

  /**
   * @return the size of the tuning
   */
  override def size: Int = deviations.size

  override def iterator: Iterator[Double] = deviations.iterator

  override def toString: String = {
    val superString = super.toString
    s"$name = $superString"
  }
}

object Tuning {

  /** The tuning for a 12-tone equal temperament, which has 0 cents deviation for each of the 12-keys. */
  val Edo12: Tuning = Tuning("Equal Temperament", Seq.fill(12)(0.0))

  def apply(name: String, headDeviation: Double, tailDeviations: Double*): Tuning =
    Tuning(name, headDeviation +: tailDeviations)
}
