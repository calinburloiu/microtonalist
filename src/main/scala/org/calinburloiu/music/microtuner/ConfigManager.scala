package org.calinburloiu.music.microtuner

import java.nio.file.{Path, Paths}

import com.typesafe.config.{Config, ConfigFactory}

class ConfigManager(configFile: Path = ConfigManager.defaultConfigFile) {

  val hocon: Config = ConfigFactory.parseFile(configFile.toFile)

  def getConfig(configPath: String): Config = hocon.getConfig(configPath)
}

object ConfigManager {

  val defaultConfigFile: Path = Paths.get(System.getProperty( "user.home" ), ".microtonalist/microtonalist.conf")
}
