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

import org.calinburloiu.music.intonation.{
  CentsIntonationStandard, EdoIntonationStandard, IntonationStandard,
  JustIntonationStandard
}
import play.api.libs.json.{Format, JsString, Json}

class JsonIntonationStandardPluginFormatTest extends JsonFormatTestUtils {
  val jsonPluginFormat: JsonPluginFormat[IntonationStandard] = JsonIntonationStandardPluginFormat
  val format: Format[IntonationStandard] = jsonPluginFormat.format

  "reads" should "deserialize a cents plugin" in {
    assertReads(format, JsString("cents"), CentsIntonationStandard)
    assertReads(format, Json.obj("type" -> "cents"), CentsIntonationStandard)
  }

  it should "deserialize a justIntonation plugin" in {
    assertReads(format, JsString("justIntonation"), JustIntonationStandard)
    assertReads(format, Json.obj("type" -> "justIntonation"), JustIntonationStandard)
  }

  it should "deserialize an edo plugin" in {
    assertReads(format, Json.obj("type" -> "edo", "countPerOctave" -> 31), EdoIntonationStandard(31))
  }

  "writes" should "serialize a cents plugin" in {
    format.writes(CentsIntonationStandard) shouldEqual JsString("cents")
  }

  it should "serialize a justIntonation plugin" in {
    format.writes(JustIntonationStandard) shouldEqual JsString("justIntonation")
  }

  it should "serialize an edo plugin" in {
    format.writes(EdoIntonationStandard(72)) shouldEqual Json.obj("type" -> "edo", "countPerOctave" -> 72)
  }
}
