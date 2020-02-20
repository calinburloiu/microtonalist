package org.calinburloiu.music.microtuner

import com.typesafe.config.{Config, ConfigFactory}
import javax.sound.midi.MidiDevice
import org.calinburloiu.music.microtuner.midi._
import org.scalatest.{FlatSpec, Matchers}

class MidiConfigsTest extends FlatSpec with Matchers {

  val hoconConfig: Config = ConfigFactory.parseResources(getClass, "/microtonalist.conf")
  val mainConfigManager = new MainConfigManager(hoconConfig)

  "MidiInputConfigManager" should "deserialize HOCON" in {
    val expectedDevices = Seq(
      MidiDeviceId("FP-90", "Roland", "1.0")
    )
    val expectedCcTriggers = CcTriggers(
      prevTuningCc = 67, nextTuningCc = 66, ccThreshold = 0, isFilteringInOutput = true)

    val midiInputConfigManager = new MidiInputConfigManager(mainConfigManager)
    val config = midiInputConfigManager.config

    config.enabled shouldBe true
    config.devices shouldEqual expectedDevices
    config.ccTriggers shouldEqual expectedCcTriggers
  }

  "MidiOutputConfigManager" should "deserialize HOCON" in {
    val expectedDevices = Seq(
      MidiDeviceId("FP-90", "Roland", "1.0"),
      MidiDeviceId("P-125", "Yamaha", "9.8.7")
    )
    val midiOutputConfigManager = new MidiOutputConfigManager(mainConfigManager)

    midiOutputConfigManager.config.devices shouldEqual expectedDevices
    midiOutputConfigManager.config.tuningFormat shouldEqual MidiTuningFormat.NonRealTime1BOctave
  }

  "MidiDeviceId.apply" should "convert from MidiDevice.Info to MidiDeviceId" in {
    // Given
    //   Extending MidiDevice.Info because its constructor is protected and we need to expose a public constructor
    class MyMidiDeviceInfo(name: String, vendor: String, description: String, version: String)
      extends MidiDevice.Info(name, vendor, description, version)
    val midiDeviceInfo = new MyMidiDeviceInfo("Seaboard", "ROLI", "Innovative piano", "3.14");
    // When
    val midiDeviceId = MidiDeviceId(midiDeviceInfo)
    // Then
    midiDeviceId shouldEqual MidiDeviceId("Seaboard", "ROLI", "3.14")
  }

  "test serialize" should "work" in {
    val midiOutputConfigManager = new MidiOutputConfigManager(mainConfigManager)
    val config = MidiOutputConfig(
      devices = Seq(
        MidiDeviceId("FP-90", "Roland", "1.0"),
        MidiDeviceId("P-125", "Yamaha", "9.8.7")),
      tuningFormat = MidiTuningFormat.NonRealTime1BOctave
    )
    val hocon = midiOutputConfigManager.serialize(config)
    println(hocon.root().render())
  }
}
