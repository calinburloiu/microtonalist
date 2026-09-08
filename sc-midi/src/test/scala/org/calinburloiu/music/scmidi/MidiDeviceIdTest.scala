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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MidiDeviceIdTest extends AnyFlatSpec with Matchers {

  private val info: MidiDeviceInfo = MidiDeviceInfo(
    name = "CoreMIDI4J - FP-90",
    vendor = "Roland",
    description = "Digital piano",
    version = "1.0",
    maxTransmitters = MidiConnectionLimit.Limited(0),
    maxReceivers = MidiConnectionLimit.Limited(1)
  )

  behavior of "correspondsToInfo"

  it should "be true for the info the id was derived from" in {
    // When / Then
    MidiDeviceId("CoreMIDI4J - FP-90", "Roland").correspondsToInfo(info) shouldBe true
  }

  it should "be false for an info with another name or another vendor" in {
    // When / Then
    MidiDeviceId("CoreMIDI4J - FP-30", "Roland").correspondsToInfo(info) shouldBe false
    MidiDeviceId("CoreMIDI4J - FP-90", "Yamaha").correspondsToInfo(info) shouldBe false
  }

  behavior of "sanitizedName"

  it should "strip the CoreMIDI4J prefix" in {
    // When / Then
    MidiDeviceId("CoreMIDI4J - FP-90", "Roland").sanitizedName shouldEqual "FP-90"
  }

  it should "leave a name without the prefix untouched" in {
    // When / Then
    MidiDeviceId("FP-90", "Roland").sanitizedName shouldEqual "FP-90"
  }

  behavior of "toString"

  it should "quote the name and append the vendor in parentheses" in {
    // When / Then
    MidiDeviceId("FP-90", "Roland").toString shouldEqual "\"FP-90\" (Roland)"
  }

  it should "omit the parentheses for a blank vendor" in {
    // When / Then
    MidiDeviceId("IAC 1", " ").toString shouldEqual "\"IAC 1\""
  }
}
