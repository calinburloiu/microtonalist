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
