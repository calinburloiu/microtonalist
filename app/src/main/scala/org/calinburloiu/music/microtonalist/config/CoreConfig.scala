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

import com.typesafe.config.{Config => HoconConfig}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.calinburloiu.music.microtonalist.PlatformUtils

import java.nio.file.{FileSystem, FileSystems, Path, Paths}

case class CoreConfig(libraryPath: Path = CoreConfig.defaultLibraryPath,
                      metaConfig: MetaConfig = MetaConfig()) extends Configured

object CoreConfig {
  val defaultLibraryPath: Path = if (PlatformUtils.isMac) {
    val homePath = Paths.get(System.getProperty("user.home"))
    homePath.resolve("Music/microtonalist/lib/")
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

  import ConfigSerDe._
  import CoreConfigManager._

  override protected def serialize(config: CoreConfig): HoconConfig = {
    val hoconConfig = this.hoconConfig
    val metaConfigMap = Map(
      "saveIntervalMillis" -> config.metaConfig.saveIntervalMillis,
      "saveOnExit" -> config.metaConfig.saveOnExit
    )

    hoconConfig
      .withAnyRefValue("libraryPath", config.libraryPath.toString)
      .withAnyRefValue("metaConfig", metaConfigMap)
  }

  override protected def deserialize(hoconConfig: HoconConfig): CoreConfig = CoreConfig(
    libraryPath = hoconConfig.getAs[String]("libraryPath").map(Paths.get(_))
      .getOrElse(CoreConfig.defaultLibraryPath),
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
