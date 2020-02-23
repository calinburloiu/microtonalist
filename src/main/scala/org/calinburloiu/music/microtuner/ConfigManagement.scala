package org.calinburloiu.music.microtuner

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, Config => HoconConfig}
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

final class MainConfigManager private[microtuner] (configFile: Option[Path], fallbackMainHoconConfig: HoconConfig)
    extends StrictLogging {
  import MainConfigManager._

  private[this] var _mainHoconConfig: HoconConfig = load()

  def load(): HoconConfig = configFile
    .map { path =>
      val result = ConfigFactory.parseFile(path.toFile)
      logger.info(s"Loaded config file from '$path''")
      result
    }
    .getOrElse {
      logger.warn("Config file not loaded from file; using fallback config")
      fallbackMainHoconConfig
    }

  def save(): Unit = configFile.foreach { path =>
    Try(Files.newBufferedWriter(path, StandardCharsets.UTF_8)).fold(
      exception => logger.error(s"Failed to save config file to '$path'", exception),
      writer => {
        writer.write(render())
        logger.info(s"Saved config file to '$path")
      }
    )
  }

  def render(): String = _mainHoconConfig.root().render(configRenderOptions)

  def hoconConfig(configPath: String): HoconConfig = _mainHoconConfig.getConfig(configPath)

  def notifyConfigChanged(configPath: String, hoconConfig: HoconConfig): Unit = ???

  private[this] def mainHoconConfig: HoconConfig = _mainHoconConfig
  private[this] def mainHoconConfig_=(newMainHoconConfig: HoconConfig): Unit = { _mainHoconConfig = newMainHoconConfig }
}

object MainConfigManager {

  val defaultConfigFile: Path = Paths.get(System.getProperty("user.home"), ".microtonalist/microtonalist.conf")

  private[MainConfigManager] val configRenderOptions: ConfigRenderOptions = ConfigRenderOptions.defaults()
    .setOriginComments(false)
    .setJson(false)

  def apply(configFile: Path): MainConfigManager =
    new MainConfigManager(Some(configFile), ConfigFactory.empty("no fallback"))

  def apply(mainHoconConfig: HoconConfig): MainConfigManager = new MainConfigManager(None, mainHoconConfig)
}


trait Configured

abstract class SubConfigManager[C <: Configured](configPath: String, protected val mainConfigManager: MainConfigManager) {

  def config: C = deserialize(hoconConfig)

  def notifyConfigChanged(configured: C): Unit = {
    val hocon = serialize(configured)
    mainConfigManager.notifyConfigChanged(configPath, hocon)
  }

  protected def hoconConfig: HoconConfig = mainConfigManager.hoconConfig(configPath)

  protected def serialize(configured: C): HoconConfig

  protected def deserialize(hoconConfig: HoconConfig): C
}
