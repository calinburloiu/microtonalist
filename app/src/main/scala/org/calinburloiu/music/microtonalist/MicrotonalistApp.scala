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
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.parseUriOrPath
import org.calinburloiu.music.microtonalist.composition.{OctaveTuning, TuningList}
import org.calinburloiu.music.microtonalist.config._
import org.calinburloiu.music.microtonalist.format.FormatModule
import org.calinburloiu.music.microtonalist.tuner._
import org.calinburloiu.music.microtonalist.ui.TuningListFrame
import org.calinburloiu.music.scmidi.MidiManager

import java.net.URI
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

  case object AppUsageException extends AppException("Usage: microtonalist <input-composition-uri> [config-file]", 1)

  case object NoDeviceAvailableException extends AppException("None of the configured devices is available", 2)

  case class AppConfigException(message: String) extends AppException(message, 3)

  def main(args: Array[String]): Unit = Try {
    logger.info(s"Welcome to Microtonalist ${BuildInfo.version}!")

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

  private def parseUriArg(uriString: String): URI = {
    parseUriOrPath(uriString).getOrElse(throw AppUsageException)
  }

  private def parsePathArg(fileName: String): Path = Try(Paths.get(fileName)).recover {
    case _: InvalidPathException => throw AppUsageException
  }.get

  def run(inputUri: URI, maybeConfigPath: Option[Path] = None): Unit = {
    val configPath = maybeConfigPath.getOrElse(MainConfigManager.defaultConfigFile)
    val eventBus: EventBus = new EventBus
    val businessync = new Businessync(eventBus)
    val mainConfigManager = MainConfigManager(configPath)

    // # MIDI
    logger.info("Initializing MIDI...")
    val midiManager = new MidiManager(businessync)

    val midiInputConfigManager = new MidiInputConfigManager(mainConfigManager)
    val midiInputConfig = midiInputConfigManager.config
    val maybeTransmitter = if (midiInputConfig.enabled && midiInputConfig.triggers.cc.enabled) {
      val maybeDeviceHandle = midiManager.openFirstAvailableInput(midiInputConfig.devices)
      maybeDeviceHandle.map(_.transmitter)
    } else {
      None
    }

    val midiOutputConfigManager = new MidiOutputConfigManager(mainConfigManager)
    val midiOutputConfig = midiOutputConfigManager.config
    val outputDeviceHandle = midiManager.openFirstAvailableOutput(midiOutputConfig.devices)
      .getOrElse {
        throw NoDeviceAvailableException
      }
    val receiver = outputDeviceHandle.receiver

    // # I/O
    val formatModule = new FormatModule(mainConfigManager.coreConfig.libraryUri)

    // # Microtuner
    val tunerModule = new TunerModule(businessync)
    val tuningService = tunerModule.tuningService

    val composition = formatModule.defaultCompositionRepo.read(inputUri)
    val tuningList = TuningList.fromComposition(composition)
    val tuner = createTuner(midiInputConfig, midiOutputConfig)

    val triggersConfig = midiInputConfig.triggers.cc
    val tuningChangeTriggers = TuningChangeTriggers(
      Some(triggersConfig.prevTuningCc),
      Some(triggersConfig.nextTuningCc)
    )
    val tuningChanger = PedalTuningChanger(
      tuningChangeTriggers, triggersConfig.ccThreshold, !triggersConfig.isFilteringThru)
    val tuningChangeProcessor = new TuningChangeProcessor(tuningService, tuningChanger)

    val track = new Track(Some(tuningChangeProcessor), tuner, receiver, midiOutputConfig.ccParams)
    val trackManager = new TrackManager(Seq(track))
    businessync.register(trackManager)

    maybeTransmitter.foreach { transmitter =>
      transmitter.setReceiver(track)
      logger.info("Using CC tuning switcher")
    }
    tunerModule.tuningSession.tunings = tuningList.tunings

    // # GUI
    logger.info("Initializing GUI...")
    val tuningListFrame = new TuningListFrame(tuningService)
    businessync.register(tuningListFrame)
    tuningListFrame.setVisible(true)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        logger.info("Preparing to exit...")
        logger.info("Switching back to 12-EDO...")
        try {
          tuner.tune(OctaveTuning.Edo12)
        } catch {
          case e: TunerException => logger.error(e.getMessage)
        }
        Thread.sleep(1000)

        logger.info("Closing MIDI connections...")
        midiManager.close()

        logger.info("Bye bye!")
      }
    })
  }

  private def createTuner(midiInputConfig: MidiInputConfig, midiOutputConfig: MidiOutputConfig): Tuner = {
    logger.info(s"Using ${midiOutputConfig.tunerType} tuner...")
    midiOutputConfig.tunerType match {
      case TunerType.MtsOctave1ByteNonRealTime => MtsOctave1ByteNonRealTimeTuner(midiInputConfig.thru)
      case TunerType.MonophonicPitchBend => MonophonicPitchBendTuner(
        Track.DefaultOutputChannel, midiOutputConfig.pitchBendSensitivity)
      case _ => throw AppConfigException(s"Invalid tunerType ${midiOutputConfig.tunerType} in config!")
    }
  }
}
