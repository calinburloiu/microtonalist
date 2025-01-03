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

import org.calinburloiu.music.microtonalist.composition.{DirectTuningReducer, MergeTuningReducer, TuningReducer}
import play.api.libs.json._

class JsonTuningReducerPluginFormatTest extends JsonFormatTestUtils {

  import JsonFormatTestUtils._

  private val jsonPluginFormat = JsonTuningReducerPluginFormat
  private val reads: Reads[TuningReducer] = jsonPluginFormat.reads

  behavior of "DirectTuningReducer JSON plugin format"

  it should "deserialize DirectTuningReducer" in {
    assertReads(reads, JsString("direct"), DirectTuningReducer)
  }

  it should "serialize DirectTuningReducer" in {
    jsonPluginFormat.writes.writes(DirectTuningReducer) shouldEqual JsString("direct")
  }

  behavior of "MergeTuningReducer JSON plugin format"

  private val mergeTypeJson = Json.obj(
    "type" -> "merge",
    "equalityTolerance" -> 3.0
  )
  private val mergeType = MergeTuningReducer(3.0)

  private val mergeTypeFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("path", "check", "expected JsonValidationError"),
    (__ \ "equalityTolerance", AllowedTypes(JsonNumberType, JsonNullType), "error.expected.jsnumber"),
    (__ \ "equalityTolerance", DisallowedValues(JsNumber(-51)), "error.min"),
    (__ \ "equalityTolerance", DisallowedValues(JsNumber(51)), "error.max")
  )

  it should "deserialize a MergeTuningReducer" in {
    assertReads(reads, mergeTypeJson, mergeType)
  }

  it should "fail to deserialize a MergeTuningReducer from invalid JSON" in {
    assertReadsFailureTable(reads, mergeTypeJson, mergeTypeFailureTable)
  }

  it should "serialize a MergeTuningReducer" in {
    jsonPluginFormat.writes.writes(mergeType) shouldEqual mergeTypeJson
  }
}
