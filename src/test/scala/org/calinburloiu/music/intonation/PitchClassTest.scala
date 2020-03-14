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

import org.calinburloiu.music.tuning.PitchClassConfig
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.prop.TableDrivenPropertyChecks

class PitchClassTest extends FunSuite with Matchers with TableDrivenPropertyChecks {
  import PitchClass._

  test("roundToInt") {
    val table = Table[Double, Boolean, Int](
      ("input", "halfDown", "output"),
      (2.0,     true,       2),
      (2.3,     true,       2),
      (2.45,    true,       2),
      (2.5,     true,       2),
      (2.55,    true,       2),
      (2.7,     true,       3),
      (3.0,     true,       3),

      (2.0,     false,      2),
      (2.3,     false,      2),
      (2.45,    false,      3),
      (2.5,     false,      3),
      (2.55,    false,      3),
      (2.7,     false,      3),
      (3.0,     false,      3)
    )
    val tolerance = 0.1

    forAll(table) { (input, halfDown, output) =>
      roundToInt(input, halfDown, tolerance) shouldEqual output
    }
  }

  test("fromCents") {
    val tolerance = 0.1
    val downConfig = PitchClassConfig(mapQuarterTonesLow = true, tolerance)
    val upConfig = PitchClassConfig(mapQuarterTonesLow = false, tolerance)

    val table = Table[Double, PitchClassConfig, PitchClass](
      ("Input Cents", "PitchClassConfig", "PitchClass"),
      (145.0,         downConfig,         PitchClass(1, 45.0)),
      (150.0,         downConfig,         PitchClass(1, 50.0)),
      (155.0,         downConfig,         PitchClass(1, 55.0)),
      (145.0,         upConfig,           PitchClass(2, -55.0)),
      (150.0,         upConfig,           PitchClass(2, -50.0)),
      (155.0,         upConfig,           PitchClass(2, -45.0)),

      (161.0,         downConfig,         PitchClass(2, -39.0)),
      (139.0,         upConfig,           PitchClass(1, 39.0))
    )

    forAll(table) { (inputCents, pitchClassConfig, pitchClass) =>
      fromCents(inputCents)(pitchClassConfig) shouldEqual pitchClass
    }
  }
}
