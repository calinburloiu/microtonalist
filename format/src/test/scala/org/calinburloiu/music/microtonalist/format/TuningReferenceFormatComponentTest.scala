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

package org.calinburloiu.music.microtonalist.format

import org.calinburloiu.music.intonation.{CentsIntonationStandard, RatioInterval}
import org.calinburloiu.music.microtonalist.core.ConcertPitchTuningReference
import play.api.libs.json._

class TuningReferenceFormatComponentTest extends JsonFormatTestUtils {

  import JsonFormatTestUtils._

  private val jsonFormatComponent = TuningReferenceFormatComponent(CentsIntonationStandard).jsonFormatComponent

  private val failureTable = Table[JsPath, JsonPropertyCheck, String](
    ("path", "check", "expected JsonValidationError"),
    (__ \ "concertPitchToBaseInterval", DisallowedValues(JsString("bla")), "error.expected" +
      ".intervalForCentsIntonationStandard"),
    (__ \ "concertPitchToBaseInterval", AllowedTypes(JsonNumberType, JsonStringType), "error.expected" +
      ".intervalForCentsIntonationStandard"),
    (__ \ "baseMidiNote", DisallowedValues(JsNumber(-1)), "error.min"),
    (__ \ "baseMidiNote", DisallowedValues(JsNumber(128)), "error.max"),
    (__ \ "baseMidiNote", AllowedTypes(JsonNumberType), "error.expected.jsnumber"),

    (__ \ "concertPitchFrequency", AllowedTypes(JsonNumberType, JsonNullType), "error.expected.jsnumber"),
  )

  it should "check the failure table" in {
    val baselineJson = Json.obj(
      "type" -> "concertPitch",
      "concertPitchToBaseInterval" -> "2/3",
      "baseMidiNote" -> 60,
      "concertPitchFrequency" -> 440.0
    )
    assertFailureTable(jsonFormatComponent.reads, baselineJson, failureTable)
  }

  it should "deserialize a concertPitch TuningReference" in {
    val baselineJson = Json.obj(
      "type" -> "concertPitch",
      "concertPitchToBaseInterval" -> "2/3",
      "baseMidiNote" -> 60,
      "concertPitchFrequency" -> 440.0
    )
    assertReads(jsonFormatComponent.reads, baselineJson,
      ConcertPitchTuningReference(RatioInterval(2, 3), 60, 440.0))
  }
}
