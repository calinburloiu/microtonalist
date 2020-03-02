package org.calinburloiu.music.microtuner

import java.io.FileInputStream
import java.nio.file.Paths

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.io.{LocalScaleLibrary, ScaleReaderRegistry}
import org.calinburloiu.music.microtuner.io.JsonScaleListReader
import org.calinburloiu.music.microtuner.midi._
import org.calinburloiu.music.tuning.{Tuning, TuningList, TuningListReducerRegistry, TuningMapperRegistry}

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

  def main(args: Array[String]): Unit = Try {
    args match {
      case Array(inputFileName: String) => run(inputFileName)
      case Array(inputFileName: String, configFileName: String) => run(inputFileName, Some(configFileName))
      case _ => Some(AppUsageException)
    }
  }.recover {
    case appException: AppException => appException.exitWithMessage()
  }

  def run(inputFileName: String, configFileName: Option[String] = None): Unit = {
    val configPath = configFileName.map(Paths.get(_)).getOrElse(MainConfigManager.defaultConfigFile)

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
    val maybeOutputDeviceId = midiManager.openFirstAvailableOutput(midiOutputConfig.devices)
    val receiver = maybeOutputDeviceId.map(midiManager.outputReceiver).getOrElse {
      throw NoDeviceAvailableException
    }

    // # I/O
    val scaleLibraryPath = mainConfigManager.coreConfig.scaleLibraryPath
    val scaleListReader = new JsonScaleListReader(new LocalScaleLibrary(ScaleReaderRegistry, scaleLibraryPath),
      new TuningMapperRegistry, new TuningListReducerRegistry)

    // # Microtuner
    val scaleList = scaleListReader.read(new FileInputStream(inputFileName))
    val tuningList = TuningList.fromScaleList(scaleList)
    val tuner: Tuner = new MidiTuner(receiver, MidiTuningFormat.NonRealTime1BOctave) with LoggerTuner
    val tuningSwitch = new TuningSwitch(tuner, tuningList)

    // # Triggers
    maybeTransmitter.foreach { transmitter =>
      val pedalTuningSwitchReceiver = new PedalTuningSwitchReceiver(tuningSwitch, receiver, midiInputConfig.triggers.cc)
      transmitter.setReceiver(pedalTuningSwitchReceiver)
      logger.info("Using pedal tuning switcher")
    }

    // # GUI
    logger.info("Initializing the main frame...")
    val tuningListFrame = new TuningListFrame(tuningSwitch)
    tuningListFrame.setVisible(true)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        // TODO Check thread safety!
        logger.info("Switching back to equal temperament before exit...")
        tuner.tune(Tuning.equalTemperament)

        midiManager.close()
      }
    })
  }
}
