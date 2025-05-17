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

package org.calinburloiu.music.microtonalist.config

import com.typesafe.config.Config as HoconConfig
import net.ceedubs.ficus.Ficus.*
import net.ceedubs.ficus.readers.ValueReader
import org.calinburloiu.music.microtonalist.common.{PlatformUtils, parseUrlOrPath}

import java.net.URI
import java.nio.file.Paths

case class CoreConfig(libraryBaseUrl: URI = CoreConfig.defaultLibraryBaseUrl,
                      metaConfig: MetaConfig = MetaConfig()) extends Configured

object CoreConfig {
  val defaultLibraryBaseUrl: URI = if (PlatformUtils.isMac) {
    val homePath = Paths.get(System.getProperty("user.home"))
    homePath.resolve("Music/microtonalist/lib/").toUri
  } else {
    throw new RuntimeException("Only Mac platform is currently supported")
  }
}

case class MetaConfig(saveIntervalMillis: Int = 5000,
                      saveOnExit: Boolean = true)

object MetaConfig {
  val default: MetaConfig = MetaConfig()
}

class CoreConfigManager(mainConfigManager: MainConfigManager)
  extends SubConfigManager[CoreConfig](CoreConfigManager.configRootPath, mainConfigManager) {

  import ConfigSerDe.*
  import CoreConfigManager.*

  override protected def serialize(config: CoreConfig): HoconConfig = {
    val hoconConfig = this.hoconConfig
    val metaConfigMap = Map(
      "saveIntervalMillis" -> config.metaConfig.saveIntervalMillis,
      "saveOnExit" -> config.metaConfig.saveOnExit
    )

    hoconConfig
      .withAnyRefValue("libraryBaseUrl", config.libraryBaseUrl.toString)
      .withAnyRefValue("metaConfig", metaConfigMap)
  }

  override protected def deserialize(hoconConfig: HoconConfig): CoreConfig = CoreConfig(
    libraryBaseUrl = hoconConfig.getAs[String]("libraryBaseUrl")
      .map(uri => parseUrlOrPath(uri).getOrElse(throw new ConfigPropertyException(
        s"$configRootPath.libraryBaseUrl", "must be a valid URL or a local path")))
      .getOrElse(CoreConfig.defaultLibraryBaseUrl),
    metaConfig = hoconConfig.getAs[MetaConfig]("metaConfig").getOrElse(MetaConfig())
  )
}

object CoreConfigManager {
  val configRootPath: String = "core"

  implicit private val metaConfigValueReader: ValueReader[MetaConfig] = ValueReader.relative { hc =>
    MetaConfig(
      saveIntervalMillis = hc.getAs[Int]("saveIntervalMillis").getOrElse(MetaConfig.default.saveIntervalMillis),
      saveOnExit = hc.getAs[Boolean]("saveOnExit").getOrElse(MetaConfig.default.saveOnExit)
    )
  }
}
