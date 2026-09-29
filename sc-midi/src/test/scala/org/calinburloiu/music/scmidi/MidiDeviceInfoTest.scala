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

package org.calinburloiu.music.scmidi

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class MidiDeviceInfoTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  private val info: MidiDeviceInfo = MidiDeviceInfo(
    name = "CoreMIDI4J - FP-90",
    vendor = "Roland",
    description = "Digital piano",
    version = "1.0",
    transmittersLimit = MidiConnectionLimit.Unlimited,
    receiversLimit = MidiConnectionLimit.Limited(1)
  )

  "id" should {
    "be derived from the name and vendor" in {
      // When / Then
      info.id shouldEqual MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
    }
  }

  "direction" should {
    "derive the directions from the connection limits" in {
      // Given
      val cases = Table[MidiConnectionLimit, MidiConnectionLimit, MidiDirection, Boolean, Boolean](
        ("transmittersLimit", "receiversLimit", "direction", "isInputDevice", "isOutputDevice"),
        (MidiConnectionLimit.Unlimited, MidiConnectionLimit.Limited(0), MidiDirection.Input, true, false),
        (MidiConnectionLimit.Limited(0), MidiConnectionLimit.Limited(1), MidiDirection.Output, false, true),
        (MidiConnectionLimit.Limited(2), MidiConnectionLimit.Unlimited, MidiDirection.InputOutput, true, true),
        (MidiConnectionLimit.Limited(0), MidiConnectionLimit.Limited(0), MidiDirection.None, false, false)
      )

      forAll(cases) { (transmittersLimit, receiversLimit, direction, isInputDevice, isOutputDevice) =>
        // When
        val deviceInfo = info.copy(transmittersLimit = transmittersLimit, receiversLimit = receiversLimit)

        // Then
        deviceInfo.direction shouldEqual direction
        deviceInfo.isInputDevice shouldBe isInputDevice
        deviceInfo.isOutputDevice shouldBe isOutputDevice
      }
    }
  }
}
