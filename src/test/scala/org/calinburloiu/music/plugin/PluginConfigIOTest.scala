package org.calinburloiu.music.plugin

import org.calinburloiu.music.tuning.{AutoTuningMapperConfig, PitchClassConfig}
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json

class PluginConfigIOTest extends FlatSpec with Matchers {

  "fromPlayJsValue" should "successfully create a plugin config by parsing a Play JsValue" in {
    val json = Json.obj(
      "mapQuarterTonesLow" -> true,
      "halfTolerance" -> PitchClassConfig.DEFAULT_HALF_TOLERANCE
    )
    val configClass = Some(classOf[AutoTuningMapperConfig])
    val tuningMapperConfig = PluginConfigIO.fromPlayJsValue(json, configClass)
    val expectedConfig = AutoTuningMapperConfig(
      mapQuarterTonesLow = true, halfTolerance = PitchClassConfig.DEFAULT_HALF_TOLERANCE)

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
