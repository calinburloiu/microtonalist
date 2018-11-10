package org.calinburloiu.music.microtuner

import java.io.FileInputStream
import java.nio.file.Paths

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
      System.err.println("Usage: scalist <input_scalist>")
      System.exit(1)
    }
    val inputFileName = args(0)
    val scaleLibraryPath = Paths.get(System.getenv("SCALE_LIBRARY_PATH"))
    val scaleListReader = new JsonScaleListReader(new LocalScaleLibrary(ScaleReaderRegistry, scaleLibraryPath),
      new TuningMapperRegistry, new TuningListReducerRegistry)
    val scaleList = scaleListReader.read(new FileInputStream(inputFileName))
    val tuningList = TuningList.fromScaleList(scaleList)

    val midiDeviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo
    val (midiInput, transmitter) = getMidiInput(midiDeviceInfoArray)
    val (midiOutput, receiver) = getMidiOutput(midiDeviceInfoArray)
    val midiOutputInfo = midiOutput.getDeviceInfo
    logger.info(s"Using MIDI output device ${midiOutputInfo.getName}, ${midiOutputInfo.getDescription}, " +
      s"from ${midiOutputInfo.getVendor}, version ${midiOutputInfo.getVersion}")

    midiOutput.open()
    val tuningMidiMessageGenerator: TuningMidiMessageGenerator = NonRealTime1BOctaveTuningMidiMessageGenerator
    val tuner: Tuner = new MidiTuner(receiver, tuningMidiMessageGenerator) with LoggerTuner

    val tuningSwitch = new TuningSwitch(tuner, tuningList)

    midiInput.open()
    val pedalTuningSwitchReceiver = new PedalTuningSwitchReceiver(tuningSwitch, receiver)
    transmitter.setReceiver(pedalTuningSwitchReceiver)

    logger.info("Initializing the main frame...")
    val tuningListFrame = new TuningListFrame(tuningSwitch)
    tuningListFrame.setVisible(true)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        // TODO Check thread safety!
        logger.info("Switching back to equal temperament...")
        tuner.tune(Tuning.equalTemperament)
        logger.info("Closing MIDI device...")
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
