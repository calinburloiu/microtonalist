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

import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class IntonationUtilsTest extends AnyFunSuite with TableDrivenPropertyChecks with Matchers {

  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  //@formatter:off
  private val commonTable = Table(
    ("real value",  "cents",  "Hz"),
    (0.75,          -498.04,  330.0),
    (1.0,           0.0,      440.0),
    (1.25,          386.31,   550.0),
    (1.5,           701.96,   660.0),
    (2.0,           1200.0,   880.0),
    (4.0,           2400.0,   1760.0)
  )
  //@formatter:on

  test("common conversions between real values, cents and Hz") {
    forAll(commonTable) { (realValue, cents, hz) =>
      fromRealValueToCents(realValue) should equal(cents)
      fromCentsToRealValue(cents) should equal(realValue)
      fromCentsToHz(cents, 440) should equal(hz)
      fromHzToCents(hz, 440) should equal(cents)
    }
  }

  test("fromRealValueToCents fails") {
    val table = Table("realValue", 0, -1)
    forAll(table) { realValue =>
      assertThrows[IllegalArgumentException](fromRealValueToCents(realValue))
    }
  }

  test("fromRatioToCents succeeds") {
    //@formatter:off
    val table = Table(
      ("numerator", "denominator",  "cents"),
      (3,           4,              -498.04),
      (1,           1,              0.0),
      (5,           4,              386.31),
      (3,           2,              701.96),
      (2,           1,              1200.0),
      (4,           1,              2400.0)
    )
    //@formatter:on

    forAll(table) { (numerator, denominator, cents) =>
      fromRatioToCents(numerator, denominator) shouldEqual cents
    }
  }

  test("fromRatioToCents fails") {
    //@formatter:off
    val table = Table(
      ("numerator", "denominator"),
      (3,           0),
      (1,           -1),
      (0,           4),
      (-3,           2),
    )
    //@formatter:on

    forAll(table) { (numerator, denominator) =>
      assertThrows[IllegalArgumentException](fromRatioToCents(numerator, denominator))
    }
  }

  test("fromCentsToHz fails") {
    val table = Table("baseFreqHz", 0, -1)
    forAll(table) { baseFreqHz =>
      assertThrows[IllegalArgumentException](fromCentsToHz(100.0, baseFreqHz))
    }
  }

  test("fromHzToCents fails") {
    val table = Table("(base)FreqHz", 0, -1)
    forAll(table) { value =>
      assertThrows[IllegalArgumentException](fromHzToCents(100.0, value))
      assertThrows[IllegalArgumentException](fromHzToCents(value, 100.0))
    }
  }

  test("gcd") {
    assertThrows[IllegalArgumentException](gcd(Nil))
    gcd(Seq(6)) shouldEqual 6
    gcd(Seq(9, 12)) shouldEqual 3
    gcd(Seq(36, 54)) shouldEqual 18
    gcd(Seq(36, 54, 30)) shouldEqual 6
  }

  test("lcm") {
    assertThrows[IllegalArgumentException](lcm(Nil))
    lcm(Seq(6)) shouldEqual 6
    lcm(Seq(9, 12)) shouldEqual 36
    lcm(Seq(20, 36, 18)) shouldEqual 180
  }
}
