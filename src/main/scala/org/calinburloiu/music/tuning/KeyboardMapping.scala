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

package org.calinburloiu.music.tuning

import org.calinburloiu.music.intonation.PitchClass

case class KeyboardMapping(scaleDegrees: Seq[Option[Int]]) {
  require(scaleDegrees.size == 12, "There must be exactly 12 scale degree values")
  require(scaleDegrees.forall(_.getOrElse(0) >= 0), "Scale degrees must be natural numbers")

  def apply(pitchClass: PitchClass): Option[Int] = {
    pitchClass.assertValid()
    scaleDegrees(pitchClass)
  }

  def removed(pitchClass: PitchClass): KeyboardMapping = {
    updated(pitchClass, None)
  }

  def updated(pitchClass: PitchClass, value: Option[Int]): KeyboardMapping = {
    KeyboardMapping(scaleDegrees.updated(pitchClass, value))
  }

  def values: Seq[(PitchClass, Option[Int])] = iterator.toSeq

  def iterator: Iterator[(PitchClass, Option[Int])] = (PitchClass.values zip scaleDegrees).iterator
}

object KeyboardMapping {
  def apply(c: Option[Int] = None,
            cSharpOrDFlat: Option[Int] = None,
            d: Option[Int] = None,
            dSharpOrEFlat: Option[Int] = None,
            e: Option[Int] = None,
            f: Option[Int] = None,
            fSharpOrGFlat: Option[Int] = None,
            g: Option[Int] = None,
            gSharpOrAFlat: Option[Int] = None,
            a: Option[Int] = None,
            aSharpOrBFlat: Option[Int] = None,
            b: Option[Int] = None): KeyboardMapping = {
    KeyboardMapping(Seq(
      c, cSharpOrDFlat, d, dSharpOrEFlat, e, f, fSharpOrGFlat, g, gSharpOrAFlat, a, aSharpOrBFlat, b))
  }
}
