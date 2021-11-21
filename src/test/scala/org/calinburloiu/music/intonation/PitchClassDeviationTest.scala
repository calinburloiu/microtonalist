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

import org.calinburloiu.music.tuning.AutoTuningMapperContext
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class PitchClassDeviationTest extends AnyFunSuite with Matchers with TableDrivenPropertyChecks {

  import PitchClassDeviation._

  test("roundToInt") {
    //@formatter:off
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
    //@formatter:on
    val tolerance = 0.1

    forAll(table) { (input, halfDown, output) =>
      roundToInt(input, halfDown, tolerance) shouldEqual output
    }
  }

  test("fromCents") {
    val tolerance = 0.1
    val downConfig = AutoTuningMapperContext(mapQuarterTonesLow = true, tolerance)
    val upConfig = AutoTuningMapperContext(mapQuarterTonesLow = false, tolerance)

    //@formatter:off
    val table = Table[Double, AutoTuningMapperContext, PitchClassDeviation](
      ("Input Cents", "autoTuningMapperContext", "PitchClass"),
      (145.0,          downConfig,                PitchClassDeviation(1, 45.0)),
      (150.0,          downConfig,                PitchClassDeviation(1, 50.0)),
      (155.0,          downConfig,                PitchClassDeviation(1, 55.0)),
      (145.0,          upConfig,                  PitchClassDeviation(2, -55.0)),
      (150.0,          upConfig,                  PitchClassDeviation(2, -50.0)),
      (155.0,          upConfig,                  PitchClassDeviation(2, -45.0)),

      (161.0,          downConfig,                PitchClassDeviation(2, -39.0)),
      (139.0,          upConfig,                  PitchClassDeviation(1, 39.0))
    )
    //@formatter:on

    forAll(table) { (inputCents, autoTuningMapperContext, pitchClassDeviation) =>
      fromCents(inputCents)(autoTuningMapperContext) shouldEqual pitchClassDeviation
    }
  }
}
