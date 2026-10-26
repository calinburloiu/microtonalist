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

package org.calinburloiu.music.microtonalist.perf.midilatency.midi

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfSetupException
import org.calinburloiu.music.scmidi.javamidi.{FakeJavaMidiEnvironment, FakeMidiDevice, JavaMidiManager}
import org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOnMidiMsg}
import org.calinburloiu.music.scmidi.{MidiDeviceId, MidiNote, MidiReceiver}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import javax.sound.midi.{MidiUnavailableException, ShortMessage}
import scala.collection.mutable

class DeviceMidiPathTest extends AnyWordSpec with Matchers {

  private abstract class Fixture {
    val environment: FakeJavaMidiEnvironment = FakeJavaMidiEnvironment()
    val sendDevice: FakeMidiDevice = FakeMidiDevice("Bus 1", vendor = "Acme", maxTransmitters = 0)
    val receiveDevice: FakeMidiDevice = FakeMidiDevice("Bus 2", vendor = "Acme", maxReceivers = 0)
    environment.plug(sendDevice)
    environment.plug(receiveDevice)
    val manager: JavaMidiManager = JavaMidiManager(environment)

    val received: mutable.Buffer[MidiMsg] = mutable.ArrayBuffer()
    val receiver: MidiReceiver = (message, _) => received += message

    /** Simulates a message coming from the receive device. */
    def receiveFromDevice(note: Int): Unit =
      receiveDevice.transmitter.getReceiver.send(ShortMessage(ShortMessage.NOTE_ON, 0, note, 64), -1)
  }

  "DeviceMidiPath" should {
    "send to the send device" in new Fixture {
      // Given
      private val path = DeviceMidiPath.open(manager, sendDevice.id, receiveDevice.id)

      // When
      path.sender.send(NoteOnMidiMsg(0, MidiNote(64)))

      // Then
      sendDevice.receivedMessages should have size 1
      sendDevice.receivedMessages.head._1.getMessage.toSeq shouldEqual Seq(0x90.toByte, 64.toByte, 64.toByte)
    }

    "deliver what the receive device sends to an attached receiver, and nothing after detach" in new Fixture {
      // Given
      private val path = DeviceMidiPath.open(manager, sendDevice.id, receiveDevice.id)
      path.attach(receiver)

      // When
      receiveFromDevice(64)
      path.detach(receiver)
      receiveFromDevice(65)

      // Then
      received.toSeq shouldEqual Seq(NoteOnMidiMsg(0, MidiNote(64)))
    }

    "fail with the available devices when a device is missing" in new Fixture {
      // When
      private val exception = the[PerfSetupException] thrownBy
        DeviceMidiPath.open(manager, MidiDeviceId("Bus 9", "Acme"), receiveDevice.id)

      // Then
      exception.getMessage should include("Bus 9")
      exception.getMessage should include("Bus 1")
    }

    "fail when a device fails to open, releasing the other" in new Fixture {
      // Given
      receiveDevice.openFailure = Some(MidiUnavailableException("Busy"))

      // When
      private val exception = the[PerfSetupException] thrownBy
        DeviceMidiPath.open(manager, sendDevice.id, receiveDevice.id)

      // Then
      exception.getMessage should include("Bus 2")
      sendDevice.isOpen shouldBe false
    }

    "release both devices on close" in new Fixture {
      // Given
      private val path = DeviceMidiPath.open(manager, sendDevice.id, receiveDevice.id)

      // When
      path.close()

      // Then
      sendDevice.closeCount shouldEqual 1
      receiveDevice.closeCount shouldEqual 1
    }
  }
}
