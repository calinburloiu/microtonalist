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
 * Describes the tuning of a keyed instrument, typically with a piano keyboard, by specifying a deviation in cents
 * for each 12-EDO pitch class.
 *
 * @param name       a human-friendly name for the tuning
 * @param deviations deviation in cents for each key
 */
case class OctaveTuning(override val name: String,
                        override val deviations: Seq[Double]) extends Tuning[Double] {
  require(deviations.size == 12, "There should be exactly 12 deviations!")

  /** Returns the deviation in cents for a particular key 0-based index. */
  def apply(index: Int): Double = {
    checkElementIndex(index, size)
    deviations(index)
  }
}

object OctaveTuning {

  /** The tuning for a 12-tone equal temperament, which has 0 cents deviation for each of the 12-keys. */
  val Edo12: OctaveTuning = OctaveTuning("Equal Temperament", Seq.fill(12)(0.0))

  def apply(name: String,
            c: Double,
            cSharpOrDFlat: Double,
            d: Double,
            dSharpOrEFlat: Double,
            e: Double,
            f: Double,
            fSharpOrGFlat: Double,
            g: Double,
            gSharpOrAFlat: Double,
            a: Double,
            aSharpOrBFlat: Double,
            b: Double): OctaveTuning = {
    val deviations = Seq(c, cSharpOrDFlat, d, dSharpOrEFlat, e, f, fSharpOrGFlat, g,
      gSharpOrAFlat, a, aSharpOrBFlat, b)
    OctaveTuning(name, deviations)
  }
}
