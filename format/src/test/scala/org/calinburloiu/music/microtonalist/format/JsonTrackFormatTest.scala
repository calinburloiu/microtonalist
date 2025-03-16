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

import org.calinburloiu.music.microtonalist.tuner.TrackInitSpec
import play.api.libs.json.Json

class JsonTrackInitFormatTest extends JsonFormatTestUtils {
  private val channel = 0
  private val initJson = Json.obj(
    "programChange" -> 128,
    "cc" -> Json.obj(
      "1" -> 10,
      "2" -> 20,
    ),
  )
  private val initSpec = TrackInitSpec(
    programChange = Some(128),
    cc = Map(1 -> 10, 2 -> 20),
  )

  "initFormat" should "deserialize an init spec" in {
    assertReads(JsonTrackInitFormat.initFormat, initJson, initSpec)
  }

  it should "deserialize a default init spec" in {
    assertReads(JsonTrackInitFormat.initFormat, Json.obj(), TrackInitSpec())
  }

  it should "fail to deserialize an init spec with invalid CC keys" in {
    def createInvalidInput(cc: Int) = Json.obj(
      "cc" -> Json.obj(
        s"$cc" -> 10,
      )
    )

    for (invalidCc <- Seq(-1, 128)) {
      val invalidInput = createInvalidInput(invalidCc)
      assertReadsSingleFailure(JsonTrackInitFormat.initFormat, invalidInput, JsonError_Uint7)
    }
  }

  it should "fail to deserialize an init spec with invalid CC values" in {
    def createInvalidInput(ccValue: Int) = Json.obj(
      "cc" -> Json.obj(
        "4" -> ccValue,
      )
    )

    for (invalidCcValue <- Seq(-1, 128)) {
      val invalidInput = createInvalidInput(invalidCcValue)
      assertReadsSingleFailure(JsonTrackInitFormat.initFormat, invalidInput, JsonError_Uint7)
    }
  }

  it should "fail to deserialize an init spec with invalid program change" in {
    def createInvalidInput(pc: Int) = Json.obj(
      "programChange" -> pc
    )

    for (invalidPc <- Seq(0, 129)) {
      val invalidInput = createInvalidInput(invalidPc)
      assertReadsSingleFailure(JsonTrackInitFormat.initFormat, invalidInput, JsonError_Uint7Positive)
    }
  }

  it should "serialize an init spec" in {
    JsonTrackInitFormat.initFormat.writes(initSpec) shouldEqual initJson
  }
}

class JsonTrackFormatTest extends JsonFormatTestUtils


