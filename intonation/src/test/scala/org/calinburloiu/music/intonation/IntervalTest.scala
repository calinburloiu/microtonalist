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

import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class IntervalTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers {

  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  //@formatter:off
  private val validIntervals = Table[RatioInterval, Double, RatioInterval, RatioInterval](
    ("RatioInterval",   "cents",  "RatioInterval.normalize",  "RatioInterval.invert"),
    ((3, 4),            -498.04,  (3, 2),                     null),
    ((1, 1),              0.00,   (1, 1),                     (2, 1)),
    ((256, 243),         90.22,   (256, 243),                 (243, 128)),
    ((16, 15),          111.73,   (16, 15),                   (15, 8)),
    ((9, 8),            203.91,   (9, 8),                     (16, 9)),
    ((32, 27),          294.13,   (32, 27),                   (27, 16)),
    ((6, 5),            315.64,   (6, 5),                     (5, 3)),
    ((5, 4),            386.31,   (5, 4),                     (8, 5)),
    ((81, 64),          407.82,   (81, 64),                   (128, 81)),
    ((9, 7),            435.08,   (9, 7),                     (14, 9)),
    ((4, 3),            498.04,   (4, 3),                     (3, 2)),
    ((7, 5),            582.51,   (7, 5),                     (10, 7)),
    ((3, 2),            701.96,   (3, 2),                     (4, 3)),
    ((128, 81),         792.18,   (128, 81),                  (81, 64)),
    ((8, 5),            813.69,   (8, 5),                     (5, 4)),
    ((5, 3),            884.36,   (5, 3),                     (6, 5)),
    ((27, 16),          905.87,   (27, 16),                   (32, 27)),
    ((7, 4),            968.82,   (7, 4),                     (8, 7)),
    ((16, 9),           996.09,   (16, 9),                    (9, 8)),
    ((9, 5),           1017.60,   (9, 5),                     (10, 9)),
    ((15, 8),          1088.27,   (15, 8),                    (16, 15)),
    ((2, 1),           1200.00,   (1, 1),                     (1, 1)),
    ((3, 1),           1901.96,   (3, 2),                     null),
    ((4, 1),           2400.00,   (1, 1),                     null),
    ((5, 1),           2786.31,   (5, 4),                     null),
    ((81, 16),         2807.82,   (81, 64),                   null),
  )
  //@formatter:on

  "an Interval" should "have a the real value greater than 0" in {
    assertThrows[IllegalArgumentException] {
      Interval(0.0)
    }
    assertThrows[IllegalArgumentException] {
      Interval(-0.1)
    }
    assertThrows[IllegalArgumentException] {
      Interval(-1.5)
    }
  }

  "a RatioInterval" should "have the numerator and denominator greater than 0" in {
    val values = Table("value", 0, -1, -2, -3)

    forAll(values) { value =>
      assertThrows[IllegalArgumentException] {
        RatioInterval(value, 2)
      }
      assertThrows[IllegalArgumentException] {
        RatioInterval(3, value)
      }
    }
  }

  "all interval classes" should "correctly compute the cents, normalize and invert values for " +
    "the most common just intervals" in {
    forAll(validIntervals) { (ratioInterval, cents, normalizedRatioInterval, invertedRatioInterval) =>
      val interval = Interval(ratioInterval.numerator.toDouble / ratioInterval.denominator)
      val centsInterval = CentsInterval(cents)

      withClue("Interval.cents:") {
        interval.cents shouldEqual cents
      }
      withClue("RatioInterval.cents:") {
        ratioInterval.cents shouldEqual cents
      }
      withClue("CentsInterval.cents:") {
        centsInterval.cents shouldEqual cents
      }

      withClue("Interval.isNormalized:") {
        interval.isNormalized shouldEqual (ratioInterval == normalizedRatioInterval)
      }
      withClue("RatioInterval.isNormalized:") {
        ratioInterval.isNormalized shouldEqual (ratioInterval == normalizedRatioInterval)
      }
      withClue("CentsInterval.isNormalized:") {
        centsInterval.isNormalized shouldEqual (ratioInterval == normalizedRatioInterval)
      }

      withClue("Interval.normalize:") {
        interval.normalize.realValue shouldEqual normalizedRatioInterval.realValue
      }
      withClue("RatioInterval.normalize:") {
        ratioInterval.normalize shouldEqual normalizedRatioInterval
      }
      withClue("CentsInterval.normalize:") {
        centsInterval.normalize.cents shouldEqual normalizedRatioInterval.cents
      }

      if (ratioInterval >= RatioInterval(1, 1) && ratioInterval < RatioInterval(2, 1)) {
        withClue("Interval.normalize same instance:") {
          interval.normalize should be theSameInstanceAs interval
        }
        withClue("RatioInterval.normalize same instance:") {
          ratioInterval.normalize should be theSameInstanceAs ratioInterval
        }
        withClue("CentsInterval.normalize same instance:") {
          centsInterval.normalize should be theSameInstanceAs centsInterval
        }
      }

      if (ratioInterval >= RatioInterval(1, 1) && ratioInterval <= RatioInterval(2, 1)) {
        withClue("Interval.invert:") {
          interval.invert.realValue shouldEqual invertedRatioInterval.realValue
        }
        withClue("RatioInterval.invert:") {
          ratioInterval.invert shouldEqual invertedRatioInterval
        }
        withClue("CentsInterval.invert:") {
          centsInterval.invert.cents shouldEqual invertedRatioInterval.cents
        }
      } else {
        assertThrows[IllegalArgumentException](interval.invert)
        assertThrows[IllegalArgumentException](ratioInterval.invert)
        assertThrows[IllegalArgumentException](centsInterval.invert)
      }
    }
  }

  they should "correctly add" in {
    val table = Table[RatioInterval, RatioInterval, RatioInterval](
      ("a", "b", "result"),
      ((1, 1), (1, 1), (1, 1)),
      ((3, 4), (4, 3), (1, 1)),
      ((3, 4), (3, 2), (9, 8)),
      ((5, 4), (6, 5), (3, 2)),
      ((1, 1), (5, 4), (5, 4)),
      ((3, 2), (4, 3), (2, 1)),
      ((7, 4), (4, 3), (7, 3)),
      ((2, 1), (3, 2), (3, 1)),
      ((3, 1), (3, 2), (9, 2)),
      ((3, 1), (3, 1), (9, 1)),
    )

    forAll(table) { (ratioIntervalA, ratioIntervalB, ratioIntervalResult) =>
      val intervalA = Interval(ratioIntervalA.realValue)
      val intervalB = Interval(ratioIntervalB.realValue)
      val intervalResult = Interval(ratioIntervalResult.realValue)
      (intervalA + intervalB).realValue shouldEqual intervalResult.realValue

      ratioIntervalA + ratioIntervalB shouldEqual ratioIntervalResult

      val centsIntervalA = CentsInterval(ratioIntervalA.cents)
      val centsIntervalB = CentsInterval(ratioIntervalB.cents)
      val centsIntervalResult = CentsInterval(ratioIntervalResult.cents)
      (centsIntervalA + centsIntervalB).cents shouldEqual centsIntervalResult.cents

      (intervalA + ratioIntervalB).realValue shouldEqual intervalResult.realValue
      (intervalA + centsIntervalB).realValue shouldEqual intervalResult.realValue
      (ratioIntervalA + intervalB).realValue shouldEqual ratioIntervalResult.realValue
      (ratioIntervalA + centsIntervalB).realValue shouldEqual ratioIntervalResult.realValue
      (centsIntervalA + intervalB).realValue shouldEqual centsIntervalResult.realValue
      (centsIntervalA + ratioIntervalB).realValue shouldEqual centsIntervalResult.realValue
    }
  }

  they should "correctly subtract" in {
    val table = Table[RatioInterval, RatioInterval, RatioInterval](
      ("a", "b", "result"),
      ((1, 1), (1, 1), (1, 1)),
      ((8, 9), (32, 27), (3, 4)),
      ((4, 3), (3, 2), (8, 9)),
      ((3, 2), (5, 4), (6, 5)),
      ((3, 2), (3, 2), (1, 1)),
      ((3, 1), (3, 2), (2, 1)),
      ((5, 1), (4, 3), (15, 4)),
      ((5, 1), (3, 1), (5, 3)),
    )

    forAll(table) { (ratioIntervalA, ratioIntervalB, ratioIntervalResult) =>
      val intervalA = Interval(ratioIntervalA.realValue)
      val intervalB = Interval(ratioIntervalB.realValue)
      val intervalResult = Interval(ratioIntervalResult.realValue)
      (intervalA - intervalB).realValue shouldEqual intervalResult.realValue

      ratioIntervalA - ratioIntervalB shouldEqual ratioIntervalResult

      val centsIntervalA = CentsInterval(ratioIntervalA.cents)
      val centsIntervalB = CentsInterval(ratioIntervalB.cents)
      val centsIntervalResult = CentsInterval(ratioIntervalResult.cents)
      (centsIntervalA - centsIntervalB).cents shouldEqual centsIntervalResult.cents

      (intervalA - ratioIntervalB).realValue shouldEqual intervalResult.realValue
      (intervalA - centsIntervalB).realValue shouldEqual intervalResult.realValue
      (ratioIntervalA - intervalB).realValue shouldEqual ratioIntervalResult.realValue
      (ratioIntervalA - centsIntervalB).realValue shouldEqual ratioIntervalResult.realValue
      (centsIntervalA - intervalB).realValue shouldEqual centsIntervalResult.realValue
      (centsIntervalA - ratioIntervalB).realValue shouldEqual centsIntervalResult.realValue
    }
  }

  they should "correctly report if they are unison" in {
    Interval(1.0).isUnison should be(true)
    Interval(1.5).isUnison should be(false)

    CentsInterval(0.0).isUnison should be(true)
    CentsInterval(700.0).isUnison should be(false)

    RatioInterval(1, 1).isUnison should be(true)
    RatioInterval(3, 2).isUnison should be(false)
  }

  "the result of a binary operation between intervals of the same class" should
    "keep the same class" in {
    (CentsInterval(700.0) + CentsInterval(500.0)).getClass shouldBe classOf[CentsInterval]
    (CentsInterval(700.0) - CentsInterval(500.0)).getClass shouldBe classOf[CentsInterval]
    (CentsInterval(700.0) + CentsInterval(500.0).asInstanceOf[Interval]).getClass shouldBe classOf[CentsInterval]
    (CentsInterval(700.0) - CentsInterval(500.0).asInstanceOf[Interval]).getClass shouldBe classOf[CentsInterval]

    (RatioInterval(3, 2) + RatioInterval(4, 3)).getClass shouldBe classOf[RatioInterval]
    (RatioInterval(3, 2) - RatioInterval(4, 3)).getClass shouldBe classOf[RatioInterval]
    (RatioInterval(3, 2) + RatioInterval(4, 3).asInstanceOf[Interval]).getClass shouldBe classOf[RatioInterval]
    (RatioInterval(3, 2) - RatioInterval(4, 3).asInstanceOf[Interval]).getClass shouldBe classOf[RatioInterval]
  }

  "the result of a binary operation between intervals of different classe" should
    "have the class of the most specific common superclass" in {
    (CentsInterval(700.0) + Interval(1.33)).getClass shouldBe classOf[Interval]
    (CentsInterval(700.0) - Interval(1.33)).getClass shouldBe classOf[Interval]

    (RatioInterval(3, 2) + Interval(1.33)).getClass shouldBe classOf[Interval]
    (RatioInterval(3, 2) - Interval(1.33)).getClass shouldBe classOf[Interval]

    (CentsInterval(700.0) + RatioInterval(4, 3)).getClass shouldBe classOf[Interval]
    (CentsInterval(700.0) - RatioInterval(4, 3)).getClass shouldBe classOf[Interval]
    (CentsInterval(700.0) + RatioInterval(4, 3).asInstanceOf[Interval]).getClass shouldBe classOf[Interval]
    (CentsInterval(700.0) - RatioInterval(4, 3).asInstanceOf[Interval]).getClass shouldBe classOf[Interval]
  }

}
