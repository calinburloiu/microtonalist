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

package org.calinburloiu.music.intonation

import com.google.common.math.IntMath
import org.calinburloiu.music.tuning.AutoTuningMapperContext

import scala.language.implicitConversions

/** Class representing the concept of pitch class in the 12-tone equal temperament system,
 * identified by the semitone number, along with a deviation in cents.
 *
 * @param pitchClass  Pitch class semitone number: C is 0, C#/Db is 1, ..., B is 11
 * @param deviation Deviation from the semitone in cents
 */
case class PitchClassDeviation(pitchClass: PitchClass, deviation: Double) {
  pitchClass.assertValid()

  def cents: Double = 100.0 * pitchClass + deviation

  def +(interval: Interval)(implicit context: AutoTuningMapperContext): PitchClassDeviation = {
    val totalCents = cents + interval.normalize.cents
    PitchClassDeviation.fromCents(totalCents)
  }

  /**
   * Tells if the instance is overflowing. A `PitchClassDeviation` is said to overflow if its `deviation` absolute
   * value exceeds 100 cents causing it to overlap with an another pitch class.
   * @return true if it's overflowing, false otherwise
   */
  def isOverflowing: Boolean = Math.abs(deviation) > 100.0
}

object PitchClassDeviation {
  /** Creates a [[PitchClassDeviation]] from a cents value assumed relative to pitch class 0 (C note). */
  def fromCents(cents: Double)(implicit context: AutoTuningMapperContext): PitchClassDeviation = {
    val totalSemitones = roundToInt(cents / 100,
      context.mapQuarterTonesLow, context.halfTolerance)
    val deviation = cents - 100 * totalSemitones
    val semitone = IntMath.mod(totalSemitones, 12)

    PitchClassDeviation(semitone, deviation)
  }

  /** Creates a [[PitchClassDeviation]] from an interval assumed relative to pitch class 0 (C note). */
  def fromInterval(interval: Interval)(implicit context: AutoTuningMapperContext): PitchClassDeviation =
    fromCents(interval.normalize.cents)

  implicit def fromPitchClass(pitchClass: PitchClass): PitchClassDeviation = PitchClassDeviation(pitchClass, 0.0)

  implicit def toPitchClass(pitchClassDeviation: PitchClassDeviation): PitchClass = pitchClassDeviation.pitchClass

  implicit def toInt(pitchClassDeviation: PitchClassDeviation): Int = pitchClassDeviation.pitchClass.number

  private[intonation] def roundToInt(value: Double, halfDown: Boolean, halfTolerance: Double): Int = {
    val fractional = value - Math.floor(value)
    val lowThreshold = 0.5 - halfTolerance
    val highThreshold = 0.5 + halfTolerance

    if (fractional >= lowThreshold && fractional <= highThreshold) {
      if (halfDown) Math.floor(value).toInt else Math.ceil(value).toInt
    } else {
      Math.round(value).toInt
    }
  }
}
