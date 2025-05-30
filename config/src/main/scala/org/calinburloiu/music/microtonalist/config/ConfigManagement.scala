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

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, Config as HoconConfig}
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.common.PlatformUtils
import org.calinburloiu.music.microtonalist.common.concurrency.Locking

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.util.Try

final class MainConfigManager private[microtonalist](configFile: Option[Path], fallbackMainHoconConfig: HoconConfig)
  extends AutoCloseable, Locking, StrictLogging {

  import MainConfigManager.*

  private implicit val lock: ReadWriteLock = new ReentrantReadWriteLock

  val coreConfigManager: CoreConfigManager = new CoreConfigManager(this)

  def coreConfig: CoreConfig = coreConfigManager.config

  def metaConfig: MetaConfig = coreConfig.metaConfig

  private var _mainHoconConfig: HoconConfig = load()
  private var _dirty: Boolean = false

  private val scheduledExecutorService: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
  private val scheduledTask: Runnable = () => save()
  if (metaConfig.saveIntervalMillis > 0) {
    scheduledExecutorService.scheduleAtFixedRate(scheduledTask,
      metaConfig.saveIntervalMillis, metaConfig.saveIntervalMillis, TimeUnit.MILLISECONDS)
  }

  def load(): HoconConfig = configFile
    .map { path =>
      val loadedHoconConfig = ConfigFactory.parseFile(path.toFile)
      logger.info(s"Loaded config file from \"$path\"")
      loadedHoconConfig.resolve()
    }
    .getOrElse {
      logger.warn("Config file not loaded from file; using fallback config")
      fallbackMainHoconConfig
    }

  def save(): Unit = {
    if (_dirty) {
      configFile.foreach { path =>
        Try(Files.newBufferedWriter(path, StandardCharsets.UTF_8)).fold(
          exception => logger.error(s"Failed to save config file to \"$path\"", exception),
          writer => {
            writer.write(render())
            _dirty = false
            logger.info(s"Saved config file to \"$path\"")
          }
        )
      }
    } else {
      logger.trace("Nothing to save")
    }
  }

  def isDirty: Boolean = _dirty

  def render(): String = mainHoconConfig.root().render(configRenderOptions)

  def hoconConfig(configPath: String): HoconConfig = mainHoconConfig.getConfig(configPath)

  def notifyConfigChanged(configPath: String, hoconConfig: HoconConfig): Unit = {
    mainHoconConfig = mainHoconConfig.withValue(configPath, hoconConfig.root)
  }

  override def close(): Unit = {
    if (metaConfig.saveOnExit) {
      save()
    }
    scheduledExecutorService.shutdown()
  }

  private def mainHoconConfig: HoconConfig = withReadLock {
    _mainHoconConfig
  }

  private def mainHoconConfig_=(newMainHoconConfig: HoconConfig): Unit = withWriteLock {
    _mainHoconConfig = newMainHoconConfig
    _dirty = true
  }
}

object MainConfigManager {

  private[MainConfigManager] val configRenderOptions: ConfigRenderOptions = ConfigRenderOptions.defaults()
    .setOriginComments(false)
    .setJson(false)

  def defaultConfigFile: Path = if (PlatformUtils.isMac)
    Paths.get(System.getProperty("user.home"), ".microtonalist/microtonalist.conf")
  else
    throw new RuntimeException("Only Mac platform is currently supported")

  def apply(configFile: Path): MainConfigManager =
    new MainConfigManager(Some(configFile), ConfigFactory.empty("no fallback"))

  def apply(mainHoconConfig: HoconConfig): MainConfigManager = new MainConfigManager(None, mainHoconConfig)
}


trait Configured

abstract class SubConfigManager[C <: Configured](configPath: String,
                                                 protected val mainConfigManager: MainConfigManager) {

  def config: C = deserialize(hoconConfig)

  def notifyConfigChanged(configured: C): Unit = {
    val hocon = serialize(configured)
    mainConfigManager.notifyConfigChanged(configPath, hocon)
  }

  protected def hoconConfig: HoconConfig = mainConfigManager.hoconConfig(configPath)

  protected def serialize(configured: C): HoconConfig

  protected def deserialize(hoconConfig: HoconConfig): C
}
