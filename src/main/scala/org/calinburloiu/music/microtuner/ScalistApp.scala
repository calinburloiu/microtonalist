package org.calinburloiu.music.microtuner

import java.io.{File, FileInputStream}
import java.nio.file.{Path, Paths}

import com.typesafe.config.{Config, ConfigFactory}
import javax.sound.midi.{MidiDevice, MidiSystem, Receiver, Transmitter}
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.io.{LocalScaleLibrary, ScaleReaderRegistry}
import org.calinburloiu.music.microtuner.io.JsonScaleListReader
import org.calinburloiu.music.tuning.{Tuning, TuningList, TuningListReducerRegistry, TuningMapperRegistry}
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider

import scala.util.Try

object ScalistApp extends StrictLogging {

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println("Usage: scalist <input-scalist> [config-file]")
      System.exit(1)
    }
    val inputFileName = args(0)
    val configFileName = if (args.length >= 2) Some(args(1)) else None
    val configFile = configFileName.map(Paths.get(_)).getOrElse(ConfigManager.defaultConfigFile)

    val configManager = new ConfigManager(configFile)

    val midiInputConfig = new MidiInputConfig(configManager.getConfig(MidiInputConfig.configRootPath))
    val midiOutputConfig = new MidiOutputConfig(configManager.getConfig(MidiOutputConfig.configRootPath))
    val midiManager = new MidiManager

    val scaleLibraryPath = Paths.get(System.getenv("SCALE_LIBRARY_PATH"))
    val scaleListReader = new JsonScaleListReader(new LocalScaleLibrary(ScaleReaderRegistry, scaleLibraryPath),
      new TuningMapperRegistry, new TuningListReducerRegistry)
    val scaleList = scaleListReader.read(new FileInputStream(inputFileName))
    val tuningList = TuningList.fromScaleList(scaleList)

    val midiDeviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo
//    val (midiInput, transmitter) = getMidiInput(midiDeviceInfoArray)
    val (midiOutput, receiver) = getMidiOutput(midiDeviceInfoArray)
    val midiOutputInfo = midiOutput.getDeviceInfo
    logger.info(s"Using MIDI output device ${midiOutputInfo.getName}, ${midiOutputInfo.getDescription}, " +
      s"from ${midiOutputInfo.getVendor}, version ${midiOutputInfo.getVersion}")

    midiOutput.open()
    val tuningMidiMessageGenerator: TuningMidiMessageGenerator = NonRealTime1BOctaveTuningMidiMessageGenerator
    val tuner: Tuner = new MidiTuner(receiver, tuningMidiMessageGenerator) with LoggerTuner

    val tuningSwitch = new TuningSwitch(tuner, tuningList)

//    midiInput.open()
//    val pedalTuningSwitchReceiver = new PedalTuningSwitchReceiver(tuningSwitch, receiver)
//    transmitter.setReceiver(pedalTuningSwitchReceiver)

    logger.info("Initializing the main frame...")
    val tuningListFrame = new TuningListFrame(tuningSwitch)
    tuningListFrame.setVisible(true)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        // TODO Check thread safety!
        logger.info("Switching back to equal temperament...")
        tuner.tune(Tuning.equalTemperament)
        logger.info("Closing MIDI devices...")
//        midiInput.close()
        midiOutput.close()
      }
    })
  }

  def getMidiInput(midiDeviceInfoArray: Array[MidiDevice.Info]): (MidiDevice, Transmitter) = {
    val results = for {
      deviceInfo <- midiDeviceInfoArray.toStream if deviceInfo.getVendor.contains("Roland")
      device = MidiSystem.getMidiDevice(deviceInfo)
      maybeTransmitter = Try(device.getTransmitter).toOption
      transmitter <- maybeTransmitter
    } yield (device, transmitter)

    results.headOption.getOrElse(throw new DeviceNotFoundException("Roland input device not found"))
  }

  def getMidiOutput(midiDeviceInfoArray: Array[MidiDevice.Info]): (MidiDevice, Receiver) = {
    val results = for {
      deviceInfo <- midiDeviceInfoArray.toStream if deviceInfo.getVendor.contains("Roland")
      device = MidiSystem.getMidiDevice(deviceInfo)
      // TODO verify it's a port: `if ( !(device instanceof Sequencer) && !(device instanceof Synthesizer))`
      maybeReceiver = Try(device.getReceiver).toOption
      receiver <- maybeReceiver
    } yield (device, receiver)

    results.headOption.getOrElse {
      val synth = MidiSystem.getSynthesizer
      (synth, synth.getReceiver)
    }
  }
}

class DeviceNotFoundException(message: String) extends RuntimeException(message)
