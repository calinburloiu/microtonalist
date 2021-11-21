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

import scala.language.implicitConversions

package object intonation {
  /** Concert pitch frequency in Hz for central A4. */
  val ConcertPitchFreq: Double = 440.0

  implicit class PitchClass(val number: Int) extends AnyVal {
    /**
     * Call this method after creating an instance.
     *
     * Context: Scala value classes do not allow constructor validation.
     */
    def assertValid(): Unit = require(number >= 0 && number < 12, "0 <= pitchClass < 12")
  }

  object PitchClass {
    val C: PitchClass = 0
    val CSharp: PitchClass = 1
    val DFlat: PitchClass = 1
    val D: PitchClass = 2
    val DSharp: PitchClass = 3
    val EFlat: PitchClass = 3
    val E: PitchClass = 4
    val F: PitchClass = 5
    val FSharp: PitchClass = 6
    val GFlat: PitchClass = 6
    val G: PitchClass = 7
    val GSharp: PitchClass = 8
    val AFlat: PitchClass = 8
    val A: PitchClass = 9
    val ASharp: PitchClass = 10
    val BFlat: PitchClass = 10
    val B: PitchClass = 11

    implicit def toInt(pitchClass: PitchClass): Int = pitchClass.number
  }

  def log2(a: Double): Double = Math.log(a) / Math.log(2)

  def fromRealValueToCents(realValue: Double): Double = {
    require(realValue > 0.0, s"Expecting positive realValue, but got $realValue")

    1200.0 * log2(realValue)
  }

  def fromRatioToCents(numerator: Int, denominator: Int): Double = {
    require(numerator > 0.0, s"Expecting positive numerator, but got $numerator")
    require(denominator > 0.0, s"Expecting positive denominator, but got $denominator")

    fromRealValueToCents(numerator.toDouble / denominator.toDouble)
  }

  def fromCentsToRealValue(cents: Double): Double = Math.pow(2, cents / 1200)

  def fromCentsToHz(cents: Double, baseFreqHz: Double): Double = {
    require(baseFreqHz > 0.0, s"Expecting positive baseFreqHz, but got $baseFreqHz")

    baseFreqHz * fromCentsToRealValue(cents)
  }

  def fromHzToCents(freqHz: Double, baseFreqHz: Double): Double = {
    require(freqHz > 0.0, s"Expecting positive freqHz, but got $freqHz")
    require(baseFreqHz > 0.0, s"Expecting positive baseFreqHz, but got $baseFreqHz")

    fromRealValueToCents(freqHz / baseFreqHz)
  }
}
