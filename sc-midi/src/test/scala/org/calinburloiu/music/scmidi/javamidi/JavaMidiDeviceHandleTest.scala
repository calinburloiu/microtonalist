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

package org.calinburloiu.music.scmidi.javamidi

import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, NoteOnMidiMsg}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import javax.sound.midi.{MidiUnavailableException, ShortMessage}

class JavaMidiDeviceHandleTest extends AnyWordSpec with Matchers {

  private val deviceId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

  private val failure: Exception = MidiUnavailableException("The device is busy")

  /**
   * A handle over [[device]], which is not yet connected to it. The parameters configure the device.
   */
  private abstract class Fixture(maxTransmitters: Int = -1,
                                 openFailure: Option[Exception] = None,
                                 closeFailure: Option[Exception] = None,
                                 providesReceiver: Boolean = true) {
    val businessync: RecordingBusinessync = RecordingBusinessync()
    val handle: JavaMidiDeviceHandle = JavaMidiDeviceHandle(deviceId, businessync)
    val device: FakeMidiDevice = FakeMidiDevice(deviceId.name, deviceId.vendor, maxTransmitters = maxTransmitters,
      openFailure = openFailure, closeFailure = closeFailure, providesReceiver = providesReceiver)

    /** Informs the handle that `connectedDevice` got connected, as [[JavaMidiManager]] does. */
    def connect(connectedDevice: FakeMidiDevice = device): Unit = {
      handle.onConnect(connectedDevice.asMidiDeviceInfo, connectedDevice)
    }
  }

  "A new JavaMidiDeviceHandle" should {
    "be closed and disconnected, with no device and no info" in new Fixture {
      // Then
      handle.id shouldEqual deviceId
      handle.state shouldEqual State.Closed
      handle.isConnected shouldBe false
      handle.isOpen shouldBe false
      handle.device shouldBe empty
      handle.info shouldBe empty
    }
  }

  "onConnect" should {
    "move a closed handle to Connected, exposing the device and its info without opening it" in new Fixture {
      // When
      connect()

      // Then
      handle.state shouldEqual State.Connected
      handle.isConnected shouldBe true
      handle.isOpen shouldBe false
      handle.device shouldEqual Some(device)
      handle.info shouldEqual Some(device.asMidiDeviceInfo)
      device.openCount shouldEqual 0
    }

    "open the device of a handle waiting to open" in new Fixture {
      // Given
      handle.open()

      // When
      connect()

      // Then
      handle.state shouldEqual State.Open
      handle.isOpen shouldBe true
      device.openCount shouldEqual 1
    }

    "release the device it replaces" in new Fixture {
      // Given
      val replacement: FakeMidiDevice = FakeMidiDevice(deviceId.name, deviceId.vendor)
      connect()

      // When
      connect(replacement)

      // Then
      device.closeCount shouldEqual 1
      handle.device shouldEqual Some(replacement)
      handle.state shouldEqual State.Connected
    }

    "reject the info of another device, leaving the handle disconnected" in new Fixture {
      // Given
      val otherDevice: FakeMidiDevice = FakeMidiDevice("CoreMIDI4J - Seaboard", "ROLI")

      // When / Then
      an[IllegalArgumentException] should be thrownBy handle.onConnect(otherDevice.asMidiDeviceInfo, otherDevice)
      handle.isConnected shouldBe false
    }
  }

  "onDisconnect" should {
    "close the device and forget it along with its info" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      handle.onDisconnect()

      // Then
      device.isOpen shouldBe false
      handle.isConnected shouldBe false
      handle.isOpen shouldBe false
      handle.device shouldBe empty
      handle.info shouldBe empty
      businessync.events shouldBe empty
    }

    "publish MidiDeviceFailedToDisconnectEvent when the device fails to close, and still forget the device" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()

        // When
        handle.onDisconnect()

        // Then
        businessync.events shouldEqual Seq(MidiDeviceFailedToDisconnectEvent(deviceId, failure))
        handle.isConnected shouldBe false
        handle.device shouldBe empty
      }
  }

  "open" should {
    "make a disconnected handle wait to open, without opening anything" in new Fixture {
      // When
      handle.open()

      // Then
      handle.state shouldEqual State.WaitingToOpen
      handle.isOpen shouldBe false
      device.openCount shouldEqual 0
    }

    "open the device of a connected handle" in new Fixture {
      // Given
      connect()

      // When
      handle.open()

      // Then
      handle.state shouldEqual State.Open
      handle.isOpen shouldBe true
      device.isOpen shouldBe true
      businessync.events shouldBe empty
    }

    "open the device only on the first of several calls" in new Fixture {
      // Given
      connect()

      // When
      handle.open()
      handle.open()

      // Then
      device.openCount shouldEqual 1
      handle.state shouldEqual State.Open
    }

    "subscribe to the device's transmitter when the device is an input" in new Fixture {
      // Given
      connect()

      // When
      handle.open()

      // Then
      Option(device.transmitter.getReceiver) shouldBe defined
    }

    "not subscribe to the device's transmitter when the device is not an input" in new Fixture(maxTransmitters = 0) {
      // Given
      connect()

      // When
      handle.open()

      // Then
      handle.isOpen shouldBe true
      Option(device.transmitter.getReceiver) shouldBe empty
    }

    "publish MidiDeviceFailedToOpenEvent when the device fails to open" in new Fixture(openFailure = Some(failure)) {
      // Given
      connect()

      // When
      handle.open()

      // Then
      businessync.events shouldEqual Seq(MidiDeviceFailedToOpenEvent(deviceId, failure))
      handle.isOpen shouldBe false
    }
  }

  "close" should {
    "close the device on the last of several closes only, returning the handle to Connected" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.open()

      // When
      handle.close()

      // Then
      device.isOpen shouldBe true
      handle.state shouldEqual State.Open

      // When
      handle.close()

      // Then
      device.isOpen shouldBe false
      handle.isOpen shouldBe false
      handle.isConnected shouldBe true
      handle.state shouldEqual State.Connected
    }

    "return a handle waiting to open to Closed, so that connecting the device no longer opens it" in new Fixture {
      // Given
      handle.open()

      // When
      handle.close()

      // Then
      handle.state shouldEqual State.Closed

      // When
      connect()

      // Then
      handle.state shouldEqual State.Connected
      device.openCount shouldEqual 0
    }

    "publish MidiDeviceFailedToCloseEvent when the device fails to close, and still return the handle to Connected" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()
        handle.open()

        // When
        handle.close()

        // Then
        businessync.events shouldEqual Seq(MidiDeviceFailedToCloseEvent(deviceId, failure))
        handle.state shouldEqual State.Connected
      }
  }

  "receiver" should {
    "convert each message to Java Sound and send it to the open device, with its time stamp" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      handle.receiver.send(NoteOnMidiMsg(2, MidiNote.C4, 100), 42L)

      // Then
      device.receiver.messages.map { case (message, timeStamp) => (message.asScala, timeStamp) } shouldEqual
        Seq((NoteOnMidiMsg(2, MidiNote.C4, 100), 42L))
    }

    "drop the messages sent while the device is connected but not open" in new Fixture {
      // Given
      connect()

      // When
      handle.receiver.send(NoteOnMidiMsg(2, MidiNote.C4, 100), 42L)

      // Then
      device.receiver.messages shouldBe empty
    }

    "drop the messages sent before the device is connected, instead of delivering them once it opens" in new Fixture {
      // Given
      handle.open()

      // When
      handle.receiver.send(NoteOnMidiMsg(2, MidiNote.C4, 100), 42L)
      connect()

      // Then
      handle.isOpen shouldBe true
      device.receiver.messages shouldBe empty
    }

    "drop the messages when the open device provides no receiver" in new Fixture(providesReceiver = false) {
      // Given
      connect()
      handle.open()

      // When
      handle.receiver.send(NoteOnMidiMsg(2, MidiNote.C4, 100), 42L)

      // Then
      device.receiver.messages shouldBe empty
    }
  }

  "transmitter" should {
    "fan out the messages of the open input device, converted from Java Sound, to receivers subscribed beforehand" in
      new Fixture {
        // Given
        val receiver1: RecordingMidiReceiver = RecordingMidiReceiver()
        val receiver2: RecordingMidiReceiver = RecordingMidiReceiver()
        handle.transmitter.addReceiver(receiver1)
        handle.transmitter.addReceiver(receiver2)
        connect()
        handle.open()

        // When
        device.transmitter.getReceiver.send(ShortMessage(ShortMessage.CONTROL_CHANGE, 3, 64, 127), 7L)

        // Then
        receiver1.messages shouldEqual Seq((CcMidiMsg(3, 64, 127), 7L))
        receiver2.messages shouldEqual Seq((CcMidiMsg(3, 64, 127), 7L))
      }

    "keep fanning out after Java Sound closes the receiver the handle subscribed to the device" in new Fixture {
      // Given
      val receiver: RecordingMidiReceiver = RecordingMidiReceiver()
      handle.transmitter.addReceiver(receiver)
      connect()
      handle.open()
      val inboundReceiver = device.transmitter.getReceiver

      // When
      inboundReceiver.close()
      inboundReceiver.send(ShortMessage(ShortMessage.CONTROL_CHANGE, 3, 64, 0), 8L)

      // Then
      receiver.messages shouldEqual Seq((CcMidiMsg(3, 64, 0), 8L))
    }
  }
}
