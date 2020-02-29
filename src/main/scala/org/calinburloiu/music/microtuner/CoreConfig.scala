package org.calinburloiu.music.microtuner

import java.nio.file.{Path, Paths}

import com.typesafe.config.{Config => HoconConfig}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

case class CoreConfig(
  // TODO #1 Needs platform dependent default
  scaleLibraryPath: Path,
  metaConfig: MetaConfig = MetaConfig()
) extends Configured

case class MetaConfig(
  saveIntervalMillis: Int = 5000,
  saveOnExit: Boolean = true
)

object MetaConfig {
  val default: MetaConfig = MetaConfig()
}

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
