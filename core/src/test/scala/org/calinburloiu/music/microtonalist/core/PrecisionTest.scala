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

import org.calinburloiu.music.intonation._
import org.calinburloiu.music.microtonalist.core.PianoKeyboardTuningUtils._
import org.calinburloiu.music.scmidi.PitchClass
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class PrecisionTest extends AnyFunSuite with Matchers with TableDrivenPropertyChecks {

  test("changing interval class does not alter quarter tones mapping when using halfTolerance") {
    val scaleCents = CentsScale(0.0, 150.0, 300.0, 500.0, 700.0, 850.0, 1000.0)

    // Change the interval class from CentsInterval to an Interval by using the realValue.
    // This will lead to a Double precision error, e.g. 150.0 will not be precisely the same.
    val convertedIntervals = scaleCents.intervals.map { centsInterval: CentsInterval =>
      Interval(centsInterval.realValue)
    }
    val convertedScale = Scale(convertedIntervals.head, convertedIntervals.tail: _*)

    val autoTuningMapper = AutoTuningMapper(mapQuarterTonesLow = true, halfTolerance = 0.5e-2)
    val tuning = autoTuningMapper.mapScale(convertedScale, StandardTuningRef(PitchClass.C))

    // When setting halfTolerance to 0.0, the quarter tones are mapped to D and A, instead of
    // D flat and A flat, respectively.
    tuning.c should not be empty
    tuning.dFlat should not be empty
    tuning.d shouldBe empty
    tuning.eFlat should not be empty
    tuning.f should not be empty
    tuning.g should not be empty
    tuning.aFlat should not be empty
    tuning.a shouldBe empty
    tuning.bFlat should not be empty
  }

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
      (3.0,     false,      3),

      (-2.0,    true,       -2),
      (-2.3,    true,       -2),
      (-2.45,   true,       -3),
      (-2.5,    true,       -3),
      (-2.55,   true,       -3),
      (-2.7,    true,       -3),
      (-3.0,    true,       -3),

      (-2.0,    false,      -2),
      (-2.3,    false,      -2),
      (-2.45,   false,      -2),
      (-2.5,    false,      -2),
      (-2.55,   false,      -2),
      (-2.7,    false,      -3),
      (-3.0,    false,      -3)
    )
    //@formatter:on
    val tolerance = 0.1

    forAll(table) { (input, halfDown, output) =>
      roundWithTolerance(input, halfDown, tolerance) shouldEqual output
    }
  }
}
