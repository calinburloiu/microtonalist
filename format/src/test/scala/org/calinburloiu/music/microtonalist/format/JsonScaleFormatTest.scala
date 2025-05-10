/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.calinburloiu.music.intonation.*
import org.calinburloiu.music.intonation.CentsInterval.PostfixOperator
import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.scalactic.{Equality, TolerantNumerics}
import play.api.libs.json.*

// TODO #68 Test writeAsJsValue with defined context
// TODO #68 Test context-based conversion of cents scale to EDO

class JsonScaleFormatTest extends JsonFormatTestUtils {
  private val scaleFormat: JsonScaleFormat = new JsonScaleFormat(NoJsonPreprocessor)

  private val epsilon: Double = 1e-1
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  "pitchIntervalFormatFor" should "either read on interval directly or get it from a scale pitch object" in {
    val format: Format[Interval] = JsonScaleFormat.pitchIntervalFormatFor(JustIntonationStandard)

    assertReads(format, JsString("7/4"), 7 /: 4)
    assertReads(format, Json.obj("name" -> "segah", "interval" -> "5/4"), 5 /: 4)
  }

  behavior of "Reading a JSON Scale in cents intonation standard"

  private val centsScale = CentsScale("min-4", 0.0, 204.3, 315.9, 498.5)
  private val centsScaleJson = Json.obj(
    "name" -> "min-4",
    "intonationStandard" -> "cents",
    "pitches" -> Json.arr(
      0.0, 204.3, 315.9, 498.5
    )
  )

  it should "create a CentsScale object when all intervals are in " +
    "cents" in {
    // When
    val result = scaleFormat.read(centsScaleJson, None)
    // Then
    result shouldBe a[CentsScale]
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
    result shouldBe a[Scale[Interval]]
    result.intervals.map(_.cents) should contain theSameElementsAs Seq(0.0, 204.3, 315.6, 498.5)
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

  it should "fail if some intervals are invalid" in {
    def createJsonScale(jsIntervalValue: JsValue) = Json.obj(
      "name" -> "abc",
      "intonationStandard" -> "cents",
      "pitches" -> Json.arr(
        "204.3", jsIntervalValue, "498.5"
      )
    )

    for (jsIntervalValue <- Seq(JsString("xxx"), JsString("5:4"), Json.arr(2), JsObject.empty)) {
      assertThrows[InvalidJsonScaleException] {
        scaleFormat.read(createJsonScale(jsIntervalValue), None)
      }
    }
  }

  behavior of "Writing a scale with intervals in cents"

  it should "output a JSON Scale with cents intonation standard" in {
    // When
    val result = scaleFormat.writeAsJsValue(centsScale, None)
    // Then
    (result \ "intonationStandard").asOpt[String] should contain("cents")
    result shouldEqual centsScaleJson
  }

  behavior of "Reading a JSON Scale in just intonation standard"

  private val ratiosScale = RatiosScale("maj-4", (15, 16), (1, 1), (9, 8), (5, 4), (4, 3))
  private val ratiosScaleJson = Json.obj(
    "name" -> "maj-4",
    "intonationStandard" -> "justIntonation",
    "pitches" -> Json.arr(
      "15/16", "1/1", "9/8", "5/4", "4/3"
    )
  )

  it should "create a RatiosScale object when all intervals are ratios" in {
    // When
    val result = scaleFormat.read(ratiosScaleJson, None)
    // Then
    result shouldBe a[RatiosScale]
    result shouldEqual ratiosScale
  }

  private def createJustJsonScaleWithBadInterval(jsIntervalValue: JsValue): JsObject = {
    Json.obj(
      "name" -> "bad",
      "intonationStandard" -> "justIntonation",
      "pitches" -> Json.arr(
        "15/16", "1/1", jsIntervalValue, "5/4", "4/3"
      )
    )
  }

  it should "fail if there are intervals that are not expressed as ratios" in {
    for (jsIntervalValue <- Seq(JsString("204.3"), JsNumber(204), JsNumber(204.3), Json.arr(2, 0))) {
      assertThrows[InvalidJsonScaleException] {
        scaleFormat.read(createJustJsonScaleWithBadInterval(jsIntervalValue), None)
      }
    }
  }

  it should "fail if some intervals are invalid" in {
    for (jsIntervalValue <- Seq(JsString("xxx"), JsString("5:4"), Json.arr(2), JsObject.empty)) {
      assertThrows[InvalidJsonScaleException] {
        scaleFormat.read(createJustJsonScaleWithBadInterval(jsIntervalValue), None)
      }
    }
  }

  behavior of "Writing a scale with intervals as ratios"

  it should "output a JSON Scale with just intonation standard" in {
    // When
    val result = scaleFormat.writeAsJsValue(ratiosScale, None)
    // Then
    (result \ "intonationStandard").asOpt[String] should contain("justIntonation")
    result shouldEqual ratiosScaleJson
  }

  behavior of "Reading a JSON Scale in EDO intonation standard"

  private val edo53Scale = EdoScale("hicaz-4", 53, 0, 5, 17, 22)
  private val edo53ScaleJson = Json.obj(
    "name" -> "hicaz-4",
    "intonationStandard" -> Json.obj("type" -> "edo", "countPerOctave" -> 53),
    "pitches" -> Json.arr(0, 5, 17, 22)
  )

  private val edo72Scale = EdoScale("hicaz-4", 72, (0, 0), (1, +1), (4, -1), (5, 0))
  private val edo72RelativeIntervals = Json.arr(Json.arr(0, 0), Json.arr(1, +1), Json.arr(4, -1), Json.arr(5, 0))
  private val edo72MixedIntervals = Json.arr(0, Json.arr(1, +1), 23, Json.arr(5, 0))

  private def createEdo72ScaleJson(intervals: JsArray) = Json.obj(
    "name" -> "hicaz-4",
    "intonationStandard" -> Json.obj("type" -> "edo", "countPerOctave" -> 72),
    "pitches" -> intervals
  )

  it should "create an EdoScale object when all intervals are " +
    "expressed as absolute values in divisions" in {
    // When
    val result = scaleFormat.read(edo53ScaleJson, None)
    // Then
    result shouldBe a[EdoScale]
    result shouldEqual edo53Scale
  }

  it should "create an EdoScale object when some intervals are expressed relative to 12-EDO" in {
    // When
    val result = scaleFormat.read(createEdo72ScaleJson(edo72MixedIntervals), None)
    // Then
    result shouldBe a[EdoScale]
    result shouldEqual edo72Scale
  }

  it should "create a Scale object when some intervals are given as ratios" in {
    // Given
    val json = Json.obj(
      "name" -> "hicaz-4",
      "intonationStandard" -> Json.obj("type" -> "edo", "countPerOctave" -> 72),
      "pitches" -> Json.arr(
        "8/9", 0, Json.arr(1, +1), "5/4", "4/3", "10/7"
      )
    )
    val edo72 = EdoIntervalFactory(72)
    // When
    val result = scaleFormat.read(json, None)
    // Then
    result shouldBe a[Scale[Interval]]
    result shouldEqual Scale("hicaz-4", edo72(-2, 0), edo72(0, 0), edo72(1, +1), edo72(4, -1), edo72(5, 0), edo72(6, 1))
  }

  it should "fail if some intervals are invalid" in {
    def createJsonScale(jsIntervalValue: JsValue) = Json.obj(
      "name" -> "abc",
      "intonationStandard" -> Json.obj("type" -> "edo", "countPerOctave" -> 72),
      "pitches" -> Json.arr(
        "204.3", jsIntervalValue, "498.5"
      )
    )

    for (jsIntervalValue <- Seq(JsString("xxx"), JsString("5:4"), Json.arr(2), JsObject.empty)) {
      assertThrows[InvalidJsonScaleException] {
        scaleFormat.read(createJsonScale(jsIntervalValue), None)
      }
    }
  }

  behavior of "Writing a scale with intervals as EDO"

  it should "output a JSON Scale with EDO intonation standard and intervals " +
    "expressed as absolute values in divisions if division count per octave is not a multiple of 12" in {
    // When
    val result = scaleFormat.writeAsJsValue(edo53Scale, None)
    // Then
    (result \ "intonationStandard" \ "type").asOpt[String] should contain("edo")
    result shouldEqual edo53ScaleJson
  }

  it should "output a JSON Scale with EDO intonation standard and intervals expressed as relative to 12-EDO in " +
    "divisions if division count per octave is a multiple of 12" in {
    // When
    val result = scaleFormat.writeAsJsValue(edo72Scale, None)
    // Then
    (result \ "intonationStandard" \ "type").asOpt[String] should contain("edo")
    result shouldEqual createEdo72ScaleJson(edo72RelativeIntervals)
  }

  behavior of "Writing scales with mixed interval types"

  private val mixedScale = Scale("mixed", RealInterval.Unison, 204 cents, EdoInterval(53, 17), 4 /: 3)
  private val mixedScaleJson = Json.obj(
    "name" -> "mixed",
    "intonationStandard" -> Json.obj("type" -> "edo", "countPerOctave" -> 72),
    "pitches" -> Json.arr(Json.arr(0, 0), Json.arr(2, 0), Json.arr(4, -1), Json.arr(5, 0))
  )

  it should "fail if no context is provided" in {
    assertThrows[MissingContextScaleFormatException] {
      scaleFormat.writeAsJsValue(mixedScale, None)
    }
  }

  it should "fail if the context provides an intonation standard incompatible with some of the intervals" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(JustIntonationStandard)))
    // Then
    assertThrows[IncompatibleIntervalsScaleFormatException] {
      scaleFormat.writeAsJsValue(mixedScale, context)
    }
  }

  it should "output a JSON Scale with the intervals converted according to the intonation standard from the context " +
    "if possible" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(EdoIntonationStandard(72))))
    // When
    val result = scaleFormat.writeAsJsValue(mixedScale, context)
    // Then
    result shouldEqual mixedScaleJson
  }

  behavior of "In-context scales"

  private val min4JsonIntervals = Json.arr("9/8", "6/5", "4/3")
  private val min4Scale = RatiosScale("min-4", 1 /: 1, 9 /: 8, 6 /: 5, 4 /: 3)
  private val scaleJsonWithoutName = Json.obj(
    "intonationStandard" -> "justIntonation",
    "pitches" -> min4JsonIntervals
  )
  private val scaleJsonWithoutIntonationStandard = Json.obj(
    "name" -> "min-4",
    "pitches" -> min4JsonIntervals
  )

  "Reading a JSON Scale without a name" should "fail if the context does not provide one" in {
    assertThrows[MissingContextScaleFormatException] {
      scaleFormat.read(scaleJsonWithoutName, None)
    }
  }

  it should "take the name from the context" in {
    // Given
    val context = Some(ScaleFormatContext(name = Some("min-4")))
    // When
    val result = scaleFormat.read(scaleJsonWithoutName, context)
    // Then
    result.name shouldEqual "min-4"
    result shouldEqual min4Scale
  }

  it should "override the scale name with the one from the context" in {
    // Given
    val context = Some(ScaleFormatContext(name = Some("blah")))
    // When
    val result = scaleFormat.read(centsScaleJson, context)
    // Then
    result.name shouldEqual "blah"
  }

  "Reading a JSON Scale without intonation standard" should "fail if the context does not provide one" in {
    assertThrows[MissingContextScaleFormatException] {
      scaleFormat.read(scaleJsonWithoutIntonationStandard, None)
    }
  }

  it should "take the intonation standard from the context" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(JustIntonationStandard)))
    // When
    val result = scaleFormat.read(scaleJsonWithoutIntonationStandard, context)
    // Then
    result.intonationStandard should contain(JustIntonationStandard)
    result shouldEqual min4Scale
  }

  it should "use the scale name if the context does not provide an override" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(CentsIntonationStandard)))
    // When
    val result = scaleFormat.read(ratiosScaleJson, context)
    // Then
    result.intonationStandard should contain(CentsIntonationStandard)
    result.name shouldEqual "maj-4"
  }

  "Reading a concise JSON Scale" should "fail if the context does not provide a name and the intonation standard" in {
    assertThrows[MissingContextScaleFormatException] {
      scaleFormat.read(min4JsonIntervals, None)
    }
  }

  it should "take the name and the intonation standard from the context" in {
    // Given
    val context = Some(ScaleFormatContext(name = Some("min-4"), intonationStandard = Some(JustIntonationStandard)))
    // When
    val result = scaleFormat.read(min4JsonIntervals, context)
    // Then
    result.name shouldEqual "min-4"
    result.intonationStandard should contain(JustIntonationStandard)
    result shouldEqual min4Scale
  }

  "Reading a scale when its intonation is not convertible to the one from the context" should
    "fail if the context provides an intonation standard incompatible with some of the intervals" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(JustIntonationStandard)))
    // Then
    assertThrows[IncompatibleIntervalsScaleFormatException] {
      scaleFormat.read(centsScaleJson, context)
    }
  }

  "Reading a JSON Scale with extra metadata" should "successfully create a Scale instance" in {
    // Given
    val json = Json.obj(
      "name" -> "hicaz-4",
      "authorName" -> "John Doe",
      "authorSortName" -> "Doe, John",
      "description" -> "An Ottoman Hicaz tetrachord in 72-EDO.",
      "intonationStandard" -> Json.obj("type" -> "edo", "countPerOctave" -> 72),
      "pitches" -> Json.arr(
        Json.obj("name" -> "dugah", "interval" -> Json.arr(0, 0), "degree" -> 1, "role" -> "primary"),
        Json.obj("name" -> "segah", "interval" -> Json.arr(1, +1)),
        Json.obj("name" -> "hicaz", "interval" -> Json.arr(4, -1)),
        Json.obj("name" -> "neva", "interval" -> Json.arr(5, 0), "role" -> "secondary")
      )
    )

    // When
    val result = scaleFormat.read(json, None)
    // Then
    result shouldBe a[EdoScale]
    result shouldEqual edo72Scale
  }
}
