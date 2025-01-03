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

import org.calinburloiu.music.microtonalist.composition._
import play.api.libs.json.{JsNull, JsString, Json, __}

class JsonTuningMapperPluginFormatTest extends JsonFormatTestUtils {
  private val jsonPluginFormat: JsonPluginFormat[TuningMapper] = JsonTuningMapperPluginFormat
  private val format = jsonPluginFormat.format

  private val sparseJsonKeyboardMapping = Json.obj(
    "C" -> 0, "D" -> 3, "E" -> 4, "F" -> 6, "G" -> 9, "Ab" -> 11, "B" -> 12)
  private val denseJsonKeyboardMapping = Json.arr(0, JsNull, 3, JsNull, 4, 6, JsNull, 9, 11, JsNull, JsNull, 12)
  private val keyboardMapping = KeyboardMapping(c = Some(0), d = Some(3), e = Some(4), f = Some(6), g = Some(9),
    gSharpOrAFlat = Some(11), b = Some(12))

  behavior of "ManualTuningMapper JSON plugin format"

  it should "deserialize a manual type plugin" in {
    assertReads(
      format,
      Json.obj("type" -> "manual", "keyboardMapping" -> sparseJsonKeyboardMapping),
      ManualTuningMapper(keyboardMapping)
    )
  }

  it should "fail to deserialize a manual type plugin without mandatory settings" in {
    assertReadsSingleFailure(format, JsString("manual"), "error.path.missing")
  }

  it should "serialize a ManualTuningMapper instance" in {
    format.writes(ManualTuningMapper(keyboardMapping)) shouldEqual Json.obj(
      "type" -> "manual",
      "keyboardMapping" -> denseJsonKeyboardMapping
    )
  }

  behavior of "AutoTuningMapper JSON plugin format"

  it should "deserialize an auto type plugin without global settings" in {
    assertReads(
      format,
      Json.obj(
        "type" -> "auto",
        "shouldMapQuarterTonesLow" -> true,
        "quarterToneTolerance" -> 3.0,
        "softChromaticGenusMapping" -> "pseudoChromatic",
        "overrideKeyboardMapping" -> sparseJsonKeyboardMapping
      ),
      AutoTuningMapper(
        shouldMapQuarterTonesLow = true,
        quarterToneTolerance = 3.0,
        softChromaticGenusMapping = SoftChromaticGenusMapping.PseudoChromatic,
        overrideKeyboardMapping = keyboardMapping
      )
    )
  }

  it should "deserialize an auto type plugin with some settings defined globally" in {
    val formatWithGlobalSettings = jsonPluginFormat.formatWithRootGlobalSettings(Json.obj(
      "tuningMapper" -> Json.obj(
        "auto" -> Json.obj(
          "shouldMapQuarterTonesLow" -> true,
          // only defined here
          "quarterToneTolerance" -> 4.0,
        )
      )
    ))

    assertReads(
      formatWithGlobalSettings,
      Json.obj(
        "type" -> "auto",
        // overrides global settings
        "shouldMapQuarterTonesLow" -> false,
        // only defined here
        "softChromaticGenusMapping" -> "strict",
      ),
      AutoTuningMapper(
        shouldMapQuarterTonesLow = false,
        quarterToneTolerance = 4.0,
        softChromaticGenusMapping = SoftChromaticGenusMapping.Strict
      )
    )
  }

  it should "fail to deserialize an auto type plugin when a setting defined globally is invalid" in {
    val readsWithGlobalSettings = jsonPluginFormat.readsWithRootGlobalSettings(Json.obj(
      "tuningMapper" -> Json.obj(
        "auto" -> Json.obj(
          "shouldMapQuarterTonesLow" -> true,
          "quarterToneTolerance" -> 4.0,
          // has typo
          "softChromaticGenusMapping" -> "pseudoChromaticc"
        )
      )
    ))

    assertReadsFailure(
      readsWithGlobalSettings,
      JsString("auto"),
      __ \ "softChromaticGenusMapping",
      "error.plugin.type.unrecognized"
    )
  }

  it should "deserialize an auto type plugin with all settings defined globally" in {
    val formatWithGlobalSettings = jsonPluginFormat.formatWithRootGlobalSettings(Json.obj(
      "tuningMapper" -> Json.obj(
        "auto" -> Json.obj(
          "quarterToneTolerance" -> 5.0,
          "softChromaticGenusMapping" -> "strict",
        )
      )
    ))

    assertReads(
      formatWithGlobalSettings,
      JsString("auto"),
      AutoTuningMapper(
        shouldMapQuarterTonesLow = false,
        quarterToneTolerance = 5.0,
        softChromaticGenusMapping = SoftChromaticGenusMapping.Strict
      )
    )
  }

  it should "use the default instance when deserializing an auto type plugin without any settings" in {
    assertReads(format, JsString("auto"), AutoTuningMapper.Default)
  }

  it should "fail to deserialize when some settings is invalid" in {
    assertReadsSingleFailure(
      format,
      Json.obj(
        "type" -> "auto",
        "shouldMapQuarterTonesLow" -> "true"
      ),
      "error.expected.jsboolean"
    )

    val formatWithGlobalSettings = jsonPluginFormat.formatWithRootGlobalSettings(Json.obj(
      "tuningMapper" -> Json.obj(
        "auto" -> Json.obj(
          "quarterToneTolerance" -> "blah"
        )
      )
    ))
    assertReadsSingleFailure(formatWithGlobalSettings, JsString("auto"), "error.expected.jsnumber")
  }
}
