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

import org.calinburloiu.music.intonation.CentsInterval.PostfixOperator
import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation._
import play.api.libs.json.{Format, JsString, Json}

class JsonScaleFormatTest extends JsonFormatTestUtils {
  private val scaleFormat: JsonScaleFormat = new JsonScaleFormat(NoJsonPreprocessor,
    IntonationStandardComponentFormat.createComponentJsonFormat())

  private val centsScale = CentsScale("min-4", 0.0, 204.3, 315.9, 498.5)
  private val centsScaleJson = Json.obj(
    "name" -> "min-4",
    "intonationStandard" -> "cents",
    "pitches" -> Json.arr(
      0.0, 204.3, 315.9, 498.5
    )
  )

  "pitchIntervalFormatFor" should "either read on interval directly or get it from a scale pitch object" in {
    val format: Format[Interval] = JsonScaleFormat.pitchIntervalFormatFor(JustIntonationStandard)

    assertReads(format, JsString("7/4"), 7 /: 4)
    assertReads(format, Json.obj("name" -> "segah", "interval" -> "5/4"), 5 /: 4)
  }

  "Reading a JSON Scale in cents intonation standard" should "create a CentsScale object when all intervals are in " +
    "cents" in {
    // When
    val result = scaleFormat.read(centsScaleJson, None)
    // Then
    result.getClass shouldEqual classOf[CentsScale]
    result shouldEqual centsScale
  }

  it should "create a Scale object when some intervals are ratios" in {
    // Given
    val scale = Json.obj(
      "name" -> "mix",
      "intonationStandard" -> Json.obj("type" -> "cents"),
      "pitches" -> Json.arr(204.3, "6/5", 498.5)
    )
    // When
    val result = scaleFormat.read(scale, None)
    // Then
    result.getClass shouldEqual classOf[Scale[Interval]]
    result shouldEqual new Scale("mix", Seq(RealInterval.Unison, 204.3 cents, 6 /: 5, 498.5 cents))
  }

  it should "fail if there are intervals written with EDO-style arrays" in {
    // Given
    val scale = Json.obj(
      "name" -> "bad",
      "intonationStandard" -> Json.obj("type" -> "cents"),
      "pitches" -> Json.arr(204.3, Json.arr(3, 1), 498.5)
    )
    // Then
    assertThrows[InvalidJsonScaleException] {
      scaleFormat.read(scale, None)
    }
  }

  "Writing a scale with intervals in cents" should "output a JSON Scale with cents intonation standard" in {
    // When
    val result = scaleFormat.writeAsJsValue(centsScale)
    // Then
    (result \ "intonationStandard").asOpt[String] should contain("cents")
    result shouldEqual centsScaleJson
  }

  private val ratiosScale = RatiosScale("maj-4", (15, 16), (1, 1), (9, 8), (5, 4), (4, 3))
  private val ratiosScaleJson = Json.obj(
    "name" -> "maj-4",
    "intonationStandard" -> "justIntonation",
    "pitches" -> Json.arr(
      "15/16", "1/1", "9/8", "5/4", "4/3"
    )
  )

  "Reading JSON Scale in just intonation standard" should "create a RatiosScale object when all intervals are ratios" in {
    // When
    val result = scaleFormat.read(ratiosScaleJson, None)
    // Then
    result.getClass shouldEqual classOf[RatiosScale]
    result shouldEqual ratiosScale
  }

  "Writing a scale with intervals as rations" should "output a JSON Scale with just intonation standard" in {
    // When
    val result = scaleFormat.writeAsJsValue(ratiosScale)
    // Then
    (result \ "intonationStandard").asOpt[String] should contain("justIntonation")
    result shouldEqual ratiosScaleJson
  }

  "Writing a scale with intervals of various types" should "fail if no context is provided" in {

  }

  it should "output a JSON Scale with the intervals converted according to the intonation standard from the context if possible" in {

  }

  // TODO #45

  "A JSON Scale without a name" should "correctly create a scale object with an empty string name" in {
    val json = Json.obj(
      "intonationStandard" -> "cents",
      "pitches" -> Json.arr(
        "204.3", "315.9", "498.5"
      )
    )

    val result = scaleFormat.read(json, None)

    result.name should be(empty)
  }

  "A JSON Scale with an invalid pitch" should "throw InvalidJsonScaleException" in {
    val json = Json.obj(
      "name" -> "abc",
      "intonationStandard" -> "cents",
      "pitches" -> Json.arr(
        "204.3", "xxx", "498.5"
      )
    )

    assertThrows[InvalidJsonScaleException] {
      scaleFormat.read(json, None)
    }
  }
}
