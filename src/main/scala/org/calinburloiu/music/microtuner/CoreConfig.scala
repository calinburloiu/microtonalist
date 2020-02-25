package org.calinburloiu.music.microtuner

import java.nio.file.{Path, Paths}

import com.typesafe.config.{Config => HoconConfig}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

case class CoreConfig(
  scaleLibraryPath: Path,
  metaConfig: MetaConfig
) extends Configured

case class MetaConfig(
  saveIntervalMillis: Int,
  saveOnExit: Boolean
)

class CoreConfigManager(mainConfigManager: MainConfigManager)
    extends SubConfigManager[CoreConfig](CoreConfigManager.configRootPath, mainConfigManager) {
  import CoreConfigManager._
  import ConfigSerDe._

  override protected def serialize(config: CoreConfig): HoconConfig = {
    val hoconConfig = this.hoconConfig
    val metaConfigMap = Map(
      "saveIntervalMillis" -> config.metaConfig.saveIntervalMillis,
      "saveOnExit" -> config.metaConfig.saveOnExit
    )

    hoconConfig
      .withAnyRefValue("scaleLibraryPath", config.scaleLibraryPath.toString)
      .withAnyRefValue("metaConfig", metaConfigMap)
  }

  override protected def deserialize(hoconConfig: HoconConfig): CoreConfig = CoreConfig(
    scaleLibraryPath = Paths.get(hoconConfig.as[String]("scaleLibraryPath")),
    metaConfig = hoconConfig.as[MetaConfig]("metaConfig")
  )
}

object CoreConfigManager {
  val configRootPath: String = "core"

  implicit private val metaConfigValueReader: ValueReader[MetaConfig] = ValueReader.relative { hc =>
    MetaConfig(
      saveIntervalMillis = hc.as[Int]("saveIntervalMillis"),
      saveOnExit = hc.as[Boolean]("saveOnExit")
    )
  }
}
