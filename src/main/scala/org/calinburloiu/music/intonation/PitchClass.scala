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

package org.calinburloiu.music.intonation

import scala.language.implicitConversions

case class PitchClass private (number: Int) extends AnyVal {
  /**
   * Call this method after creating an instance.
   *
   * Context: Scala value classes do not allow constructor validation.
   */
  def assertValid(): Unit = require(number >= 0 && number < 12, "0 <= pitchClass < 12")

  /**
   * @return [[TuningPitch]] value in 12-EDO for `this` pitch class
   */
  def standardTuningPitch: TuningPitch = TuningPitch(this, 0.0)
}

object PitchClass {
  val C: PitchClass = fromInt(0)
  val CSharp: PitchClass = fromInt(1)
  val DFlat: PitchClass = fromInt(1)
  val D: PitchClass = fromInt(2)
  val DSharp: PitchClass = fromInt(3)
  val EFlat: PitchClass = fromInt(3)
  val E: PitchClass = fromInt(4)
  val F: PitchClass = fromInt(5)
  val FSharp: PitchClass = fromInt(6)
  val GFlat: PitchClass = fromInt(6)
  val G: PitchClass = fromInt(7)
  val GSharp: PitchClass = fromInt(8)
  val AFlat: PitchClass = fromInt(8)
  val A: PitchClass = fromInt(9)
  val ASharp: PitchClass = fromInt(10)
  val BFlat: PitchClass = fromInt(10)
  val B: PitchClass = fromInt(11)

  def fromInt(n: Int): PitchClass = {
    val result = PitchClass(n)
    result.assertValid()
    result
  }

  implicit def toInt(pitchClass: PitchClass): Int = pitchClass.number
}
