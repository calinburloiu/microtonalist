/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.cli

import org.calinburloiu.music.scmidi.{MidiConnectionLimit, MidiDeviceInfo, MidiDirection, MidiManager}
import org.scalamock.scalatest.MockFactory
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream

class MidiDevicesCliCommandTest extends AnyWordSpec with Matchers with MockFactory {

  "run" should {
    "print only the section headers when there are no devices" in {
      // Given
      val midiManager = stub[MidiManager]
      midiManager.devicesInfoFor.when(MidiDirection.Input).returns(Seq.empty)
      midiManager.devicesInfoFor.when(MidiDirection.Output).returns(Seq.empty)
      val command = MidiDevicesCliCommand(midiManager)
      val out = ByteArrayOutputStream()

      // When
      Console.withOut(out) {
        command.run()
      }

      // Then
      out.toString shouldEqual
        """=== Input Devices ===
          |
          |
          |=== Output Devices ===
          |
          |""".stripMargin
    }

    "print the fields and the connection limit of every input and output device" in {
      // Given
      val input = MidiDeviceInfo(
        name = "CoreMIDI4J - Seaboard",
        vendor = "ROLI Ltd.",
        description = "MPE controller",
        version = "1.0",
        transmittersLimit = MidiConnectionLimit.Unlimited,
        receiversLimit = MidiConnectionLimit.Limited(0)
      )
      val output = MidiDeviceInfo(
        name = "CoreMIDI4J - FP-90",
        vendor = "Roland",
        description = "Digital piano",
        version = "2.1",
        transmittersLimit = MidiConnectionLimit.Limited(0),
        receiversLimit = MidiConnectionLimit.Limited(1)
      )
      val midiManager = stub[MidiManager]
      midiManager.devicesInfoFor.when(MidiDirection.Input).returns(Seq(input))
      midiManager.devicesInfoFor.when(MidiDirection.Output).returns(Seq(output))
      val command = MidiDevicesCliCommand(midiManager)
      val out = ByteArrayOutputStream()

      // When
      Console.withOut(out) {
        command.run()
      }

      // Then
      out.toString shouldEqual
        """=== Input Devices ===
          |
          |Name: CoreMIDI4J - Seaboard
          |Vendor: ROLI Ltd.
          |Version: 1.0
          |Description: MPE controller
          |Max. Transmitters: unlimited
          |
          |
          |=== Output Devices ===
          |
          |Name: CoreMIDI4J - FP-90
          |Vendor: Roland
          |Version: 2.1
          |Description: Digital piano
          |Max. Receivers: 1
          |
          |""".stripMargin
    }
  }
}
