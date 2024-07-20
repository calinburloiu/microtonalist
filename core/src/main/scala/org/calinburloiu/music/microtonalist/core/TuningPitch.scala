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

package org.calinburloiu.music.microtonalist.core

import com.google.common.math.DoubleMath
import org.calinburloiu.music.intonation.CentsInterval
import org.calinburloiu.music.scmidi.PitchClass

import scala.language.implicitConversions

/**
 * Class representing the tuning of a single pitch class with its deviation in cents from 12-EDO.
 *
 * @param pitchClass Pitch class semitone number: C is 0, C#/Db is 1, ..., B is 11
 * @param deviation  Deviation from the semitone in cents
 */
case class TuningPitch(pitchClass: PitchClass, deviation: Double) {
  def cents: Double = 100.0 * pitchClass + deviation

  def interval: CentsInterval = CentsInterval(cents)

  /**
   * Tells if the instance is overflowing. A `TuningPitch` is said to overflow if its `deviation` absolute
   * value exceeds 100 cents causing it to overlap with an another pitch class.
   *
   * @return true if it's overflowing, false otherwise
   */
  def isOverflowing: Boolean = Math.abs(deviation) >= 100.0

  def almostEquals(that: TuningPitch, tolerance: Double = 0.02): Boolean = {
    this.pitchClass == that.pitchClass && DoubleMath.fuzzyEquals(this.deviation, that.deviation, tolerance)
  }
}

object TuningPitch {
  implicit def fromPitchClass(pitchClass: PitchClass): TuningPitch = TuningPitch(pitchClass, 0.0)

  implicit def toPitchClass(tuningPitch: TuningPitch): PitchClass = tuningPitch.pitchClass

  implicit def toInt(tuningPitch: TuningPitch): Int = tuningPitch.pitchClass.number
}
