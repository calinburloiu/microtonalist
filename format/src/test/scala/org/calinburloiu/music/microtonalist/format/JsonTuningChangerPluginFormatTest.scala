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

import org.calinburloiu.music.microtonalist.tuner.PedalTuningChanger.Cc
import org.calinburloiu.music.microtonalist.tuner.{PedalTuningChanger, TuningChangeTriggers, TuningChanger}
import play.api.libs.json._

class JsonTuningChangerPluginFormatTest extends JsonFormatTestUtils {

  import JsonFormatTestUtils._

  private val jsonPluginFormat = JsonTuningChangerPluginFormat
  private val reads: Reads[TuningChanger] = jsonPluginFormat.reads

  behavior of "PedalTuningChanger JSON plugin format"

  private val ccTriggers: TuningChangeTriggers[Cc] = TuningChangeTriggers(
    previous = Some(100),
    next = Some(101),
    index = Map(
      1 -> 10,
      2 -> 20
    )
  )
  private val pedalTuningChanger = PedalTuningChanger(ccTriggers, threshold = 10, triggersThru = true)
  private val pedalTuningChangerJson = Json.obj(
    "type" -> "pedal",
    "triggers" -> Json.obj(
      "previous" -> 100,
      "next" -> 101,
      "index" -> Json.obj(
        "1" -> 10,
        "2" -> 20
      )
    ),
    "threshold" -> 10,
    "triggersThru" -> true
  )

  private val pedalTuningChangerFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("path", "check", "expected JsonValidationError"),

    (__ \ "triggers" \ "index" \ "2", DisallowedValues(JsNumber(-1), JsNumber(128)), "error.expected.uint7"),

    ((__ \ "threshold"), AllowedTypes(JsonNumberType), "error.expected.jsnumber"),
    ((__ \ "threshold"), DisallowedValues(JsNumber(-1)), "error.min"),
    ((__ \ "threshold"), DisallowedValues(JsNumber(127)), "error.max"),

    ((__ \ "triggersThru"), AllowedTypes(JsonBooleanType), "error.expected.jsboolean")
  )

  it should "deserialize a PedalTuningChanger with default value" in {
    assertReads(reads, JsString("pedal"), PedalTuningChanger())
    assertReads(reads, Json.obj("type" -> "pedal"), PedalTuningChanger())
  }

  it should "deserialize a PedalTuningChanger" in {
    assertReads(reads, pedalTuningChangerJson, pedalTuningChanger)
  }

  it should "fail to deserialize a MergeTuningReducer from invalid JSON" in {
      assertReadsFailureTable(reads, pedalTuningChangerJson, pedalTuningChangerFailureTable)
  }

  it should "serialize a PedalTuningChanger" in {
    jsonPluginFormat.writes.writes(pedalTuningChanger) shouldEqual pedalTuningChangerJson
  }
}
