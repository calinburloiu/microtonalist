package org.calinburloiu.music.microtuner

import com.typesafe.config.{Config, ConfigFactory}
import javax.sound.midi.MidiDevice
import net.ceedubs.ficus.Ficus._
import org.scalatest.FunSuite

class MidiIOConfigTest extends FunSuite {

  val hocon: Config = ConfigFactory.parseString(
    """
      |output {
      |  midi {
      |    devices = [
      |      {
      |        name = "FP-90"
      |        vendor = "Roland"
      |        version = "1.0"
      |      },
      |      {
      |        vendor = "Yamaha"
      |        name = "P-125"
      |        version = "9.8.7"
      |      }
      |    ]
      |  }
      |}
      |""".stripMargin)

  test("read HOCON") {
    // Given
    val expected = Seq(
      MidiDeviceId("FP-90", "Roland", "1.0"),
      MidiDeviceId("P-125", "Yamaha", "9.8.7")
    )
    // When
    val midiIOConfig = new MidiIOConfig(hocon.as[Config]("output.midi"))
    // Then
    assert(midiIOConfig.devices === expected)
  }

  test("convert from MidiDevice.Info to MidiDeviceId") {
    // Given
    //   Extending MidiDevice.Info because its constructor is protected and we need to expose a public constructor
    class MyMidiDeviceInfo(name: String, vendor: String, description: String, version: String)
      extends MidiDevice.Info(name, vendor, description, version)
    val midiDeviceInfo = new MyMidiDeviceInfo("Seaboard", "ROLI", "Innovative piano", "3.14");
    // When
    val midiDeviceId = MidiDeviceId(midiDeviceInfo)
    // Then
    assert(midiDeviceId === MidiDeviceId("Seaboard", "ROLI", "3.14"))
  }
}
