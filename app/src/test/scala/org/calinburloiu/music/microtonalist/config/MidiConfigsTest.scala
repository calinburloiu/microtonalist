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

package org.calinburloiu.music.microtonalist.config

import org.calinburloiu.music.microtonalist.tuner.{MtsTuningFormat, TunerType}
import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.MidiDevice

class MidiInputConfigTest extends SubConfigTest[MidiInputConfig, MidiInputConfigManager] {

  override lazy val subConfigManager: MidiInputConfigManager = new MidiInputConfigManager(mainConfigManager)

  override lazy val expectedSubConfigRead: MidiInputConfig = MidiInputConfig(
    enabled = true,
    devices = Seq(
      MidiDeviceId("FP-90", "Roland")
    ),
    thru = true,
    triggers = Triggers(
      cc = CcTriggers(enabled = true, prevTuningCc = 67, nextTuningCc = 66, ccThreshold = 0, isFilteringThru = true)
    )
  )

  override lazy val subConfigsToWrite: Seq[MidiInputConfig] = Seq(
    expectedSubConfigRead.copy(enabled = false),
    expectedSubConfigRead.copy(
      enabled = false,
      devices = Seq(
        MidiDeviceId("P-125", "Yamaha"),
        MidiDeviceId("blah", "bleh")
      ),
      thru = false,
      triggers = Triggers(
        cc = CcTriggers(enabled = false, prevTuningCc = 1, nextTuningCc = 2, ccThreshold = 10, isFilteringThru = false)
      )
    )
  )
}

class MidiInputConfigDefaultsTest extends MidiInputConfigTest {

  override def configResource: String = SubConfigTest.defaultConfigResourceWithDefaults

  override lazy val expectedSubConfigRead: MidiInputConfig = MidiInputConfig(
    enabled = false,
    devices = Seq(
      MidiDeviceId("FP-90", "Roland")
    ),
    thru = true,
    triggers = Triggers(
      cc = CcTriggers(enabled = false, prevTuningCc = 67, nextTuningCc = 66, ccThreshold = 0, isFilteringThru = true)
    )
  )

  override lazy val subConfigsToWrite: Seq[MidiInputConfig] = Seq()
}

class MidiOutputConfigTest extends SubConfigTest[MidiOutputConfig, MidiOutputConfigManager] {

  override lazy val subConfigManager: MidiOutputConfigManager = new MidiOutputConfigManager(mainConfigManager)

  override lazy val expectedSubConfigRead: MidiOutputConfig = MidiOutputConfig(
    devices = Seq(
      MidiDeviceId("FP-90", "Roland"),
      MidiDeviceId("P-125", "Yamaha")
    ),
    tunerType = TunerType.Mts,
    mtsTuningFormat = MtsTuningFormat.NonRealTime1BOctave,
    pitchBendSensitivity = PitchBendSensitivity.Default
  )

  override lazy val subConfigsToWrite: Seq[MidiOutputConfig] = Seq(
    expectedSubConfigRead.copy(
      devices = Seq(MidiDeviceId("blah", "bleh"))
    )
  )
}

class MidiConfigsTest extends AnyFlatSpec with Matchers {

  "MidiDeviceId.apply" should "convert from MidiDevice.Info to MidiDeviceId" in {
    // Given
    //   Extending MidiDevice.Info because its constructor is protected and we need to expose a public constructor
    class MyMidiDeviceInfo(name: String, vendor: String, description: String, version: String)
      extends MidiDevice.Info(name, vendor, description, version)
    val midiDeviceInfo = new MyMidiDeviceInfo("Seaboard", "ROLI", "Innovative piano", "3.14")
    // When
    val midiDeviceId = MidiDeviceId(midiDeviceInfo)
    // Then
    midiDeviceId shouldEqual MidiDeviceId("Seaboard", "ROLI")
  }
}
