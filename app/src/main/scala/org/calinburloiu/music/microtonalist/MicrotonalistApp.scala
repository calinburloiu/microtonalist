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

package org.calinburloiu.music.microtonalist

import com.google.common.eventbus.EventBus
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.config._
import org.calinburloiu.music.microtonalist.core.{OctaveTuning, TuningList}
import org.calinburloiu.music.microtonalist.format.FormatModule
import org.calinburloiu.music.microtonalist.tuner._
import org.calinburloiu.music.microtonalist.ui.TuningListFrame
import org.calinburloiu.music.scmidi.MidiManager

import java.net.{URI, URISyntaxException}
import java.nio.file.{InvalidPathException, Path, Paths}
import scala.util.Try

object MicrotonalistApp extends StrictLogging {
  sealed abstract class AppException(message: String, val statusCode: Int, cause: Throwable = null)
    extends RuntimeException(message, cause) {
    def exitWithMessage(): Unit = {
      System.err.println(message)
      System.exit(statusCode)
    }
  }

  case object AppUsageException extends AppException("Usage: microtonalist <input-scale-list-uri> [config-file]", 1)

  case object NoDeviceAvailableException extends AppException("None of the configured devices is available", 2)

  case class AppConfigException(message: String) extends AppException(message, 3)

  def main(args: Array[String]): Unit = Try {
    args match {
      case Array(inputUriString: String) =>
        run(parseUriArg(inputUriString))
      case Array(inputUriString: String, configFileName: String) =>
        run(parseUriArg(inputUriString), Some(parsePathArg(configFileName)))
      case _ => throw AppUsageException
    }
  }.recover {
    case appException: AppException => appException.exitWithMessage()
    case throwable: Throwable =>
      logger.error("Unexpected error", throwable)
      System.exit(1000)
  }

  private def parseUriArg(uriString: String): URI = Try(new URI(uriString)).recover {
    case _: URISyntaxException => throw AppUsageException
  }.get

  private def parsePathArg(fileName: String): Path = Try(Paths.get(fileName)).recover {
    case _: InvalidPathException => throw AppUsageException
  }.get

  // TODO #38 URI doesn't work well with files now
  def run(inputUri: URI, maybeConfigPath: Option[Path] = None): Unit = {
    val configPath = maybeConfigPath.getOrElse(MainConfigManager.defaultConfigFile)
    val eventBus: EventBus = new EventBus
    val mainConfigManager = MainConfigManager(configPath)

    // # MIDI
    logger.info("Initializing MIDI...")
    val midiManager = new MidiManager

    val midiInputConfigManager = new MidiInputConfigManager(mainConfigManager)
    val midiInputConfig = midiInputConfigManager.config
    val maybeTransmitter = if (midiInputConfig.enabled && midiInputConfig.triggers.cc.enabled) {
      val maybeInputDeviceId = midiManager.openFirstAvailableInput(midiInputConfig.devices)
      maybeInputDeviceId.map(midiManager.inputTransmitter)
    } else {
      None
    }

    val midiOutputConfigManager = new MidiOutputConfigManager(mainConfigManager)
    val midiOutputConfig = midiOutputConfigManager.config
    val outputDeviceId = midiManager.openFirstAvailableOutput(midiOutputConfig.devices).getOrElse {
      throw NoDeviceAvailableException
    }
    val receiver = midiManager.outputReceiver(outputDeviceId)

    // # I/O
    // TODO #38 Rename "scale library" to "microtonalist library" everywhere
    val scaleLibraryPath = mainConfigManager.coreConfig.scaleLibraryPath
    val formatModule = new FormatModule(scaleLibraryPath.toUri)

    // # Microtuner
    val scaleList = formatModule.defaultScaleListRepo.read(inputUri)
    val tuningList = TuningList.fromScaleList(scaleList)
    val tuner = createTuner(midiInputConfig, midiOutputConfig)
    val tuningSwitcher = new TuningSwitcher(Seq(tuner), tuningList, eventBus)
    val tuningSwitchProcessor = new CcTuningSwitchProcessor(tuningSwitcher, midiInputConfig.triggers.cc)
    val track = new Track(Some(tuningSwitchProcessor), tuner, receiver, midiOutputConfig.ccParams)
    maybeTransmitter.foreach { transmitter =>
      transmitter.setReceiver(track)
      logger.info("Using CC tuning switcher")
    }
    tuningSwitcher.tune()

    // # GUI
    logger.info("Initializing the main frame...")
    val tuningListFrame = new TuningListFrame(tuningSwitcher)
    eventBus.register(tuningListFrame)
    tuningListFrame.setVisible(true)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        logger.info("Switching back to 12-EDO before exit...")
        try {
          tuner.tune(OctaveTuning.Edo12)
        } catch {
          case e: TunerException => logger.error(e.getMessage)
        }
        Thread.sleep(1000)

        midiManager.close()
      }
    })
  }

  private def createTuner(midiInputConfig: MidiInputConfig, midiOutputConfig: MidiOutputConfig): TunerProcessor = {
    logger.info(s"Using ${midiOutputConfig.tunerType} tuner...")
    midiOutputConfig.tunerType match {
      case TunerType.Mts => new MtsTuner(midiOutputConfig.mtsTuningFormat, midiInputConfig.thru) with LoggerTuner
      case TunerType.MonophonicPitchBend => new MonophonicPitchBendTuner(
        Track.DefaultOutputChannel, midiOutputConfig.pitchBendSensitivity) with LoggerTuner
      case _ => throw AppConfigException(s"Invalid tunerType ${midiOutputConfig.tunerType} in config!")
    }
  }
}
