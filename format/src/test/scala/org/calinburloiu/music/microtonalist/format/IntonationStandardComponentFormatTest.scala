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

import org.calinburloiu.music.microtonalist.core.{CentsIntonationStandard, EdoIntonationStandard, IntonationStandard, JustIntonationStandard}
import play.api.libs.json.{JsString, Json}

class IntonationStandardComponentFormatTest extends JsonFormatTestUtils[ComponentJsonFormat[IntonationStandard]] {
  override val format: ComponentJsonFormat[IntonationStandard] = IntonationStandardComponentFormat.createComponentJsonFormat()

  "reads" should "deserialize a cents component" in {
    assertReads(JsString("cents"), CentsIntonationStandard)
    assertReads(Json.obj("type" -> "cents"), CentsIntonationStandard)
  }

  it should "deserialize a justIntonation component" in {
    assertReads(JsString("justIntonation"), JustIntonationStandard)
    assertReads(Json.obj("type" -> "justIntonation"), JustIntonationStandard)
  }

  it should "deserialize an edo component" in {
    assertReads(Json.obj("type" -> "edo", "divisionCount" -> 31), EdoIntonationStandard(31))
  }

  "writes" should "serialize a cents component" in {
    format.writes(CentsIntonationStandard) shouldEqual JsString("cents")
  }

  it should "serialize a justIntonation component" in {
    format.writes(JustIntonationStandard) shouldEqual JsString("justIntonation")
  }

  it should "serialize an edo component" in {
    format.writes(EdoIntonationStandard(72)) shouldEqual Json.obj("type" -> "edo", "divisionCount" -> 72)
  }
}
