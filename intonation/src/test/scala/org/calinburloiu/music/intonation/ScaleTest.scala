/*
 * Copyright 2024 Calin-Andrei Burloiu
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

import org.calinburloiu.music.intonation.CentsInterval._
import org.calinburloiu.music.intonation.RatioInterval._
import org.calinburloiu.music.intonation.Scale._
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScaleTest extends AnyFlatSpec with Matchers {
  private val epsilon: Double = 1e-1
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  val mixedScale: Scale[Interval] = new Scale("mixed",
    Seq(RealInterval(1.0), CentsInterval(200.0), RatioInterval(5, 4), EdoInterval(72, 30)))
  val centsScale: CentsScale = CentsScale("cents", 0 cents, 100 cents, 400 cents, 500 cents)
  val ratiosScale: RatiosScale = RatiosScale("ratios", 1 /: 1, 7 /: 6, 5 /: 4, 4 /: 3)
  val edo72Scale: EdoScale = EdoScale("edo", 72, (0, 0), (2, 0), (3, +1), (5, 0))
  val allScales: Seq[Scale[Interval]] = Seq(mixedScale, centsScale, ratiosScale, edo72Scale)

  "apply" should "return the interval at the specified index" in {
    allScales.foreach(_(0).cents shouldEqual 0.0)

    mixedScale(1) shouldEqual CentsInterval(200.0)
    centsScale(1) shouldEqual (100 cents)
    ratiosScale(1) shouldEqual (7 /: 6)
    edo72Scale(2) shouldEqual EdoInterval(72, (3, +1))

    for (scale <- allScales) {
      assertThrows[IndexOutOfBoundsException] {
        scale(4)
      }
    }
  }

  "equals" should "tell if two scales have the same name and the same intervals" in {
    centsScale shouldNot equal(centsScale.rename("new"))
    centsScale shouldEqual centsScale

    mixedScale shouldEqual new Scale("mixed",
      Seq(RealInterval(1.0), CentsInterval(200.0), RatioInterval(5, 4), EdoInterval(72, 30)))
    centsScale shouldEqual CentsScale("cents", 0 cents, 100 cents, 400 cents, 500 cents)
    ratiosScale shouldEqual RatiosScale("ratios", 1 /: 1, 7 /: 6, 5 /: 4, 4 /: 3)
    edo72Scale shouldEqual EdoScale("edo", 72, (0, 0), (2, 0), (3, +1), (5, 0))
  }

  "size" should "tell how many intervals the scale has" in {
    allScales.foreach(_.size shouldEqual 4)
  }

  "transpose" should "add an interval to all scale pitches" in {
    centsScale.transpose(700 cents) shouldEqual CentsScale("cents", 700 cents, 800 cents, 1100 cents, 1200 cents)
    ratiosScale.transpose(3 /: 2) shouldEqual RatiosScale("ratios", 3 /: 2, 7 /: 4, 15 /: 8, 2 /: 1)
    edo72Scale.transpose(EdoInterval(72, (7, 0))) shouldEqual EdoScale("edo", 72, (7, 0), (9, 0), (10, +1), (12, 0))

    mixedScale.transpose(1200 cents)(2).cents shouldEqual 1586.31
    centsScale.transpose(2 /: 1)(1).cents shouldEqual 1300.0
    ratiosScale.transpose(701.96 cents)(1).cents shouldEqual 968.82
    edo72Scale.transpose(2 /: 1)(2).cents shouldEqual 1516.67
  }

  "rename" should "change the name of the scale" in {
    mixedScale.rename("new") shouldEqual new Scale("new", mixedScale.intervals)
    centsScale.rename("new") shouldEqual CentsScale("new", centsScale.intervals)
    ratiosScale.rename("new") shouldEqual RatiosScale("new", ratiosScale.intervals)
    edo72Scale.rename("new") shouldEqual EdoScale("new", edo72Scale.intervals)
  }

  "intonationStandard" should "return Some IntonationStandard if there is a consistent one" in {
    mixedScale.intonationStandard shouldBe empty
    centsScale.intonationStandard should contain(CentsIntonationStandard)
    ratiosScale.intonationStandard should contain(JustIntonationStandard)
    edo72Scale.intonationStandard should contain(EdoIntonationStandard(72))
  }

  "isCentsScale" should "tell if it's a scale that only has cents intervals" in {
    mixedScale.isCentsScale shouldBe false
    centsScale.isCentsScale shouldBe true
    ratiosScale.isCentsScale shouldBe false
    edo72Scale.isCentsScale shouldBe false
  }

  "isRatiosScale" should "tell if it's a scale that only has ratio intervals" in {
    mixedScale.isRatiosScale shouldBe false
    centsScale.isRatiosScale shouldBe false
    ratiosScale.isRatiosScale shouldBe true
    edo72Scale.isRatiosScale shouldBe false
  }

  "isEdoScale" should "tell if it's a scale that only has EDO intervals" in {
    mixedScale.isEdoScale shouldBe false
    centsScale.isEdoScale shouldBe false
    ratiosScale.isEdoScale shouldBe false
    edo72Scale.isEdoScale shouldBe true
  }

  "create with no intonationStandard" should "create a scale with the correct type" in {
    Scale.create("mixed",
      Seq(RealInterval(1.0), CentsInterval(200.0), RatioInterval(5, 4), EdoInterval(72, 30))) shouldEqual mixedScale
    Scale.create("cents", Seq(100 cents, 400 cents, 500 cents)) shouldEqual centsScale
    Scale.create("ratios", Seq(7 /: 6, 5 /: 4, 4 /: 3)) shouldEqual ratiosScale

    val edo = EdoIntervalFactory(72)
    Scale.create("edo", Seq(edo(12), edo(19), edo(30))) shouldEqual edo72Scale
  }

  it should "sort intervals in ascending order" in {
    Scale.create("shuffled", Seq(5 /: 4, EdoInterval(72, 30), 15 /: 16, 200.0 cents)) shouldEqual
      new Scale("shuffled", Seq(15 /: 16, RealInterval(1.0), 200 cents, 5 /: 4, EdoInterval(72, 30)))
  }

  "create with intonationStandard" should "create a scale with the correct type" in {
    val edo = EdoIntervalFactory(72)

    Scale.create("cents", Seq(100 cents, 400 cents, 500 cents), CentsIntonationStandard) shouldEqual centsScale
    Scale.create("ratios", Seq(7 /: 6, 5 /: 4, 4 /: 3), JustIntonationStandard) shouldEqual ratiosScale
    Scale.create("edo", Seq(edo(12), edo(19), edo(30)), EdoIntonationStandard(72)) shouldEqual edo72Scale

    Scale.create("cents", Seq(5 /: 4), CentsIntonationStandard)(1).cents shouldEqual 386.31
    Scale.create("cents", Seq(edo(23)), CentsIntonationStandard)(1).cents shouldEqual 383.33

    assertThrows[IllegalArgumentException] {
      Scale.create("ratios", Seq(100 cents), JustIntonationStandard)
    }
    assertThrows[IllegalArgumentException] {
      Scale.create("ratios", Seq(edo(12)), JustIntonationStandard)
    }

    Scale.create("edo", Seq(102 cents), EdoIntonationStandard(72))(1) shouldEqual EdoInterval(72, 6)
    Scale.create("edo", Seq(5 /: 4), EdoIntonationStandard(72))(1) shouldEqual EdoInterval(72, 23)
    Scale.create("edo", Seq(EdoInterval(31, 10)), EdoIntonationStandard(72))(1) shouldEqual EdoInterval(72, 23)
  }

  "convertToIntonationStandard" should "convert a scale if necessary to a given intonation standard" in {
    // When
    var result: Option[ScaleConversionResult] = mixedScale.convertToIntonationStandard(CentsIntonationStandard)
    // Then
    result should not be empty
    result.get.conversionQuality shouldEqual LosslessConversion
    result.get.scale.apply(2) shouldBe a[CentsInterval]

    // When
    result = mixedScale.convertToIntonationStandard(JustIntonationStandard)
    // Then
    result shouldBe empty

    // When
    result = mixedScale.convertToIntonationStandard(EdoIntonationStandard(72))
    // Then
    result should contain(ScaleConversionResult(
      EdoScale(
        "mixed",
        72,
        (0, 0), (2, 0), (4, -1), (5, 0)
      ),
      LossyConversion
    ))

    // When
    result = centsScale.convertToIntonationStandard(CentsIntonationStandard)
    // Then
    result should not be empty
    result.get.conversionQuality shouldEqual NoConversion
    result.get.scale shouldBe theSameInstanceAs(centsScale)

    // When
    result = ratiosScale.convertToIntonationStandard(JustIntonationStandard)
    // Then
    result should not be empty
    result.get.conversionQuality shouldEqual NoConversion
    result.get.scale shouldBe theSameInstanceAs(ratiosScale)

    // When
    result = edo72Scale.convertToIntonationStandard(EdoIntonationStandard(72))
    // Then
    result should not be empty
    result.get.conversionQuality shouldEqual NoConversion
    result.get.scale shouldBe theSameInstanceAs(edo72Scale)

    // When
    result = edo72Scale.convertToIntonationStandard(EdoIntonationStandard(31))
    // Then
    result should not be empty
    result.get.conversionQuality shouldEqual LossyConversion
    result.get.scale should not be theSameInstanceAs(edo72Scale)
    result.get.scale.apply(2) shouldEqual EdoInterval(31, 8)
  }

  "indexOfUnison" should "tell the index where a unison interval is in the scale's intervals, if any" in {
    allScales.foreach { scale => scale.indexOfUnison shouldEqual 0 }
    RatiosScale(3 /: 5, 15 /: 16, 1 /: 1, 9 /: 8, 5 /: 4).indexOfUnison shouldEqual 2
    RatiosScale(9 /: 8, 5 /: 4, 4 /: 3).indexOfUnison shouldEqual -1
  }
}
