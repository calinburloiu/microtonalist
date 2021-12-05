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

package org.calinburloiu.music.microtuner.core

import org.calinburloiu.music.scmidi.PitchClass

/**
 * A mapping between a pitch class and a scale degree (0-based). Not all pitch classes need to be mapped.
 *
 * @param scaleDegrees array of scale degrees where the index represents the pitch class number
 */
case class KeyboardMapping(scaleDegrees: Seq[Option[Int]]) {
  require(scaleDegrees.size == 12, "There must be exactly 12 scale degree values")
  require(scaleDegrees.forall(_.getOrElse(0) >= 0), "Scale degrees must be natural numbers")

  /**
   * @return `Some` scale degree if the given pitch class was mapped or `None` otherwise
   */
  def apply(pitchClass: PitchClass): Option[Int] = {
    pitchClass.assertValid()
    scaleDegrees(pitchClass)
  }

  /**
   * @return a copy of the `KeyboardMapping` with the given pitch class not mapped to any scale degree
   */
  def removed(pitchClass: PitchClass): KeyboardMapping = {
    updated(pitchClass, None)
  }

  /**
   * @return a copy of the `KeyboardMapping` with the given pitch class mapped to the given scale degree
   */
  def updated(pitchClass: PitchClass, value: Option[Int]): KeyboardMapping = {
    KeyboardMapping(scaleDegrees.updated(pitchClass, value))
  }

  /**
   * @return a sequence of pairs each containing a pitch class and maybe a scale degree associated with it
   */
  def values: Seq[(PitchClass, Option[Int])] = iterator.toSeq

  /**
   * @return a [[Map]] from [[PitchClass]] to a scale degree. Pitch classes that are not mapped are not included.
   */
  def toMap: Map[PitchClass, Int] = values.flatMap {
    case (pitchClass, Some(scaleDegree)) => Some((pitchClass, scaleDegree))
    case _ => None
  }.toMap

  /**
   * @return an iterator of pairs each containing a pitch class and maybe a scale degree associated with it
   */
  def iterator: Iterator[(PitchClass, Option[Int])] = (PitchClass.values zip scaleDegrees).iterator

  /**
   * @return the number of pitch classes mapped to scale degrees
   */
  def size: Int = scaleDegrees.flatten.size

  /**
   * @return true is no pitch class is mapped to any scale degree, false otherwise
   */
  def isEmpty: Boolean = size == 0
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

  /**
   * @return a [[KeyboardMapping]] with no pitch class mapped to any scale degree
   */
  def empty: KeyboardMapping = KeyboardMapping(Seq.fill(12)(None))
}
