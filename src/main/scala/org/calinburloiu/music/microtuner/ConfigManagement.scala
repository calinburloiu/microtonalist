package org.calinburloiu.music.microtuner

import java.nio.file.{Path, Paths}

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, Config => HoconConfig}

class MainConfigManager(private[this] var _mainHoconConfig: HoconConfig) {

  def this(configFile: Path = MainConfigManager.defaultConfigFile) = {
    this(ConfigFactory.parseFile(configFile.toFile))
  }

  def hoconConfig(configPath: String): HoconConfig = _mainHoconConfig.getConfig(configPath)

  def notifyConfigChanged(configPath: String, hoconConfig: HoconConfig): Unit = ???

  // TODO #1 DEBUG
  def print(): Unit = {
    val configRenderOptions: ConfigRenderOptions = ConfigRenderOptions.defaults()
      .setOriginComments(false)
      .setJson(false)
    println(_mainHoconConfig.root().render(configRenderOptions))
  }

  private[this] def mainHoconConfig: HoconConfig = _mainHoconConfig
  private[this] def mainHoconConfig_=(newMainHoconConfig: HoconConfig): Unit = { _mainHoconConfig = newMainHoconConfig }
}

object MainConfigManager {

  val defaultConfigFile: Path = Paths.get(System.getProperty("user.home"), ".microtonalist/microtonalist.conf")
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
