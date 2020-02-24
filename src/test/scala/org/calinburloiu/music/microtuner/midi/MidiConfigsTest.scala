package org.calinburloiu.music.microtuner.midi

import javax.sound.midi.MidiDevice
import org.calinburloiu.music.microtuner.SubConfigTest
import org.scalatest.{FlatSpec, Matchers}

class MidiInputConfigTest extends SubConfigTest[MidiInputConfig, MidiInputConfigManager] {

  override lazy val subConfigManager: MidiInputConfigManager = new MidiInputConfigManager(mainConfigManager)

  override lazy val expectedSubConfigRead: MidiInputConfig = MidiInputConfig(
    enabled = true,
    devices = Seq(
      MidiDeviceId("FP-90", "Roland", "1.0")
    ),
    ccTriggers = CcTriggers(prevTuningCc = 67, nextTuningCc = 66, ccThreshold = 0, isFilteringInOutput = true)
  )

  override lazy val subConfigsToWrite: Seq[MidiInputConfig] = Seq(
    expectedSubConfigRead.copy(enabled = false),
    expectedSubConfigRead.copy(
      devices = Seq(
        MidiDeviceId("P-125", "Yamaha", "9.8.7"),
        MidiDeviceId("blah", "bleh", "1999")
      ),
      ccTriggers = CcTriggers(prevTuningCc = 1, nextTuningCc = 2, ccThreshold = 10, isFilteringInOutput = false)
    )
  )
}

class MidiOutputConfigTest extends SubConfigTest[MidiOutputConfig, MidiOutputConfigManager] {

  override lazy val subConfigManager: MidiOutputConfigManager = new MidiOutputConfigManager(mainConfigManager)

  override lazy val expectedSubConfigRead: MidiOutputConfig = MidiOutputConfig(
    devices = Seq(
      MidiDeviceId("FP-90", "Roland", "1.0"),
      MidiDeviceId("P-125", "Yamaha", "9.8.7")
    ),
    tuningFormat = MidiTuningFormat.NonRealTime1BOctave
  )

  override lazy val subConfigsToWrite: Seq[MidiOutputConfig] = Seq(
    expectedSubConfigRead.copy(
      devices = Seq(MidiDeviceId("blah", "bleh", "1999"))
    )
    // TODO Test changing tuningFormat after adding more formats
  )
}

class MidiConfigsTest extends FlatSpec with Matchers {

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
}
