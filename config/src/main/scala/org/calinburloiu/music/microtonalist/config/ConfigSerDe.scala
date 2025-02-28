/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.config

import com.typesafe.config.{Config, ConfigValue, ConfigValueFactory}

import scala.jdk.CollectionConverters._

object ConfigSerDe {

  implicit class HoconConfigExtension(hoconConfig: Config) {

    def withAnyRefValue(path: String, value: Any): Config = value match {
      case _: Seq[?] | _: Map[?, ?] =>
        hoconConfig.withValue(path, createHoconValue(value))
      case _: Any =>
        if (hoconConfig.getAnyRef(path) != value) {
          hoconConfig.withValue(path, ConfigValueFactory.fromAnyRef(value))
        } else {
          hoconConfig
        }
    }
  }

  def createHoconValue(value: Any): ConfigValue = value match {
    case seq: Seq[?] => ConfigValueFactory.fromIterable(seq.map { (v: Any) => createHoconValue(v) }.asJava)
    case map: Map[_, _] =>
      val convertedMap = map.map { case (k: Any, v: Any) =>
        (k.toString, createHoconValue(v))
      }.asJava
      ConfigValueFactory.fromMap(convertedMap)
    case any: Any => ConfigValueFactory.fromAnyRef(any)
  }
}
