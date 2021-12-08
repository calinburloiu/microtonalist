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

package org.calinburloiu.music

import com.google.common.math.DoubleMath

package object intonation {
  /** Concert pitch frequency in Hz for central A4. */
  val ConcertPitchFreq: Double = 440.0

  def fromRealValueToCents(realValue: Double): Double = {
    require(realValue > 0.0, s"Expecting positive realValue, but got $realValue")

    1200.0 * DoubleMath.log2(realValue)
  }

  def fromRatioToCents(numerator: Int, denominator: Int): Double = {
    require(numerator > 0.0, s"Expecting positive numerator, but got $numerator")
    require(denominator > 0.0, s"Expecting positive denominator, but got $denominator")

    fromRealValueToCents(numerator.toDouble / denominator.toDouble)
  }

  def fromEdoToCents(edo: Int, count: Int): Double = {
    require(edo > 0, s"Expecting positive edo value, but got $edo")

    count.toDouble / edo * 1200
  }

  def fromCentsToRealValue(cents: Double): Double = Math.pow(2, cents / 1200)

  def fromEdoToRealValue(edo: Int, count: Int): Double = {
    require(edo > 0, s"Expecting positive edo value, but got $edo")

    Math.pow(2, count.toDouble / edo)
  }

  def fromCentsToHz(cents: Double, baseFreqHz: Double): Double = {
    require(baseFreqHz > 0.0, s"Expecting positive baseFreqHz, but got $baseFreqHz")

    baseFreqHz * fromCentsToRealValue(cents)
  }

  def fromHzToCents(freqHz: Double, baseFreqHz: Double): Double = {
    require(freqHz > 0.0, s"Expecting positive freqHz, but got $freqHz")
    require(baseFreqHz > 0.0, s"Expecting positive baseFreqHz, but got $baseFreqHz")

    fromRealValueToCents(freqHz / baseFreqHz)
  }

  def mod(x: Double, modulus: Double): Double = {
    if (modulus <= 0) throw new ArithmeticException(s"modulus $modulus must be greater than 0.0")
    val result = x % modulus
    if (result >= 0) result else result + modulus
  }
}
