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

import org.calinburloiu.music.microtonalist.composition.KeyboardMapping
import play.api.libs.json._

class KeyboardMappingFormatTest extends JsonFormatTestUtils {
  private val sampleKeyboardMapping = KeyboardMapping(c = Some(0), d = Some(3), e = Some(4), f = Some(6), g = Some(9),
    gSharpOrAFlat = Some(11), b = Some(12))
  private val sampleDenseJsonKeyboardMapping = Json.arr(0, JsNull, 3, JsNull, 4, 6, JsNull, 9, 11, JsNull, JsNull, 12)

  "reads" should "deserialize a dense KeyboardMapping" in {
    assertReads(
      KeyboardMappingFormat.reads,
      sampleDenseJsonKeyboardMapping,
      sampleKeyboardMapping
    )
  }

  it should "deserialize a sparse KeyboardMapping" in {
    assertReads(
      KeyboardMappingFormat.format,
      Json.obj("C" -> 0, "d" -> 3, "4" -> 4, "F" -> 6, "G" -> 9, "Ab" -> 11, "11" -> 12),
      sampleKeyboardMapping
    )
  }

  it should "fail to deserialize an invalid KeyboardMapping" in {
    val invalidInputs = Seq(
      JsArray.empty, Json.arr(0, 1),
      Json.arr("0", JsNull, 3, JsNull, 4, 6, JsNull, 9, 11, JsNull, JsNull, 12),
      Json.obj("X#" -> 2),
      Json.obj("9" -> -2),
      Json.obj("C#" -> "5"),
      JsNumber(4),
      JsString("foo"),
      JsNull
    )

    for (invalidInput <- invalidInputs) {
      assertReadsSingleFailure(
        KeyboardMappingFormat.reads,
        invalidInput,
        KeyboardMappingFormat.InvalidKeyboardMapping
      )
    }
  }

  "writes" should "serialize a KeyboardMapper in sparse format" in {
    KeyboardMappingFormat.writes.writes(sampleKeyboardMapping) shouldEqual sampleDenseJsonKeyboardMapping
  }
}
