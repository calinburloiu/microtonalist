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

import ch.qos.logback.classic.Level
import org.calinburloiu.music.microtonalist.common.LogCapture
import org.calinburloiu.music.microtonalist.common.LogCapture.*
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, NoteOnMidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import javax.sound.midi.{MidiUnavailableException, ShortMessage}

class JavaMidiDeviceHandleTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks with Stubs {

  private val deviceId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

  private val requestedDirection: MidiDirection = MidiDirection.Output

  /**
   * The failure the fixtures inject into the device. Being shared, it must not be used where the production code
   * calls `addSuppressed` on it, which mutates it for good: the case that needs an open failure and a close failure
   * at once builds its own instances for that reason.
   */
  private val failure: Exception = MidiUnavailableException("The device is busy")

  private val connected: MidiEvent = MidiDeviceConnectedEvent(deviceId, requestedDirection)
  private val disconnected: MidiEvent = MidiDeviceDisconnectedEvent(deviceId, requestedDirection)
  private val opened: MidiEvent = MidiDeviceOpenedEvent(deviceId, requestedDirection)
  private val closed: MidiEvent = MidiDeviceClosedEvent(deviceId, requestedDirection)

  private val noteOn: NoteOnMidiMsg = NoteOnMidiMsg(2, MidiNote.C4, 100)

  /** A second note on of the same pitch, with the zero velocity that conventionally releases it. */
  private val secondNoteOn: NoteOnMidiMsg = NoteOnMidiMsg(2, MidiNote.C4, 0)

  /** The sustain pedal (CC 64) pressed, as the tests expect it converted from Java Sound. */
  private val sustainOn: CcMidiMsg = CcMidiMsg(3, 64, 127)

  /** The sustain pedal (CC 64) released, as the tests expect it converted from Java Sound. */
  private val sustainOff: CcMidiMsg = CcMidiMsg(3, 64, 0)

  /**
   * An output handle over [[device]], which is not yet connected to it. The parameters configure the device.
   */
  private abstract class Fixture(maxTransmitters: Int = -1,
                                 maxReceivers: Int = -1,
                                 openFailure: Option[Exception] = None,
                                 closeFailure: Option[Exception] = None,
                                 receiverFailure: Option[Exception] = None) {
    val handle: JavaMidiDeviceHandle = JavaMidiDeviceHandle(deviceId, requestedDirection)
    val device: FakeMidiDevice = FakeMidiDevice(deviceId.name, deviceId.vendor, maxTransmitters = maxTransmitters,
      maxReceivers = maxReceivers, openFailure = openFailure, closeFailure = closeFailure,
      receiverFailure = receiverFailure)

    /** Informs the handle that `connectedDevice` got connected, as [[JavaMidiManager]] does. */
    def connect(connectedDevice: FakeMidiDevice = device): Seq[MidiEvent] =
      handle.connect(connectedDevice.asMidiDeviceInfo, connectedDevice)

    /**
     * Another instance of the device, as CoreMIDI4J creates one when the device is replugged or swapped. The
     * parameters configure the instance.
     */
    def newDevice(maxTransmitters: Int = -1, openFailure: Option[Exception] = None): FakeMidiDevice =
      FakeMidiDevice(deviceId.name, deviceId.vendor, maxTransmitters = maxTransmitters, openFailure = openFailure)
  }

  "A new JavaMidiDeviceHandle" should {
    "be closed and disconnected, with no device and no info" in new Fixture {
      // Then
      handle.id shouldEqual deviceId
      handle.requestedDirection shouldEqual requestedDirection
      handle.state shouldEqual State.Closed
      handle.isConnected shouldBe false
      handle.isOpen shouldBe false
      handle.isOpenRequested shouldBe false
      handle.device shouldBe empty
      handle.info shouldBe empty
    }

    "reject a direction other than input or output" in {
      // Given
      val directions = Table("direction", MidiDirection.None, MidiDirection.InputOutput)

      forAll(directions) { direction =>
        // When / Then
        an[IllegalArgumentException] should be thrownBy JavaMidiDeviceHandle(deviceId, direction)
      }
    }
  }

  "connect" should {
    "move a closed handle to Connected, exposing the device and its info without opening it" in new Fixture {
      // When
      val events: Seq[MidiEvent] = connect()

      // Then
      events shouldEqual Seq(connected)
      handle.state shouldEqual State.Connected
      handle.device shouldEqual Some(device)
      handle.info shouldEqual Some(device.asMidiDeviceInfo)
      device.openCount shouldEqual 0
    }

    "open the device of a handle waiting to open" in new Fixture {
      // Given
      handle.open()

      // When
      val events: Seq[MidiEvent] = connect()

      // Then
      events shouldEqual Seq(connected, opened)
      handle.state shouldEqual State.Open
      device.isOpen shouldBe true
    }

    "roll a handle waiting to open back to Connected, with no reference held, when its device fails to open" in
      new Fixture(openFailure = Some(failure)) {
        // Given
        handle.open()

        // When
        val events: Seq[MidiEvent] = connect()

        // Then
        events shouldEqual Seq(connected, MidiDeviceFailedToOpenEvent(deviceId, requestedDirection, failure))
        handle.state shouldEqual State.Connected
        handle.isOpenRequested shouldBe false
        handle.close() shouldBe empty
      }

    "change nothing when a connected handle is given the same device again" in new Fixture {
      // Given
      connect()

      // When
      val events: Seq[MidiEvent] = connect()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Connected
      device.closeCount shouldEqual 0
    }

    "change nothing when an open handle is given the same device again" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = connect()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Open
      device.isOpen shouldBe true
      device.openCount shouldEqual 1
      device.closeCount shouldEqual 0
    }

    "swap the device of a connected handle silently" in new Fixture {
      // Given
      val swappedDevice: FakeMidiDevice = newDevice()
      connect()

      // When
      val events: Seq[MidiEvent] = connect(swappedDevice)

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Connected
      handle.device shouldEqual Some(swappedDevice)
      device.closeCount shouldEqual 0
    }

    "close the device of an open handle and open the one it is swapped for, obtaining a new receiver" in new Fixture {
      // Given
      val swappedDevice: FakeMidiDevice = newDevice()
      connect()
      handle.open()
      // CoreMIDI4J closes the instance of a vanished endpoint before it notifies the change
      device.close()

      // When
      val events: Seq[MidiEvent] = connect(swappedDevice)
      handle.receiver.send(noteOn, 42L)

      // Then
      events shouldEqual Seq(closed, opened)
      handle.state shouldEqual State.Open
      handle.device shouldEqual Some(swappedDevice)
      device.isOpen shouldBe false
      swappedDevice.isOpen shouldBe true
      swappedDevice.receivedMessages.map { case (message, timeStamp) => (message.asScala, timeStamp) } shouldEqual
        Seq((noteOn, 42L))
    }

    "report a failure to close the device an open handle is swapped from, and still open the new one" in new Fixture {
      // Given
      val swappedDevice: FakeMidiDevice = newDevice()
      connect()
      handle.open()
      // CoreMIDI4J closes the instance of a vanished endpoint before it notifies the change
      device.close()
      device.closeFailure = Some(failure)

      // When
      val events: Seq[MidiEvent] = connect(swappedDevice)

      // Then
      events shouldEqual Seq(MidiDeviceFailedToCloseEvent(deviceId, requestedDirection, failure), opened)
      handle.state shouldEqual State.Open
      handle.device shouldEqual Some(swappedDevice)
      swappedDevice.isOpen shouldBe true
    }

    "roll an open handle back to Connected, with no reference held, when the device it is swapped for fails to open" in
      new Fixture {
        // Given
        val swappedDevice: FakeMidiDevice = newDevice(openFailure = Some(failure))
        connect()
        handle.open()
        // CoreMIDI4J closes the instance of a vanished endpoint before it notifies the change
        device.close()

        // When
        val events: Seq[MidiEvent] = connect(swappedDevice)

        // Then
        events shouldEqual Seq(closed, MidiDeviceFailedToOpenEvent(deviceId, requestedDirection, failure))
        handle.state shouldEqual State.Connected
        handle.isOpenRequested shouldBe false
        handle.device shouldEqual Some(swappedDevice)
        handle.close() shouldBe empty
      }

    "keep the device and the info of an open handle given another instance while the one it holds is still open" in
      new Fixture {
        // Given
        // A JDK Sequencer or Synthesizer provider builds a new instance on every lookup, leaving the held one open
        val anotherInstance: FakeMidiDevice = newDevice(maxTransmitters = 3)
        connect()
        handle.open()

        // When
        val events: Seq[MidiEvent] = connect(anotherInstance)

        // Then
        events shouldBe empty
        handle.state shouldEqual State.Open
        handle.device shouldEqual Some(device)
        handle.info shouldEqual Some(device.asMidiDeviceInfo)
        device.isOpen shouldBe true
        device.closeCount shouldEqual 0
        anotherInstance.openCount shouldEqual 0
      }

    "reject the info of another device, leaving the handle unchanged" in new Fixture {
      // Given
      val otherDevice: FakeMidiDevice = FakeMidiDevice("CoreMIDI4J - Seaboard", "ROLI")

      // When / Then
      an[IllegalArgumentException] should be thrownBy handle.connect(otherDevice.asMidiDeviceInfo, otherDevice)
      handle.state shouldEqual State.Closed
      handle.isConnected shouldBe false
    }
  }

  "disconnect" should {
    "close the device of an open handle and forget it along with its info, waiting to open it again" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.disconnect()

      // Then
      events shouldEqual Seq(closed, disconnected)
      device.isOpen shouldBe false
      handle.state shouldEqual State.WaitingToOpen
      handle.isOpenRequested shouldBe true
      handle.device shouldBe empty
      handle.info shouldBe empty
    }

    "move a connected handle to Closed, closing the device it did not open" in new Fixture {
      // Given
      connect()

      // When
      val events: Seq[MidiEvent] = handle.disconnect()

      // Then
      events shouldEqual Seq(disconnected)
      handle.state shouldEqual State.Closed
      handle.device shouldBe empty
      device.closeCount shouldEqual 1
    }

    "report only a failure to disconnect a connected handle whose device fails to close, and still close it" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()

        // When
        val events: Seq[MidiEvent] = handle.disconnect()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToDisconnectEvent(deviceId, requestedDirection, failure))
        handle.state shouldEqual State.Closed
        handle.device shouldBe empty
      }

    "report only a failure to disconnect an open handle whose device fails to close, and still make it wait" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()
        handle.open()

        // When
        val events: Seq[MidiEvent] = handle.disconnect()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToDisconnectEvent(deviceId, requestedDirection, failure))
        handle.state shouldEqual State.WaitingToOpen
      }

    "make an open handle open the device it gets when it reconnects" in new Fixture {
      // Given
      val repluggedDevice: FakeMidiDevice = newDevice()
      connect()
      handle.open()
      handle.disconnect()

      // When
      val events: Seq[MidiEvent] = connect(repluggedDevice)

      // Then
      events shouldEqual Seq(connected, opened)
      handle.state shouldEqual State.Open
      repluggedDevice.isOpen shouldBe true
    }

    "keep both references of a handle held twice when its device is replugged" in new Fixture {
      // Given
      val repluggedDevice: FakeMidiDevice = newDevice()
      connect()
      handle.open()
      handle.open()
      handle.disconnect()

      // When
      connect(repluggedDevice)

      // Then
      // Disconnecting leaves the reference count untouched, so the replugged device still takes two closes to close,
      // as two tracks sharing it expect
      handle.state shouldEqual State.Open
      handle.close() shouldBe empty
      handle.state shouldEqual State.Open
      handle.close() shouldEqual Seq(closed)
      handle.state shouldEqual State.Connected
      repluggedDevice.closeCount shouldEqual 1
    }

    "change nothing on a handle that is not connected" in new Fixture {
      // Given
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.disconnect()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.WaitingToOpen
    }
  }

  "open" should {
    "make a disconnected handle wait to open, without opening anything" in new Fixture {
      // When
      val events: Seq[MidiEvent] = handle.open()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.WaitingToOpen
      handle.isOpenRequested shouldBe true
      device.openCount shouldEqual 0
    }

    "open the device of a connected handle" in new Fixture {
      // Given
      connect()

      // When
      val events: Seq[MidiEvent] = handle.open()

      // Then
      events shouldEqual Seq(opened)
      handle.state shouldEqual State.Open
      device.isOpen shouldBe true
    }

    "open the device only on the first of several calls" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.open()

      // Then
      events shouldBe empty
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
      handle.state shouldEqual State.Open
      Option(device.transmitter.getReceiver) shouldBe empty
    }

    "roll back to Connected, closing the device, with no reference held, when the device fails to open" in
      new Fixture(openFailure = Some(failure)) {
        // Given
        connect()

        // When
        val events: Seq[MidiEvent] = handle.open()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToOpenEvent(deviceId, requestedDirection, failure))
        handle.state shouldEqual State.Connected
        handle.isOpenRequested shouldBe false
        device.closeCount shouldEqual 1
        handle.close() shouldBe empty
      }

    "roll back to Connected when the device fails to provide a receiver, dropping the messages sent" in
      new Fixture(receiverFailure = Some(failure)) {
        // Given
        connect()

        // When
        val events: Seq[MidiEvent] = handle.open()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToOpenEvent(deviceId, requestedDirection, failure))
        handle.state shouldEqual State.Connected
        device.isOpen shouldBe false

        // When / Then
        noException should be thrownBy handle.receiver.send(noteOn, 42L)
        device.receivedMessages shouldBe empty
      }

    "report only the failure to open when closing the device on the way back fails too, keeping the close failure " +
      "as a suppressed exception" in {
        // Given
        val openFailure = MidiUnavailableException("The device is busy")
        val closeFailure = IllegalStateException("Cannot close")

        new Fixture(openFailure = Some(openFailure), closeFailure = Some(closeFailure)) {
          connect()

          // When
          val events: Seq[MidiEvent] = handle.open()

          // Then
          events shouldEqual Seq(MidiDeviceFailedToOpenEvent(deviceId, requestedDirection, openFailure))
          handle.state shouldEqual State.Connected
          openFailure.getSuppressed shouldEqual Array(closeFailure)
        }
      }
  }

  "close" should {
    "close the device on the last of several closes only, returning the handle to Connected" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.open()

      // When
      val firstEvents: Seq[MidiEvent] = handle.close()

      // Then
      firstEvents shouldBe empty
      device.isOpen shouldBe true
      handle.state shouldEqual State.Open

      // When
      val lastEvents: Seq[MidiEvent] = handle.close()

      // Then
      lastEvents shouldEqual Seq(closed)
      device.isOpen shouldBe false
      handle.state shouldEqual State.Connected
    }

    "return a handle waiting to open to Closed, so that connecting the device no longer opens it" in new Fixture {
      // Given
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.close()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Closed

      // When
      val connectionEvents: Seq[MidiEvent] = connect()

      // Then
      connectionEvents shouldEqual Seq(connected)
      device.openCount shouldEqual 0
    }

    "report a failure to close the device, and still move to Connected" in new Fixture(closeFailure = Some(failure)) {
      // Given
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.close()

      // Then
      events shouldEqual Seq(MidiDeviceFailedToCloseEvent(deviceId, requestedDirection, failure))
      handle.state shouldEqual State.Connected
    }

    "do nothing when no reference is held" in new Fixture {
      // Given
      connect()

      // When
      val events: Seq[MidiEvent] = handle.close()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Connected
      device.closeCount shouldEqual 0
    }
  }

  "closeAll" should {
    "release every reference, closing the device" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.closeAll()

      // Then
      events shouldEqual Seq(closed)
      handle.state shouldEqual State.Connected
      device.isOpen shouldBe false
      handle.close() shouldBe empty
    }

    "return a handle waiting to open to Closed" in new Fixture {
      // Given
      handle.open()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.closeAll()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Closed
    }

    "do nothing when no reference is held" in new Fixture {
      // Given
      connect()

      // When / Then
      handle.closeAll() shouldBe empty
      handle.state shouldEqual State.Connected
    }
  }

  "receiver" should {
    "convert each message to Java Sound and send it to the open device, with its time stamp" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      handle.receiver.send(noteOn, 42L)

      // Then
      device.receivedMessages.map { case (message, timeStamp) => (message.asScala, timeStamp) } shouldEqual
        Seq((noteOn, 42L))
    }

    "obtain a single receiver from the device for all the messages it sends while the device is open" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      handle.receiver.send(noteOn, 42L)
      handle.receiver.send(secondNoteOn, 43L)

      // Then
      device.receivedMessages should have size 2
      device.receiverCount shouldEqual 1
    }

    "send to the open device again after the device was closed and opened again" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.receiver.send(noteOn, 42L)
      handle.close()
      handle.open()

      // When
      handle.receiver.send(secondNoteOn, 43L)

      // Then
      device.receivedMessages.map { case (message, timeStamp) => (message.asScala, timeStamp) } shouldEqual
        Seq((noteOn, 42L), (secondNoteOn, 43L))
    }

    "drop the messages sent while the device is connected but not open" in new Fixture {
      // Given
      connect()

      // When
      handle.receiver.send(noteOn, 42L)

      // Then
      device.receivedMessages shouldBe empty
    }

    "drop the messages sent before the device is connected, instead of delivering them once it opens" in new Fixture {
      // Given
      handle.open()

      // When
      handle.receiver.send(noteOn, 42L)
      connect()

      // Then
      handle.state shouldEqual State.Open
      handle.isOpen shouldBe true
      device.receivedMessages shouldBe empty
    }

    "not ask a device that is not an output for a receiver" in new Fixture(maxReceivers = 0) {
      // Given
      connect()
      handle.open()

      // When / Then
      noException should be thrownBy handle.receiver.send(noteOn, 42L)
      device.receiverCount shouldEqual 0
    }

    "drop the messages sent after the device was closed" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.close()

      // When / Then
      noException should be thrownBy handle.receiver.send(noteOn, 42L)
      device.receivedMessages shouldBe empty
    }

    "drop the messages when the device opened again fails to provide a receiver" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.close()
      device.receiverFailure = Some(failure)

      // When
      handle.open()

      // Then
      handle.state shouldEqual State.Connected
      noException should be thrownBy handle.receiver.send(noteOn, 42L)
      device.receivedMessages shouldBe empty
    }

    "drop a message that reaches a receiver Java Sound already closed, without throwing" in new Fixture {
      // Given
      connect()
      handle.open()
      // Java Sound closes the device, and its receivers, before the manager learns that the device is gone
      device.close()

      // When / Then
      noException should be thrownBy handle.receiver.send(noteOn, 42L)
      device.receivedMessages shouldBe empty
    }
  }

  "transmitter" should {
    "fan out the messages of the open input device, converted from Java Sound, to receivers subscribed beforehand" in
      new Fixture {
        // Given
        val receiver1: Stub[MidiReceiver] = stub[MidiReceiver]
        val receiver2: Stub[MidiReceiver] = stub[MidiReceiver]
        Seq(receiver1, receiver2).foreach(_.send.returns(_ => ()))
        handle.transmitter.addReceiver(receiver1)
        handle.transmitter.addReceiver(receiver2)
        connect()
        handle.open()

        // When
        device.transmitter.getReceiver.send(ShortMessage(ShortMessage.CONTROL_CHANGE, 3, 64, 127), 7L)

        // Then
        receiver1.send.calls shouldEqual Seq((sustainOn, 7L))
        receiver2.send.calls shouldEqual Seq((sustainOn, 7L))
      }

    "keep fanning out after Java Sound closes the receiver the handle subscribed to the device" in new Fixture {
      // Given
      val receiver: Stub[MidiReceiver] = stub[MidiReceiver]
      receiver.send.returns(_ => ())
      handle.transmitter.addReceiver(receiver)
      connect()
      handle.open()
      val inboundReceiver = device.transmitter.getReceiver

      // When
      inboundReceiver.close()
      inboundReceiver.send(ShortMessage(ShortMessage.CONTROL_CHANGE, 3, 64, 0), 8L)

      // Then
      receiver.send.calls shouldEqual Seq((sustainOff, 8L))
    }
  }

  "Logging" should {
    val loggerName = classOf[JavaMidiDeviceHandle].getName

    "report the connection of the device at debug level, with the connection limit of its direction" in {
      // Given
      val inputDevice = FakeMidiDevice("CoreMIDI4J - Seaboard", "ROLI", maxTransmitters = 2, maxReceivers = 0)
      val outputDevice = FakeMidiDevice(deviceId.name, deviceId.vendor, maxTransmitters = 0, maxReceivers = -1)
      val inputHandle = JavaMidiDeviceHandle(inputDevice.id, MidiDirection.Input)
      val outputHandle = JavaMidiDeviceHandle(outputDevice.id, MidiDirection.Output)

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        inputHandle.connect(inputDevice.asMidiDeviceInfo, inputDevice)
        outputHandle.connect(outputDevice.asMidiDeviceInfo, outputDevice)
      }

      // Then
      events.messagesAt(Level.DEBUG) shouldEqual Seq(
        """Input device "CoreMIDI4J - Seaboard" (ROLI) with 2 transmitters was connected.""",
        """Output device "CoreMIDI4J - FP-90" (Roland) with unlimited receivers was connected."""
      )
    }

    "report the disconnection of a device that was not open at debug level" in new Fixture {
      // Given
      connect()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.disconnect()
      }

      // Then
      events.messagesAt(Level.DEBUG) shouldEqual Seq(
        """Output device "CoreMIDI4J - FP-90" (Roland) was disconnected.""")
      events.messagesAt(Level.WARN) shouldBe empty
    }

    "report the closing of an open device at info level, then its disconnection at warn level" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.disconnect()
      }

      // Then
      events.filter(event => Set(Level.INFO, Level.WARN).contains(event.getLevel))
        .map(event => (event.getLevel, event.getFormattedMessage)) shouldEqual Seq(
          (Level.INFO, """Successfully closed output device "CoreMIDI4J - FP-90" (Roland)."""),
          (Level.WARN, """Output device "CoreMIDI4J - FP-90" (Roland) was disconnected.""")
        )
    }

    "report the opening and the closing of the device at info level" in new Fixture {
      // Given
      connect()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.open()
        handle.close()
      }

      // Then
      events.messagesAt(Level.INFO) shouldEqual Seq(
        """Successfully opened output device "CoreMIDI4J - FP-90" (Roland).""",
        """Successfully closed output device "CoreMIDI4J - FP-90" (Roland)."""
      )
    }

    "report a failure to open the device at error level, with its cause" in new Fixture(openFailure = Some(failure)) {
      // Given
      connect()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.open()
      }

      // Then
      events.failuresAt(Level.ERROR) shouldEqual
        Seq(("""Failed to open output device "CoreMIDI4J - FP-90" (Roland).""", Some(failure.getMessage)))
    }

    "report a failure to close the device at error level, with its cause" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()
        handle.open()

        // When
        val (_, events) = LogCapture.capturing(loggerName) {
          handle.close()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual
          Seq(("""Failed to close output device "CoreMIDI4J - FP-90" (Roland)!""", Some(failure.getMessage)))
      }

    "report a failure to disconnect from the device at error level, with its cause" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()

        // When
        val (_, events) = LogCapture.capturing(loggerName) {
          handle.disconnect()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual Seq((
          """Failed to disconnect from output device "CoreMIDI4J - FP-90" (Roland)!""",
          Some(failure.getMessage)
        ))
      }

    "warn of the first message dropped after each open and report the following ones at debug level" in new Fixture {
      // Given
      connect()
      handle.open()
      device.close()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.receiver.send(noteOn, 42L)
        handle.receiver.send(secondNoteOn, 43L)
        handle.close()
        handle.open()
        device.close()
        handle.receiver.send(noteOn, 44L)
      }

      // Then
      val warning = """Dropping the messages sent to output device "CoreMIDI4J - FP-90" (Roland), which Java Sound """ +
        "already closed, until it opens again."
      events.messagesAt(Level.WARN) shouldEqual Seq(warning, warning)
      events.messagesAt(Level.DEBUG) shouldEqual
        Seq(s"""Dropping $secondNoteOn sent to output device "CoreMIDI4J - FP-90" (Roland), which Java Sound """ +
          "already closed.")
    }
  }
}
