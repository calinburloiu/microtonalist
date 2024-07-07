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
import play.api.libs.json.{Format, JsString, Json}

class JsonScaleFormatTest extends JsonFormatTestUtils {
  private val scaleFormat: JsonScaleFormat = new JsonScaleFormat(NoJsonPreprocessor,
    IntonationStandardComponentFormat.createComponentJsonFormat())

  private val centsScale = CentsScale("abc", 0.0, 204.3, 315.9, 498.5)
  private val centsScaleJson = Json.obj(
    "name" -> "abc",
    "intervals" -> Json.arr(
      0.0, 204.3, 315.9, 498.5
    )
  )

  "pitchIntervalFormatFor" should "either read on interval directly or get it from a scale pitch object" in {
    val format: Format[Interval] = JsonScaleFormat.pitchIntervalFormatFor(JustIntonationStandard)

    assertReads(format, JsString("7/4"), 7 /: 4)
    assertReads(format, Json.obj("name" -> "segah", "interval" -> "5/4"), 5 /: 4)
  }

  "A JSON Scale with pitches in cents" should "correctly create a CentsScale object" in {
    val result = scaleFormat.read(centsScaleJson, None)
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
    val result = scaleFormat.read(ratiosScaleJson, None)
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

    val result = scaleFormat.read(json, None)

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

    val result = scaleFormat.read(json, None)

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
      scaleFormat.read(json, None)
    }
  }
}
