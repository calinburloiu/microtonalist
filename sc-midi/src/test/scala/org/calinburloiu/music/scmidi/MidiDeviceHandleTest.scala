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

import org.calinburloiu.music.scmidi.MidiConnectionLimit.{Limited, Unlimited}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class MidiDeviceHandleTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  /** A handle that only knows its info; the members under test derive from it. */
  private class InfoOnlyHandle(override val info: Option[MidiDeviceInfo]) extends MidiDeviceHandle {
    override def id: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

    override def state: MidiDeviceHandle.State = MidiDeviceHandle.State.Closed

    override def isConnected: Boolean = false

    override def isOpen: Boolean = false

    override def open(): Unit = {}

    override def close(): Unit = {}

    override def receiver: MidiReceiver = NoOpMidiReceiver()

    override def transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
  }

  private def info(maxTransmitters: MidiConnectionLimit, maxReceivers: MidiConnectionLimit): MidiDeviceInfo =
    MidiDeviceInfo("CoreMIDI4J - FP-90", "Roland", "Digital piano", "1.0", maxTransmitters, maxReceivers)

  behavior of "endpointType"

  it should "derive the directions from the info while connected and report none while disconnected" in {
    // Given
    val cases = Table[Option[MidiDeviceInfo], MidiEndpointType, Boolean, Boolean](
      ("info", "endpointType", "isInputDevice", "isOutputDevice"),
      (None, MidiEndpointType.None, false, false),
      (Some(info(Unlimited, Limited(0))), MidiEndpointType.Input, true, false),
      (Some(info(Limited(0), Limited(1))), MidiEndpointType.Output, false, true),
      (Some(info(Limited(1), Unlimited)), MidiEndpointType.InputOutput, true, true)
    )

    forAll(cases) { (info, endpointType, isInputDevice, isOutputDevice) =>
      // When
      val handle = InfoOnlyHandle(info)

      // Then
      handle.endpointType shouldEqual endpointType
      handle.isInputDevice shouldBe isInputDevice
      handle.isOutputDevice shouldBe isOutputDevice
    }
  }
}
