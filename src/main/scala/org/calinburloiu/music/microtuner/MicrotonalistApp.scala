/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner

import com.google.common.eventbus.EventBus
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.format.{LocalScaleLibrary, ScaleFormatRegistry}
import org.calinburloiu.music.microtuner.config.MainConfigManager
import org.calinburloiu.music.microtuner.format.JsonScaleListFormat
import org.calinburloiu.music.microtuner.midi._
import org.calinburloiu.music.tuning.{OctaveTuning, TunerType, TuningList}

import java.io.FileInputStream
import java.nio.file.Paths
import scala.util.Try

object MicrotonalistApp extends StrictLogging {
  sealed abstract class AppException(message: String, val statusCode: Int, cause: Throwable = null)
    extends RuntimeException(message, cause) {
    def exitWithMessage(): Unit = {
      System.err.println(message)
      System.exit(statusCode)
    }
  }

  case object AppUsageException extends AppException("Usage: microtonalist <input-scale-list> [config-file]", 1)

  case object NoDeviceAvailableException extends AppException("None of the configured devices is available", 2)

  case class AppConfigException(message: String) extends AppException(message, 3)

  def main(args: Array[String]): Unit = Try {
    args match {
      case Array(inputFileName: String) => run(inputFileName)
      case Array(inputFileName: String, configFileName: String) => run(inputFileName, Some(configFileName))
      case _ => throw AppUsageException
    }
  }.recover {
    case appException: AppException => appException.exitWithMessage()
    case throwable: Throwable =>
      logger.error("Unexpected error", throwable)
      System.exit(1000)
  }

  def run(inputFileName: String, configFileName: Option[String] = None): Unit = {
    val configPath = configFileName.map(Paths.get(_)).getOrElse(MainConfigManager.defaultConfigFile)
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
    val scaleLibraryPath = mainConfigManager.coreConfig.scaleLibraryPath
    val scaleListFormat = new JsonScaleListFormat(new LocalScaleLibrary(ScaleFormatRegistry, scaleLibraryPath))

    // # Microtuner
    val scaleList = scaleListFormat.read(new FileInputStream(inputFileName))
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
