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

package org.calinburloiu.music.microtonalist.format

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{Format, JsNumber, JsString, Json, JsonValidationError}

class JsonScaleFormatTest extends AnyFlatSpec with Matchers {
  private val scaleFormat: JsonScaleFormat = new JsonScaleFormat(NoJsonPreprocessor)

  private val centsScale = CentsScale("abc", 0.0, 204.3, 315.9, 498.5)
  private val centsScaleJson = Json.obj(
    "name" -> "abc",
    "intervals" -> Json.arr(
      0.0, 204.3, 315.9, 498.5
    )
  )

  "intervalFormatFor" should "read cents and ratio intervals in CentsIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonScaleFormat.intervalFormatFor(CentsIntonationStandard)

      assertReads(JsNumber(203.91), CentsInterval(203.91))
      assertReads(JsNumber(-333.33), CentsInterval(-333.33))
      assertReads(JsNumber(0), CentsInterval(0))

      assertReads(JsString("5/4"), RatioInterval(5, 4))
      assertReads(JsString("8/9"), RatioInterval(8, 9))
      assertReadsFailure(JsString("blah"), JsonValidationError("error.expecting.ratioInterval"))
      assertReadsFailure(JsString("0/4"), JsonValidationError("error.expecting.ratioInterval"))
      assertReadsFailure(JsString("4/0"), JsonValidationError("error.expecting.ratioInterval"))
    }

  it should "read ratio intervals in JustIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonScaleFormat.intervalFormatFor(JustIntonationStandard)

      assertReads(JsString("5/4"), RatioInterval(5, 4))
      assertReads(JsString("8/9"), RatioInterval(8, 9))
      assertReadsFailure(JsString("blah"), JsonValidationError("error.expecting.ratioInterval"))
      assertReadsFailure(JsString("0/4"), JsonValidationError("error.expecting.ratioInterval"))
      assertReadsFailure(JsString("4/0"), JsonValidationError("error.expecting.ratioInterval"))
    }

  it should "read EDO and ratio intervals in EdoIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonScaleFormat.intervalFormatFor(EdoIntonationStandard(72))

      assertReads(JsNumber(30), EdoInterval(72, 30))
      assertReads(JsNumber(23), EdoInterval(72, 23))
      assertReads(JsNumber(19), EdoInterval(72, 19))

      assertReads(Json.arr(5, 0), EdoInterval(72, 30))
      assertReads(Json.arr(4, -1), EdoInterval(72, 23))
      assertReads(Json.arr(3, +1), EdoInterval(72, 19))

      assertReads(JsString("5/4"), RatioInterval(5, 4))
      assertReads(JsString("8/9"), RatioInterval(8, 9))
      assertReadsFailure(JsString("blah"), JsonValidationError("error.expecting.ratioInterval"))
      assertReadsFailure(JsString("0/4"), JsonValidationError("error.expecting.ratioInterval"))
      assertReadsFailure(JsString("4/0"), JsonValidationError("error.expecting.ratioInterval"))
    }

  it should "write a cents interval" in {
    val format = JsonScaleFormat.intervalFormatFor(CentsIntonationStandard)
    format.writes(CentsInterval(203.91)) shouldEqual JsNumber(203.91)
  }

  it should "write a ratio interval" in {
    val format = JsonScaleFormat.intervalFormatFor(CentsIntonationStandard)
    format.writes(5 /: 4) shouldEqual JsString("5/4")
  }

  it should "write EDO intervals" in {
    val format = JsonScaleFormat.intervalFormatFor(CentsIntonationStandard)
    format.writes(EdoInterval(31, 10)) shouldEqual JsNumber(10)
    format.writes(EdoInterval(72, 23)) shouldEqual Json.arr(4, -1)
  }

  "A JSON Scale with pitches in cents" should "correctly create a CentsScale object" in {
    val result = scaleFormat.read(centsScaleJson)
    result.getClass shouldEqual classOf[CentsScale]
    result shouldEqual centsScale
  }

  "A scale with pitches as cents" should "correctly be written as JSON" in {
    scaleFormat.writeAsJsValue(centsScale) shouldEqual centsScaleJson
  }

  private val ratiosScale = RatiosScale("abc", (1, 1), (9, 8), (5, 4), (4, 3))
  private val ratiosScaleJson = Json.obj(
    "name" -> "abc",
    "intervals" -> Json.arr(
      "1/1", "9/8", "5/4", "4/3"
    )
  )

  "A JSON Scale with pitches as ratios" should "correctly create a RatiosScale object" in {
    val result = scaleFormat.read(ratiosScaleJson)
    result.getClass shouldEqual classOf[RatiosScale]
    result shouldEqual ratiosScale
  }

  "A scale with pitches as rations" should "correctly be written as JSON" in {
    scaleFormat.writeAsJsValue(ratiosScale) shouldEqual ratiosScaleJson
  }

  "A JSON Scale with pitches in both cents and as ratios" should "correctly create a Scale object" in {
    val json = Json.obj(
      "name" -> "abc",
      "intervals" -> Json.arr(
        "9/8", "315.9", "4/3"
      )
    )

    val result = scaleFormat.read(json)

    result.getClass shouldEqual classOf[Scale[Interval]]
    result shouldEqual Scale("abc",
      RealInterval.Unison, RatioInterval(9, 8), CentsInterval(315.9), RatioInterval(4, 3))
  }

  "A JSON Scale without a name" should "correctly create a scale object with an empty string name" in {
    val json = Json.obj(
      "intervals" -> Json.arr(
        "204.3", "315.9", "498.5"
      )
    )

    val result = scaleFormat.read(json)

    result.name should be(empty)
  }

  "A JSON Scale with an invalid pitch" should "throw InvalidJsonScaleException" in {
    val json = Json.obj(
      "name" -> "abc",
      "intervals" -> Json.arr(
        "204.3", "xxx", "498.5"
      )
    )

    assertThrows[InvalidJsonScaleException] {
      scaleFormat.read(json)
    }
  }
}
