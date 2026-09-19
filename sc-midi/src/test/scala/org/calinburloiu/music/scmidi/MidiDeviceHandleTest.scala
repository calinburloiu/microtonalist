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

  /** A handle that only knows its info and its state; the members under test derive from them. */
  private class TestHandle(override val info: Option[MidiDeviceInfo] = None,
                           override val state: MidiDeviceHandle.State = MidiDeviceHandle.State.Closed)
    extends MidiDeviceHandle {
    override def id: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

    override def receiver: MidiReceiver = NoOpMidiReceiver()

    override def transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
  }

  private def info(transmittersLimit: MidiConnectionLimit, receiversLimit: MidiConnectionLimit): MidiDeviceInfo =
    MidiDeviceInfo("CoreMIDI4J - FP-90", "Roland", "Digital piano", "1.0", transmittersLimit, receiversLimit)

  behavior of "direction"

  it should "derive the directions from the info while connected and report none while disconnected" in {
    // Given
    val cases = Table[Option[MidiDeviceInfo], MidiDirection, Boolean, Boolean](
      ("info", "direction", "isInputDevice", "isOutputDevice"),
      (None, MidiDirection.None, false, false),
      (Some(info(Unlimited, Limited(0))), MidiDirection.Input, true, false),
      (Some(info(Limited(0), Limited(1))), MidiDirection.Output, false, true),
      (Some(info(Limited(1), Unlimited)), MidiDirection.InputOutput, true, true)
    )

    forAll(cases) { (info, direction, isInputDevice, isOutputDevice) =>
      // When
      val handle = TestHandle(info = info)

      // Then
      handle.direction shouldEqual direction
      handle.isInputDevice shouldBe isInputDevice
      handle.isOutputDevice shouldBe isOutputDevice
    }
  }

  behavior of "isConnected, isOpen and isOpenRequested"

  it should "derive from the state, the device being open for use only in Open" in {
    // Given
    val cases = Table[MidiDeviceHandle.State, Boolean, Boolean, Boolean](
      ("state", "isConnected", "isOpen", "isOpenRequested"),
      (MidiDeviceHandle.State.Closed, false, false, false),
      (MidiDeviceHandle.State.Connected, true, false, false),
      (MidiDeviceHandle.State.WaitingToOpen, false, false, true),
      (MidiDeviceHandle.State.Open, true, true, true)
    )

    forAll(cases) { (state, isConnected, isOpen, isOpenRequested) =>
      // When
      val handle = TestHandle(state = state)

      // Then
      handle.isConnected shouldBe isConnected
      handle.isOpen shouldBe isOpen
      handle.isOpenRequested shouldBe isOpenRequested
    }
  }

  behavior of "State"

  it should "cover every combination of being connected and being requested to open with exactly one state" in {
    // Given
    val cases = Table[MidiDeviceHandle.State, Boolean, Boolean](
      ("state", "isConnected", "isOpenRequested"),
      (MidiDeviceHandle.State.Closed, false, false),
      (MidiDeviceHandle.State.Connected, true, false),
      (MidiDeviceHandle.State.WaitingToOpen, false, true),
      (MidiDeviceHandle.State.Open, true, true)
    )

    forAll(cases) { (state, isConnected, isOpenRequested) =>
      // When / Then
      state.isConnected shouldBe isConnected
      state.isOpenRequested shouldBe isOpenRequested
    }
    // Then
    MidiDeviceHandle.State.values.map(state => (state.isConnected, state.isOpenRequested)).distinct should have size 4
  }
}
