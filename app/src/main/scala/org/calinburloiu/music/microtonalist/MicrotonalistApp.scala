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

package org.calinburloiu.music.microtonalist

import com.google.common.eventbus.EventBus
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.parseUrlOrPath
import org.calinburloiu.music.microtonalist.composition.TuningList
import org.calinburloiu.music.microtonalist.config.*
import org.calinburloiu.music.microtonalist.format.FormatModule
import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.microtonalist.ui.TuningListFrame

import java.net.URI
import java.nio.file.{InvalidPathException, Path, Paths}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

object MicrotonalistApp extends StrictLogging {

  sealed abstract class AppException(message: String, val statusCode: Int, cause: Throwable = null)
    extends RuntimeException(message, cause) {
    def exitWithMessage(): Unit = {
      System.err.println(message)
      System.exit(statusCode)
    }
  }

  case object AppUsageException extends AppException("Usage: microtonalist <input-composition-url> [config-file]", 1)

  case object NoDeviceAvailableException extends AppException("None of the configured devices is available", 2)

  case class AppConfigException(message: String) extends AppException(message, 3)

  def main(args: Array[String]): Unit = Try {
    logger.info(s"Welcome to Microtonalist ${BuildInfo.version}!")

    args match {
      case Array(inputUrlString: String) =>
        run(parseUrlArg(inputUrlString))
      case Array(inputUrlString: String, configFileName: String) =>
        run(parseUrlArg(inputUrlString), Some(parsePathArg(configFileName)))
      case _ => throw AppUsageException
    }
  }.recover {
    case appException: AppException => appException.exitWithMessage()
    case exception: Exception =>
      logger.error("Unexpected error", exception)
      System.exit(1000)
  }

  private def parseUrlArg(urlString: String): URI = {
    parseUrlOrPath(urlString).getOrElse(throw AppUsageException)
  }

  private def parsePathArg(fileName: String): Path = Try(Paths.get(fileName)).recover {
    case _: InvalidPathException => throw AppUsageException
  }.get

  def run(inputUrl: URI, maybeConfigPath: Option[Path] = None): Unit = {
    val configPath = maybeConfigPath.getOrElse(MainConfigManager.defaultConfigFile)
    val eventBus: EventBus = new EventBus
    val businessync = new Businessync(eventBus)
    val mainConfigManager = MainConfigManager(configPath)

    val formatModule = new FormatModule(businessync, mainConfigManager.coreConfig.libraryBaseUrl)

    val composition = formatModule.defaultCompositionRepo.read(inputUrl)
    val tuningList = TuningList.fromComposition(composition)

    val tunerModule = new TunerModule(businessync, formatModule.defaultTrackRepo)
    val trackService = tunerModule.trackService
    composition.tracksUrl.foreach { uri =>
      // TODO #87 This will be moved as part of a composition opening workflow
      Await.result(trackService.open(uri), 10 seconds)
    }
    tunerModule.tuningSession.tunings = tuningList.tunings

    logger.info("Initializing GUI...")
    val tuningListFrame = new TuningListFrame(tunerModule.tuningService)
    businessync.register(tuningListFrame)
    tuningListFrame.setVisible(true)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        logger.info("Preparing to exit...")
        tunerModule.close()
        Thread.sleep(1_000)

        logger.info("Bye bye!")
      }
    })
  }
}
