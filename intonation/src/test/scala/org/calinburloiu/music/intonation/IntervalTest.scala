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

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class RealIntervalTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {
  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  "a RealInterval" should "have a the real value greater than 0" in {
    assertThrows[IllegalArgumentException] {
      RealInterval(0.0)
    }
    assertThrows[IllegalArgumentException] {
      RealInterval(-0.1)
    }
    assertThrows[IllegalArgumentException] {
      RealInterval(-1.5)
    }
  }

  "realValue" should "return the same decimal value that was passed when the object was created" in {
    val table = Table("realValue", 1.0, 2.0, 1.5, 2.5, 0.8)
    forAll(table) { realValue =>
      RealInterval(realValue).realValue shouldEqual realValue
    }
  }

  "cents" should "return the value of the interval in cents" in {
    RealInterval.Unison.cents shouldEqual 0.0
    RealInterval.Octave.cents shouldEqual 1200.0
    RealInterval(3.0 / 2).cents shouldEqual 701.96
    RealInterval(5.0 / 2).cents shouldEqual 1586.31
    RealInterval(4.0 / 5).cents shouldEqual -386.31
  }

  "normalizing" should "put the interval between unison (inclusive) and octave (exclusive)" in {
    //@formatter:off
    val table = Table[RealInterval, RealInterval, Double, Double](
      ("input",                "normalize",             "normalizationFactor",  "normalizationLogFactor"),
      (RealInterval.Unison,    RealInterval(1.0 / 1),   1.0,                    0.0),
      (RealInterval(2.0 / 1),  RealInterval.Unison,     0.5,                    -1.0),
      (RealInterval(3.0 / 2),  RealInterval(3.0 / 2),   1.0,                    0.0),
      (RealInterval(5.0 / 2),  RealInterval(5.0 / 4),   0.5,                    -1.0),
      (RealInterval(13.0 / 3), RealInterval(13.0 / 12), 0.25,                   -2.0),
      (RealInterval(2.0 / 11), RealInterval(16.0 / 11), 8.0,                    3.0),
    )
    //@formatter:on

    forAll(table) { (input, normalized, normalizationFactor, normalizationLogFactor) =>
      input.isNormalized shouldEqual input == normalized
      input.normalize shouldEqual normalized
      input.normalizationFactor shouldEqual normalizationFactor
      input.normalizationLogFactor shouldEqual normalizationLogFactor
    }
  }

  "+" should "add two intervals musically (on the logarithmic scale)" in {
    // Neutral element
    (RealInterval.Unison + RealInterval.Unison) shouldEqual RealInterval.Unison
    (RealInterval.Unison + RealInterval(1.5)) shouldEqual RealInterval(1.5)
    // Commutative
    (RealInterval(5.0 / 4) + RealInterval(6.0 / 5)) shouldEqual RealInterval(3.0 / 2)
    (RealInterval(6.0 / 5) + RealInterval(5.0 / 4)) shouldEqual RealInterval(3.0 / 2)

    (RealInterval.Unison + RealInterval.Octave) shouldEqual RealInterval.Octave
    (RealInterval(1.5) + RealInterval.Octave) shouldEqual RealInterval(3.0)
  }

  "-" should "subtract two intervals musically (on the logarithmic scale)" in {
    // Neutral element
    (RealInterval.Unison - RealInterval.Unison) shouldEqual RealInterval.Unison
    (RealInterval(3.0 / 2) - RealInterval.Unison) shouldEqual RealInterval(3.0 / 2)

    (RealInterval(3.0 / 2) - RealInterval(6.0 / 5)) shouldEqual RealInterval(5.0 / 4)

    (RealInterval.Unison - RealInterval.Octave) shouldEqual RealInterval(1.0 / 2)
    (RealInterval(3.0 / 2) - RealInterval.Octave) shouldEqual RealInterval(3.0 / 4)
  }

  "*" should "repeatedly add an interval to itself musically (on the logarithmic scale)" in {
    // Neutral element
    (RealInterval.Unison * 5) shouldEqual RealInterval.Unison

    (RealInterval(9.0 / 8) * 2) shouldEqual RealInterval(81.0 / 64)

    // Synthonic comma
    (RealInterval(3.0 / 2) * 4 - RealInterval(5.0)) shouldEqual RealInterval(81.0 / 80)
  }

  "invert" should "compute the interval inversion" in {
    RealInterval.Unison.invert shouldEqual RealInterval.Octave
    RealInterval.Octave.invert shouldEqual RealInterval.Unison
    RealInterval(3.0 / 2).invert shouldEqual RealInterval(4.0 / 3)
    RealInterval(4.0 / 3).invert shouldEqual RealInterval(3.0 / 2)

    assertThrows[IllegalArgumentException] {
      RealInterval(3.0).invert
    }
    assertThrows[IllegalArgumentException] {
      RealInterval(0.67).invert
    }
  }

  "toStringLengthInterval" should "compute the ratio useful for reproducing intervals on vibrating strings" in {
    // Neutral element
    RealInterval.Unison.toStringLengthInterval shouldEqual RealInterval.Unison

    RealInterval.Octave.toStringLengthInterval shouldEqual RealInterval(1.0 / 2)
    RealInterval(5.0 / 4).toStringLengthInterval shouldEqual RealInterval(4.0 / 5)

    RealInterval(7.0 / 2).toStringLengthInterval shouldEqual RealInterval(2.0 / 7)
    RealInterval(2.0 / 3).toStringLengthInterval shouldEqual RealInterval(3.0 / 2)
  }

  "isUnison" should "correctly report if the interval is a unison" in {
    RealInterval(1.0).isUnison should be(true)
    RealInterval(1.5).isUnison should be(false)
  }

  "toRealInterval" should "convert the interval to a RealInterval" in {
    val interval = RealInterval(1.5)
    interval.toRealInterval shouldBe theSameInstanceAs (interval)
  }

  "toCentsInterval" should "convert the interval to a CentsInterval" in {
    RealInterval.Unison.toCentsInterval shouldEqual CentsInterval(0.0)
    RealInterval.Octave.toCentsInterval shouldEqual CentsInterval(1200.0)
    RealInterval(3.0 / 2).toCentsInterval shouldEqual CentsInterval(RatioInterval(3, 2).cents)
    RealInterval(5.0 / 2).toCentsInterval.cents should be > 1200.0
    RealInterval(4.0 / 5).toCentsInterval.cents should be < 0.0
  }
}

class RatioIntervalTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {
  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

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

  "realValue" should "return the decimal value of the frequency ratio" in {
    RatioInterval.Unison.realValue shouldEqual 1.0
    RatioInterval.Octave.realValue shouldEqual 2.0
    RatioInterval(3, 2).realValue shouldEqual 1.5
    RatioInterval(5, 2).realValue shouldEqual 2.5
    RatioInterval(4, 5).realValue shouldEqual 0.8
  }

  "cents" should "return the value of the interval in cents" in {
    RatioInterval.Unison.cents shouldEqual 0.0
    RatioInterval.Octave.cents shouldEqual 1200.0
    RatioInterval(3, 2).cents shouldEqual 701.96
    RatioInterval(5, 2).cents shouldEqual 1586.31
    RatioInterval(4, 5).cents shouldEqual -386.31
  }

  "normalizing" should "put the interval between unison (inclusive) and octave (exclusive)" in {
    //@formatter:off
    val table = Table[RatioInterval, RatioInterval, Double, Double](
      ("input", "normalize",  "normalizationFactor",  "normalizationLogFactor"),
      (1/:1,    1/:1,         1.0,                      0.0),
      (2/:1,    1/:1,         0.5,                      -1.0),
      (3/:2,    3/:2,         1.0,                      0.0),
      (5/:2,    5/:4,         0.5,                      -1.0),
      (13/:3,   13/:12,       0.25,                     -2.0),
      (2/:11,   16/:11,       8.0,                      3.0),
    )
    //@formatter:on

    forAll(table) { (input, normalized, normalizationFactor, normalizationLogFactor) =>
      input.isNormalized shouldEqual input == normalized
      input.normalize shouldEqual normalized
      input.normalizationFactor shouldEqual normalizationFactor
      input.normalizationLogFactor shouldEqual normalizationLogFactor
    }
  }

  "+" should "add two intervals musically (on the logarithmic scale)" in {
    // Neutral element
    (RatioInterval.Unison + RatioInterval.Unison) shouldEqual RatioInterval.Unison
    (RatioInterval.Unison + RatioInterval(3, 2)) shouldEqual RatioInterval(3, 2)
    // Commutative
    (RatioInterval(5, 4) + RatioInterval(6, 5)) shouldEqual RatioInterval(3, 2)
    (RatioInterval(6, 5) + RatioInterval(5, 4)) shouldEqual RatioInterval(3, 2)

    (RatioInterval.Unison + RatioInterval.Octave) shouldEqual RatioInterval.Octave
    (RatioInterval(3, 2) + RatioInterval.Octave) shouldEqual RatioInterval(3, 1)
  }

  "-" should "subtract two intervals musically (on the logarithmic scale)" in {
    // Neutral element
    (RatioInterval.Unison - RatioInterval.Unison) shouldEqual RatioInterval.Unison
    (RatioInterval(3, 2) - RatioInterval.Unison) shouldEqual RatioInterval(3, 2)

    (RatioInterval(3, 2) - RatioInterval(6, 5)) shouldEqual RatioInterval(5, 4)

    (RatioInterval.Unison - RatioInterval.Octave) shouldEqual RatioInterval(1, 2)
    (RatioInterval(3, 2) - RatioInterval.Octave) shouldEqual RatioInterval(3, 4)
  }

  "*" should "repeatedly add an interval to itself musically (on the logarithmic scale)" in {
    // Neutral element
    (RatioInterval.Unison * 5) shouldEqual RatioInterval.Unison

    (RatioInterval(9, 8) * 2) shouldEqual RatioInterval(81, 64)

    // Synthonic comma
    (RatioInterval(3, 2) * 4 - RatioInterval(5, 1)) shouldEqual RatioInterval(81, 80)
  }

  "invert" should "compute the interval inversion" in {
    RatioInterval.Unison.invert shouldEqual RatioInterval.Octave
    RatioInterval.Octave.invert shouldEqual RatioInterval.Unison
    RatioInterval(3, 2).invert shouldEqual RatioInterval(4, 3)
    RatioInterval(4, 3).invert shouldEqual RatioInterval(3, 2)

    assertThrows[IllegalArgumentException] {
      RatioInterval(3, 1).invert
    }
    assertThrows[IllegalArgumentException] {
      RatioInterval(2, 3).invert
    }
  }

  "toStringLengthInterval" should "compute the ratio useful for reproducing intervals on vibrating strings" in {
    // Neutral element
    RatioInterval.Unison.toStringLengthInterval shouldEqual RatioInterval.Unison

    RatioInterval.Octave.toStringLengthInterval shouldEqual RatioInterval(1, 2)
    RatioInterval(5, 4).toStringLengthInterval shouldEqual RatioInterval(4, 5)

    RatioInterval(7, 2).toStringLengthInterval shouldEqual RatioInterval(2, 7)
    RatioInterval(2, 3).toStringLengthInterval shouldEqual RatioInterval(3, 2)
  }

  "isUnison" should "correctly report if the interval is a unison" in {
    RatioInterval(1, 1).isUnison should be(true)
    RatioInterval(3, 2).isUnison should be(false)
  }

  "toRealInterval" should "convert the interval to a RealInterval" in {
    RatioInterval.Unison.toRealInterval shouldEqual RealInterval(1.0)
    RatioInterval.Octave.toRealInterval shouldEqual RealInterval(2.0)
    RatioInterval(3, 2).toRealInterval shouldEqual RealInterval(1.5)
    RatioInterval(5, 2).toRealInterval shouldEqual RealInterval(2.5)
    RatioInterval(4, 5).toRealInterval shouldEqual RealInterval(0.8)
  }

  "toCentsInterval" should "convert the interval to a CentsInterval" in {
    RatioInterval.Unison.toCentsInterval shouldEqual CentsInterval(0.0)
    RatioInterval.Octave.toCentsInterval shouldEqual CentsInterval(1200.0)
    RatioInterval(3, 2).toCentsInterval shouldEqual CentsInterval(RatioInterval(3, 2).cents)
    RatioInterval(5, 2).toCentsInterval.cents should be > 1200.0
    RatioInterval(4, 5).toCentsInterval.cents should be < 0.0
  }

  "harmonicSeriesOf" should "create a series with the harmonics that match for a set of ratio intervals" in {
    assertThrows[IllegalArgumentException](RatioInterval.harmonicSeriesOf())
    RatioInterval.harmonicSeriesOf(1/:1, 5/:4, 3/:2) shouldEqual Seq(4, 5, 6)
    RatioInterval.harmonicSeriesOf(1/:1, 4/:3, 3/:2) shouldEqual Seq(6, 8, 9)
    RatioInterval.harmonicSeriesOf(1/:1, 11/:8, 3/:2) shouldEqual Seq(8, 11, 12)
    RatioInterval.harmonicSeriesOf(1/:1, 4/:3, 11/:6) shouldEqual Seq(6, 8, 11)
    RatioInterval.harmonicSeriesOf(1/:1, 11/:8, 11/:6) shouldEqual Seq(24, 33, 44)
    RatioInterval.harmonicSeriesOf(1/:1, 5/:4, 3/:2, 7/:4) shouldEqual Seq(4, 5, 6, 7)
  }
}

class CentsIntervalTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {
  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  "realValue" should "return the decimal value of the frequency ratio" in {
    CentsInterval.Unison.realValue shouldEqual 1.0
    CentsInterval.Octave.realValue shouldEqual 2.0
    CentsInterval(701.96).realValue shouldEqual 1.5
    CentsInterval(1586.31).realValue shouldEqual 2.5
    CentsInterval(-386.31).realValue shouldEqual 0.8
  }

  "cents" should "return the same cents value that was passed when the object was created" in {
    val table = Table("cents", 0.0, 1200.0, 701.96, 1586.31, -386.31)
    forAll(table) { cents =>
      CentsInterval(cents).cents shouldEqual cents
    }
  }

  "normalizing" should "put the interval between unison (inclusive) and octave (exclusive)" in {
    //@formatter:off
    val table = Table[CentsInterval, CentsInterval, Double, Double](
      ("input",                 "normalize",            "normalizationFactor",    "normalizationLogFactor"),
      (CentsInterval(0.0),      CentsInterval(0.0),     1.0,                      0.0),
      (CentsInterval(1200.0),   CentsInterval(0.0),     0.5,                      -1.0),
      (CentsInterval(701.96),   CentsInterval(701.96),  1.0,                      0.0),
      (CentsInterval(1600.0),   CentsInterval(400.0),   0.5,                      -1.0),
      (CentsInterval(2533.33),  CentsInterval(133.33),  0.25,                     -2.0),
      (CentsInterval(-2951.32), CentsInterval(648.68),  8.0,                      3.0),
    )
    //@formatter:on

    forAll(table) { (input, normalized, normalizationFactor, normalizationLogFactor) =>
      input.isNormalized shouldEqual input == normalized
      input.normalize.cents shouldEqual normalized.cents
      input.normalizationFactor shouldEqual normalizationFactor
      input.normalizationLogFactor shouldEqual normalizationLogFactor
    }
  }

  "+" should "add two intervals musically (on the logarithmic scale)" in {
    // Neutral element
    (CentsInterval.Unison + CentsInterval.Unison) shouldEqual CentsInterval.Unison
    (CentsInterval.Unison + CentsInterval(700.0)) shouldEqual CentsInterval(700.0)
    // Commutative
    (CentsInterval(400.0) + CentsInterval(300.0)) shouldEqual CentsInterval(700.0)
    (CentsInterval(300.0) + CentsInterval(400.0)) shouldEqual CentsInterval(700.0)

    (CentsInterval.Unison + CentsInterval.Octave) shouldEqual CentsInterval.Octave
    (CentsInterval(350.0) + CentsInterval.Octave) shouldEqual CentsInterval(1550.0)
  }

  "-" should "subtract two intervals musically (on the logarithmic scale)" in {
    // Neutral element
    (CentsInterval.Unison - CentsInterval.Unison) shouldEqual CentsInterval.Unison
    (CentsInterval(850.0) - CentsInterval.Unison) shouldEqual CentsInterval(850.0)

    (CentsInterval(700.0) - CentsInterval(300.0)) shouldEqual CentsInterval(400.0)

    (CentsInterval.Unison - CentsInterval.Octave) shouldEqual CentsInterval(-1200.0)
    (CentsInterval(700.0) - CentsInterval.Octave) shouldEqual CentsInterval(-500.0)
  }

  "*" should "repeatedly add an interval to itself musically (on the logarithmic scale)" in {
    // Neutral element
    (CentsInterval.Unison * 5) shouldEqual CentsInterval.Unison

    (CentsInterval(150.0) * 2) shouldEqual CentsInterval(300.0)
    (CentsInterval(133.33) * 3).cents shouldEqual 400.0
  }

  "invert" should "compute the interval inversion" in {
    CentsInterval.Unison.invert shouldEqual CentsInterval.Octave
    CentsInterval.Octave.invert shouldEqual CentsInterval.Unison
    CentsInterval(700.0).invert shouldEqual CentsInterval(500.0)
    CentsInterval(500.0).invert shouldEqual CentsInterval(700.0)

    assertThrows[IllegalArgumentException] {
      CentsInterval(1900.0).invert
    }
    assertThrows[IllegalArgumentException] {
      CentsInterval(-500.0).invert
    }
  }

  "toStringLengthInterval" should "compute the ratio useful for reproducing intervals on vibrating strings" in {
    // Neutral element
    CentsInterval.Unison.toStringLengthInterval shouldEqual CentsInterval.Unison

    CentsInterval.Octave.toStringLengthInterval shouldEqual CentsInterval(-1200.0)
    CentsInterval(400.0).toStringLengthInterval shouldEqual CentsInterval(-400.0)

    CentsInterval(1550.0).toStringLengthInterval shouldEqual CentsInterval(-1550)
    CentsInterval(-701.96).toStringLengthInterval shouldEqual CentsInterval(701.96)
  }

  "isUnison" should "correctly report if the interval is a unison" in {
    CentsInterval(0.0).isUnison should be(true)
    CentsInterval(700.0).isUnison should be(false)
  }

  "toRealInterval" should "convert the interval to a RealInterval" in {
    CentsInterval.Unison.toRealInterval.realValue shouldEqual 1.0
    CentsInterval.Octave.toRealInterval.realValue shouldEqual 2.0
    CentsInterval(701.96).toRealInterval.realValue shouldEqual 1.5
    CentsInterval(1586.31).toRealInterval.realValue shouldEqual 2.5
    CentsInterval(-386.31).toRealInterval.realValue shouldEqual 0.8
  }

  "toCentsInterval" should "convert the same CentsInterval instance" in {
    val interval = CentsInterval(383.33)
    interval.toCentsInterval shouldBe theSameInstanceAs (interval)
  }
}

class EdoIntervalTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {
  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  "an EdoInterval" should "have edo value as a natural number" in {
    assertThrows[IllegalArgumentException] {
      EdoInterval(0, 3)
    }
    assertThrows[IllegalArgumentException] {
      EdoInterval(-1, 3)
    }
  }

  "an EdoInterval relative to standard 12-EDO" should "be created" in {
    EdoInterval(72, (7, 0)) shouldEqual EdoInterval(72, 42)
    EdoInterval(72, (4, -1)) shouldEqual EdoInterval(72, 23)
    EdoInterval(72, (3, +1)) shouldEqual EdoInterval(72, 19)

    EdoInterval(53, (7, 0)) shouldEqual EdoInterval(53, 31)
    EdoInterval(53, (4, -1)) shouldEqual EdoInterval(53, 17)
    EdoInterval(53, (3, +1)) shouldEqual EdoInterval(53, 14)

    EdoInterval(6, (7, 0)) shouldEqual EdoInterval(6, 4)
  }

  "1200-EDO intervals" should "give cents values equal to their counts" in {
    EdoInterval(1200, 0).cents shouldEqual 0.0
    EdoInterval(1200, 1200).cents shouldEqual 1200.0

    EdoInterval(1200, 700).cents shouldEqual 700.0
    EdoInterval(1200, 386).cents shouldEqual 386.0
    EdoInterval(1200, 702).cents shouldEqual 702.0

    EdoInterval(1200, -408).cents shouldEqual -408.0
    EdoInterval(1200, 2811).cents shouldEqual 2811.0
  }

  "realValue" should "return the decimal value of the frequency ratio" in {
    EdoInterval.unisonFor(17).realValue shouldEqual 1.0
    EdoInterval.octaveFor(31).realValue shouldEqual 2.0
    EdoInterval(72, 42).realValue shouldEqual 1.5
    EdoInterval(53, 53 + 17).realValue shouldEqual 2.5
    EdoInterval(53, -17).realValue shouldEqual 0.8
  }

  "cents" should "return the value of the interval in cents" in {
    EdoInterval.unisonFor(17).cents shouldEqual 0.0
    EdoInterval.octaveFor(31).cents shouldEqual 1200.0
    EdoInterval(72, 42).cents shouldEqual 700.0
    EdoInterval(53, 53 + 17).cents shouldEqual 1584.91
    EdoInterval(53, -17).cents shouldEqual -384.91
  }

  "normalizing" should "put the interval between unison (inclusive) and octave (exclusive)" in {
    //@formatter:off
    val table = Table[EdoInterval, EdoInterval, Double, Double](
      ("input",                 "normalize",            "normalizationFactor",    "normalizationLogFactor"),
      (EdoInterval(72, 0),      EdoInterval(72, 0),     1.0,                      0.0),
      (EdoInterval(53, 53),     EdoInterval(53, 0),     0.5,                      -1.0),
      (EdoInterval(53, 31),     EdoInterval(53, 31),    1.0,                      0.0),
      (EdoInterval(6, 8),       EdoInterval(6, 2),      0.5,                      -1.0),
      (EdoInterval(72, 152),    EdoInterval(72, 8),     0.25,                     -2.0),
      (EdoInterval(72, -177),   EdoInterval(72, 39),    8.0,                      3.0),
    )
    //@formatter:on

    forAll(table) { (input, normalized, normalizationFactor, normalizationLogFactor) =>
      input.isNormalized shouldEqual input == normalized
      input.normalize.cents shouldEqual normalized.cents
      input.normalizationFactor shouldEqual normalizationFactor
      input.normalizationLogFactor shouldEqual normalizationLogFactor
    }
  }

  "+" should "add two intervals musically (on the logarithmic scale)" in {
    // Neutral element
    (EdoInterval.unisonFor(36) + EdoInterval.unisonFor(36)) shouldEqual EdoInterval.unisonFor(36)
    (EdoInterval.unisonFor(36) + EdoInterval(36, 21)) shouldEqual EdoInterval(36, 21)
    // Commutative
    (EdoInterval(72, 24) + EdoInterval(72, 18)) shouldEqual EdoInterval(72, 42)
    (EdoInterval(72, 18) + EdoInterval(72, 24)) shouldEqual EdoInterval(72, 42)

    (EdoInterval.unisonFor(53) + EdoInterval.octaveFor(53)) shouldEqual EdoInterval.octaveFor(53)
    (EdoInterval(24, 7) + EdoInterval.octaveFor(24)) shouldEqual EdoInterval(24, 31)
  }

  "-" should "subtract two intervals musically (on the logarithmic scale)" in {
    // Neutral element
    (EdoInterval.unisonFor(72) - EdoInterval.unisonFor(72)) shouldEqual EdoInterval.unisonFor(72)
    (EdoInterval(72, 51) - EdoInterval.unisonFor(72)) shouldEqual EdoInterval(72, 51)

    (EdoInterval(53, 31) - EdoInterval(53, 14)) shouldEqual EdoInterval(53, 17)

    (EdoInterval.unisonFor(36) - EdoInterval.octaveFor(36)) shouldEqual EdoInterval(36, -36)
    (EdoInterval(5, 3) - EdoInterval.octaveFor(5)) shouldEqual EdoInterval(5, -2)
  }

  "*" should "repeatedly add an interval to itself musically (on the logarithmic scale)" in {
    // Neutral element
    (EdoInterval.unisonFor(24) * 5) shouldEqual EdoInterval.unisonFor(24)

    (EdoInterval(72, 9) * 2) shouldEqual EdoInterval(72, 18)
    (EdoInterval(72, 8) * 3) shouldEqual EdoInterval(72, 24)
  }

  "invert" should "compute the interval inversion" in {
    EdoInterval.unisonFor(72).invert shouldEqual EdoInterval.octaveFor(72)
    EdoInterval.octaveFor(72).invert shouldEqual EdoInterval.unisonFor(72)
    EdoInterval(53, 31).invert shouldEqual EdoInterval(53, 22)
    EdoInterval(53, 22).invert shouldEqual EdoInterval(53, 31)

    assertThrows[IllegalArgumentException] {
      EdoInterval(53, 84).invert
    }
    assertThrows[IllegalArgumentException] {
      EdoInterval(53, -22).invert
    }
  }

  "toStringLengthInterval" should "compute the ratio useful for reproducing intervals on vibrating strings" in {
    // Neutral element
    EdoInterval.unisonFor(72).toStringLengthInterval shouldEqual EdoInterval.unisonFor(72)

    EdoInterval.octaveFor(72).toStringLengthInterval shouldEqual EdoInterval(72, -72)
    EdoInterval(24, 8).toStringLengthInterval shouldEqual EdoInterval(24, -8)

    EdoInterval(24, 25).toStringLengthInterval shouldEqual EdoInterval(24, -25)
    EdoInterval(24, -14).toStringLengthInterval shouldEqual EdoInterval(24, 14)
  }

  "isUnison" should "correctly report if the interval is a unison" in {
    EdoInterval(72, 0).isUnison should be(true)
    EdoInterval(7, 0).isUnison should be(true)
    EdoInterval(72, 42).isUnison should be(false)
    EdoInterval(6, 5).isUnison should be(false)
  }

  "toRealInterval" should "convert the interval to a RealInterval" in {
    EdoInterval.unisonFor(17).toRealInterval shouldEqual RealInterval.Unison
    EdoInterval.octaveFor(31).toRealInterval shouldEqual RealInterval(2.0)
    EdoInterval(72, 42).toRealInterval.realValue shouldEqual RealInterval(1.5).realValue
    EdoInterval(53, 53 + 17).toRealInterval.realValue shouldEqual RealInterval(2.5).realValue
    EdoInterval(53, -17).toRealInterval.realValue shouldEqual RealInterval(0.8).realValue
  }

  "toCentsInterval" should "convert the interval to a CentsInterval" in {
    EdoInterval.unisonFor(17).toCentsInterval shouldEqual CentsInterval(0.0)
    EdoInterval.octaveFor(31).toCentsInterval shouldEqual CentsInterval(1200.0)
    EdoInterval(72, 42).toCentsInterval shouldEqual CentsInterval(700.0)
    EdoInterval(53, 53 + 17).toCentsInterval.cents shouldEqual CentsInterval(1584.91).cents
    EdoInterval(53, -17).toCentsInterval.cents shouldEqual CentsInterval(-384.91).cents
  }

  "EdoIntervalFactory" should "allow easy creation of intervals with the same EDO value" in {
    val moria = EdoIntervalFactory(72)

    moria(0) shouldEqual EdoInterval.unisonFor(72)
    moria.unison shouldEqual EdoInterval.unisonFor(72)

    moria(72) shouldEqual EdoInterval.octaveFor(72)
    moria.octave shouldEqual EdoInterval.octaveFor(72)

    moria(42) shouldEqual EdoInterval(72, 42)
    moria(7, 0) shouldEqual EdoInterval(72, 42)

    moria(4, -1) shouldEqual EdoInterval(72, 23)
    moria(3, +1) shouldEqual EdoInterval(72, 19)

    moria(-6) shouldEqual EdoInterval(72, -6)
    moria(-1, 0) shouldEqual EdoInterval(72, -6)

    moria(84) shouldEqual EdoInterval(72, 84)
  }
}

class IntervalTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers {
  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

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

  "all interval classes" should "correctly compute the cents, normalize and invert values for " +
    "the most common just intervals" in {
    forAll(validIntervals) { (ratioInterval, cents, normalizedRatioInterval, invertedRatioInterval) =>
      val interval = RealInterval(ratioInterval.numerator.toDouble / ratioInterval.denominator)
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

      if (ratioInterval >= RatioInterval.Unison && ratioInterval < RatioInterval.Octave) {
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

      if (ratioInterval >= RatioInterval.Unison && ratioInterval <= RatioInterval.Octave) {
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
      val intervalA = RealInterval(ratioIntervalA.realValue)
      val intervalB = RealInterval(ratioIntervalB.realValue)
      val intervalResult = RealInterval(ratioIntervalResult.realValue)
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
      val intervalA = RealInterval(ratioIntervalA.realValue)
      val intervalB = RealInterval(ratioIntervalB.realValue)
      val intervalResult = RealInterval(ratioIntervalResult.realValue)
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

  "the result of a binary operation between intervals of different classes" should
    "have the class of the most specific common superclass" in {
    //@formatter:off
    val table = Table[Interval, Double, Class[_]](
      ("computation",                                "result in ¢",  "class"),
      (RealInterval(1.5) + RatioInterval(4, 3),      1200.0,         classOf[RealInterval]),
      (RealInterval(1.5) - RatioInterval(4, 3),      203.91,         classOf[RealInterval]),
      (RatioInterval(3, 2) + RealInterval(4.0/3),    1200.0,         classOf[RealInterval]),
      (RatioInterval(3, 2) - RealInterval(4.0/3),    203.91,         classOf[RealInterval]),

      (RealInterval(1.5) + CentsInterval(498.04),    1200.0,         classOf[CentsInterval]),
      (RealInterval(1.5) - CentsInterval(498.04),    203.91,         classOf[CentsInterval]),
      (CentsInterval(701.96) + RealInterval(4.0/3),  1200.0,         classOf[CentsInterval]),
      (CentsInterval(701.96) - RealInterval(4.0/3),  203.91,         classOf[CentsInterval]),

      (RealInterval(1.5) + EdoInterval(72, 30),      1201.96,        classOf[RealInterval]),
      (RealInterval(1.5) - EdoInterval(72, 30),      201.96,         classOf[RealInterval]),
      (EdoInterval(72, 42) + RealInterval(4.0/3),    1198.04,        classOf[RealInterval]),
      (EdoInterval(72, 42) - RealInterval(4.0/3),    201.96,         classOf[RealInterval]),

      (RatioInterval(3, 2) + CentsInterval(498.04),  1200.0,         classOf[CentsInterval]),
      (RatioInterval(3, 2) - CentsInterval(498.04),  203.91,         classOf[CentsInterval]),
      (CentsInterval(701.96) + RatioInterval(4, 3),  1200.0,         classOf[CentsInterval]),
      (CentsInterval(701.96) - RatioInterval(4, 3),  203.91,         classOf[CentsInterval]),

      (RatioInterval(3, 2) + EdoInterval(72, 30),    1201.96,        classOf[RealInterval]),
      (RatioInterval(3, 2) - EdoInterval(72, 30),    201.96,         classOf[RealInterval]),
      (EdoInterval(72, 42) + RatioInterval(4, 3),    1198.04,        classOf[RealInterval]),
      (EdoInterval(72, 42) - RatioInterval(4, 3),    201.96,         classOf[RealInterval]),

      (CentsInterval(700.0) + EdoInterval(72, 30),   1200.0,         classOf[CentsInterval]),
      (CentsInterval(700.0) - EdoInterval(72, 30),   200.0,          classOf[CentsInterval]),
      (EdoInterval(72, 42) + CentsInterval(500.0),   1200.0,         classOf[CentsInterval]),
      (EdoInterval(72, 42) - CentsInterval(500.0),   200.0,          classOf[CentsInterval]),
    )
    //@formatter:on

    forAll(table) { (computation, cents, klass) =>
      computation.cents shouldEqual cents
      computation.getClass shouldEqual klass
    }
  }
}
