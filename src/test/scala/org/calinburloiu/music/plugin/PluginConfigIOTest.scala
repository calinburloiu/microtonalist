/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.plugin

import org.calinburloiu.music.tuning.{AutoTuningMapperConfig, PitchClassConfig}
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json

class PluginConfigIOTest extends FlatSpec with Matchers {

  "fromPlayJsValue" should "successfully create a plugin config by parsing a Play JsValue" in {
    val json = Json.obj(
      "mapQuarterTonesLow" -> true,
      "halfTolerance" -> PitchClassConfig.DefaultHalfTolerance
    )
    val configClass = Some(classOf[AutoTuningMapperConfig])
    val tuningMapperConfig = PluginConfigIO.fromPlayJsValue(json, configClass)
    val expectedConfig = AutoTuningMapperConfig(
      mapQuarterTonesLow = true, halfTolerance = PitchClassConfig.DefaultHalfTolerance)

    tuningMapperConfig should contain (expectedConfig)
  }

  // TODO Handle failures

  "fromJsonString" should "successfully create a plugin config by parsing a JSON string" in {
    val jsonString = """{"mapQuarterTonesLow":true,"halfTolerance":0.004}"""
    val configClass = Some(classOf[AutoTuningMapperConfig])
    val tuningMapperConfig = PluginConfigIO.fromJsonString(jsonString, configClass)
    val expectedConfig = AutoTuningMapperConfig(
      mapQuarterTonesLow = true, halfTolerance = 0.004)

    tuningMapperConfig should contain (expectedConfig)
  }
}
