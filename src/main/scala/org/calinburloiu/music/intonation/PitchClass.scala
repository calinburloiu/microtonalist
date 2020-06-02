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

import com.google.common.base.Preconditions
import com.google.common.math.IntMath
import org.calinburloiu.music.tuning.PitchClassConfig

import scala.language.implicitConversions

/** Class representing the concept of pitch class in the 12-tone equal temperament system,
 * identified by the semitone number and an option deviation in cents.
 *
 * @param semitone  Pitch class semitone number: C is 0, C#/Db is 1, ..., B is 11
 * @param deviation Deviation from the semitone in cents
 */
case class PitchClass(semitone: Int, deviation: Double = 0.0) {
  Preconditions.checkArgument(semitone >= 0 && semitone < 12,
    "0 <= semitone < 12".asInstanceOf[Any])
  Preconditions.checkArgument(deviation >= -100.0 && deviation <= 100.0,
    "-100 <= deviation <= 100".asInstanceOf[Any])

  def cents: Double = 100.0 * semitone + deviation

  def +(interval: Interval)(implicit pitchClassConfig: PitchClassConfig): PitchClass = {
    val totalCents = cents + interval.normalize.cents
    PitchClass.fromCents(totalCents)
  }
}

object PitchClass {

  /** Creates a [[PitchClass]] from a cents value assumed relative to pitch class 0 (C note). */
  def fromCents(cents: Double)(implicit pitchClassConfig: PitchClassConfig): PitchClass = {
    val totalSemitones = roundToInt(cents / 100,
      pitchClassConfig.mapQuarterTonesLow, pitchClassConfig.halfTolerance)
    val deviation = cents - 100 * totalSemitones
    val semitone = IntMath.mod(totalSemitones, 12)

    PitchClass(semitone, deviation)
  }

  /** Creates a [[PitchClass]] from an interval assumed relative to pitch class 0 (C note). */
  def fromInterval(interval: Interval)(implicit pitchClassConfig: PitchClassConfig): PitchClass =
    fromCents(interval.normalize.cents)

  implicit def fromInt(semitone: Int): PitchClass = PitchClass(semitone)

  implicit def toInt(pitchClass: PitchClass): Int = pitchClass.semitone

  private[intonation] def roundToInt(value: Double, halfDown: Boolean, halfTolerance: Double): Int = {
    val fractional = value - Math.floor(value)
    val lowHalf = 0.5 - halfTolerance
    val highHalf = 0.5 + halfTolerance

    if (halfDown) {
      if (fractional <= highHalf)
        Math.floor(value).toInt
      else
        Math.ceil(value).toInt
    } else {
      if (fractional >= lowHalf)
        Math.ceil(value).toInt
      else
        Math.floor(value).toInt
    }
  }
}
