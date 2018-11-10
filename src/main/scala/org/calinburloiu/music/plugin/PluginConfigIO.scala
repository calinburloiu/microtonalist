package org.calinburloiu.music.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import play.api.libs.json.{JsNull, JsValue}
import play.api.libs.json.jackson.PlayJsonModule

object PluginConfigIO {

  // TODO This should be already created by play-json, we just need to register the DefaultScalaModule
  val defaultObjectMapper: ObjectMapper = new ObjectMapper()
    .registerModule(PlayJsonModule)
    .registerModule(DefaultScalaModule)

  def fromPlayJsValue(
      jsValueConfig: JsValue,
      maybeConfigClass: Option[Class[_ <: PluginConfig]],
      objectMapper: ObjectMapper = defaultObjectMapper): Option[_ <: PluginConfig] = {
    jsValueConfig match {
      case JsNull => None
      case _ =>
        maybeConfigClass.map { configClass =>
          objectMapper.convertValue(jsValueConfig, configClass)
        }
    }
  }

  def fromJsonString[C <: PluginConfig](
      jsonStringConfig: String,
      maybeConfigClass: Option[Class[C]],
      objectMapper: ObjectMapper = defaultObjectMapper): Option[C] = {
    maybeConfigClass.flatMap { configClass =>
      // Wrap the result in an Option to get rid of the Java null value
      Option(objectMapper.readValue(jsonStringConfig, configClass))
    }
  }
}
