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
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.LogCapture.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import javax.sound.midi.MidiUnavailableException

/**
 * Tests [[JavaMidiManager]] over a [[FakeJavaMidiEnvironment]].
 *
 * The manager keeps inputs and outputs apart, behind two mirrored halves of its API; the behaviours of each half are
 * shared (see [[deviceEndpoint]]) and run once per [[Direction]]. The other sections cover what is common to both.
 *
 * Where the manager does not yet keep its handles up to date as [[MidiManager]] and [[MidiDeviceHandle]] document
 * (#288), the tests that run exercise the code path without asserting the outcome that #288 is going to change, while
 * the tests that pin the expected outcome are ignored, each under a `TODO #288`, until #288 is fixed.
 */
class JavaMidiManagerTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  private val deviceName: String = "CoreMIDI4J - FP-90"

  /** One direction of the [[MidiManager]] API, so that the same behaviours run for inputs and for outputs. */
  private trait Direction {
    /** Creates a device that works in this direction only. */
    def newDevice(name: String): FakeMidiDevice

    def isAvailable(manager: MidiManager, id: MidiDeviceId): Boolean

    def deviceInfoOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceInfo]

    def deviceIds(manager: MidiManager): Seq[MidiDeviceId]

    def devicesInfo(manager: MidiManager): Seq[MidiDeviceInfo]

    def open(manager: MidiManager, id: MidiDeviceId): MidiDeviceHandle

    def deviceHandleOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceHandle]

    def openedDevices(manager: MidiManager): Seq[MidiDeviceHandle]

    def close(manager: MidiManager, id: MidiDeviceId): Unit
  }

  private object Input extends Direction {
    override def newDevice(name: String): FakeMidiDevice = FakeMidiDevice(name, maxTransmitters = -1, maxReceivers = 0)

    override def isAvailable(manager: MidiManager, id: MidiDeviceId): Boolean = manager.isInputAvailable(id)

    override def deviceInfoOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceInfo] =
      manager.inputDeviceInfoOf(id)

    override def deviceIds(manager: MidiManager): Seq[MidiDeviceId] = manager.inputDeviceIds

    override def devicesInfo(manager: MidiManager): Seq[MidiDeviceInfo] = manager.inputDevicesInfo

    override def open(manager: MidiManager, id: MidiDeviceId): MidiDeviceHandle = manager.openInput(id)

    override def deviceHandleOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceHandle] =
      manager.inputDeviceHandleOf(id)

    override def openedDevices(manager: MidiManager): Seq[MidiDeviceHandle] = manager.inputOpenedDevices

    override def close(manager: MidiManager, id: MidiDeviceId): Unit = manager.closeInput(id)
  }

  private object Output extends Direction {
    override def newDevice(name: String): FakeMidiDevice = FakeMidiDevice(name, maxTransmitters = 0, maxReceivers = -1)

    override def isAvailable(manager: MidiManager, id: MidiDeviceId): Boolean = manager.isOutputAvailable(id)

    override def deviceInfoOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceInfo] =
      manager.outputDeviceInfoOf(id)

    override def deviceIds(manager: MidiManager): Seq[MidiDeviceId] = manager.outputDeviceIds

    override def devicesInfo(manager: MidiManager): Seq[MidiDeviceInfo] = manager.outputDevicesInfo

    override def open(manager: MidiManager, id: MidiDeviceId): MidiDeviceHandle = manager.openOutput(id)

    override def deviceHandleOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceHandle] =
      manager.outputDeviceHandleOf(id)

    override def openedDevices(manager: MidiManager): Seq[MidiDeviceHandle] = manager.outputOpenedDevices

    override def close(manager: MidiManager, id: MidiDeviceId): Unit = manager.closeOutput(id)
  }

  private trait Fixture {
    val businessync: RecordingBusinessync = RecordingBusinessync()
    val environment: FakeJavaMidiEnvironment = FakeJavaMidiEnvironment()

    /** Creates the manager, which scans the devices plugged into [[environment]] so far. */
    def newManager(): JavaMidiManager = JavaMidiManager(businessync, environment)
  }

  /**
   * The behaviours of the half of the API that handles the devices of `direction`.
   */
  private def deviceEndpoint(direction: Direction): Unit = {
    /**
     * A manager over [[device]], a device of `direction`, plugged in before the manager is created unless
     * `isPluggedAtStart` is false.
     */
    abstract class EndpointFixture(isPluggedAtStart: Boolean = true) extends Fixture {
      val device: FakeMidiDevice = direction.newDevice(deviceName)
      val id: MidiDeviceId = device.id

      if (isPluggedAtStart) {
        environment.plug(device)
      }

      val manager: JavaMidiManager = newManager()
    }

    "list a device plugged in at start-up, with its info, and report it as connected" in new EndpointFixture {
      // Then
      direction.isAvailable(manager, id) shouldBe true
      direction.deviceIds(manager) shouldEqual Seq(id)
      direction.devicesInfo(manager) shouldEqual Seq(device.asMidiDeviceInfo)
      direction.deviceInfoOf(manager, id) shouldEqual Some(device.asMidiDeviceInfo)
      businessync.events shouldEqual Seq(MidiDeviceConnectedEvent(id))
    }

    "know nothing of a device that is not plugged in" in new EndpointFixture(isPluggedAtStart = false) {
      // Then
      direction.isAvailable(manager, id) shouldBe false
      direction.deviceIds(manager) shouldBe empty
      direction.devicesInfo(manager) shouldBe empty
      direction.deviceInfoOf(manager, id) shouldBe empty
      direction.deviceHandleOf(manager, id) shouldBe empty
      businessync.events shouldBe empty
    }

    "report a device plugged in after start-up as connected on refresh" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        environment.plug(device)

        // When
        manager.refresh()

        // Then
        direction.isAvailable(manager, id) shouldBe true
        businessync.events shouldEqual Seq(MidiDeviceConnectedEvent(id))
      }

    "report an unplugged device as disconnected on refresh" in new EndpointFixture {
      // Given
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      direction.isAvailable(manager, id) shouldBe false
      direction.deviceIds(manager) shouldBe empty
      businessync.events shouldEqual Seq(MidiDeviceConnectedEvent(id), MidiDeviceDisconnectedEvent(id))
    }

    "open a connected device, handing it to an open handle, and report it as opened" in new EndpointFixture {
      // When
      val handle: MidiDeviceHandle = direction.open(manager, id)

      // Then
      handle.id shouldEqual id
      handle.state shouldEqual State.Open
      handle.info shouldEqual Some(device.asMidiDeviceInfo)
      device.isOpen shouldBe true
      direction.deviceHandleOf(manager, id) shouldEqual Some(handle)
      direction.openedDevices(manager) shouldEqual Seq(handle)
      businessync.events shouldEqual Seq(MidiDeviceConnectedEvent(id), MidiDeviceOpenedEvent(id))
    }

    "return the same handle when a device is opened again" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)

      // When
      val handleOpenedAgain: MidiDeviceHandle = direction.open(manager, id)

      // Then
      handleOpenedAgain should be theSameInstanceAs handle
      direction.openedDevices(manager) shouldEqual Seq(handle)
    }

    // TODO #288 Opening an open device again hands the device over to its handle again, which closes it.
    "keep the device open when it is opened again" ignore new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)

      // When
      direction.open(manager, id)

      // Then
      device.closeCount shouldEqual 0
      device.isOpen shouldBe true
      handle.isOpen shouldBe true
    }

    "return a handle that is not open for a device that is not connected" in
      new EndpointFixture(isPluggedAtStart = false) {
        // When
        val handle: MidiDeviceHandle = direction.open(manager, id)

        // Then
        handle.id shouldEqual id
        handle.isConnected shouldBe false
        handle.isOpen shouldBe false
        businessync.events shouldBe empty
      }

    // TODO #288 openDevice does not open the handle of a device that is not connected, so the handle stays Closed.
    "make the handle of a device that is not connected wait to open" ignore
      new EndpointFixture(isPluggedAtStart = false) {
        // When
        val handle: MidiDeviceHandle = direction.open(manager, id)

        // Then
        handle.state shouldEqual State.WaitingToOpen
      }

    // TODO #288 updateDevices does not hand a newly connected device to the handle already requested for it.
    "open the device of a handle requested before the device got connected, once it gets connected" ignore
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val handle: MidiDeviceHandle = direction.open(manager, id)
        environment.plug(device)

        // When
        manager.refresh()

        // Then
        handle.state shouldEqual State.Open
        device.isOpen shouldBe true
      }

    "close an open device and forget its handle" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)

      // When
      direction.close(manager, id)

      // Then
      device.isOpen shouldBe false
      handle.state shouldEqual State.Connected
      direction.deviceHandleOf(manager, id) shouldBe empty
      direction.openedDevices(manager) shouldBe empty
    }

    // TODO #288 closeDevice releases a single open and then drops the handle, whatever its reference count.
    "keep a device opened twice open, and its handle known, until it is closed twice" ignore new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)
      direction.open(manager, id)

      // When
      direction.close(manager, id)

      // Then
      device.isOpen shouldBe true
      direction.deviceHandleOf(manager, id) shouldEqual Some(handle)

      // When
      direction.close(manager, id)

      // Then
      device.isOpen shouldBe false
      direction.deviceHandleOf(manager, id) shouldBe empty
    }

    "ignore closing a device that is not open" in new EndpointFixture {
      // When
      direction.close(manager, id)

      // Then
      device.closeCount shouldEqual 0
      businessync.events shouldEqual Seq(MidiDeviceConnectedEvent(id))
    }

    // TODO #288 closeDevice neither releases nor removes a handle that is not open.
    "release and forget the handle of a device that is not connected when it is closed" ignore
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val handle: MidiDeviceHandle = direction.open(manager, id)

        // When
        direction.close(manager, id)

        // Then
        handle.state shouldEqual State.Closed
        direction.deviceHandleOf(manager, id) shouldBe empty
        direction.openedDevices(manager) shouldBe empty

        // When
        environment.plug(device)
        manager.refresh()

        // Then
        device.isOpen shouldBe false
      }

    "close an open device that got unplugged and report it as disconnected on refresh" in new EndpointFixture {
      // Given
      direction.open(manager, id)
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      device.isOpen shouldBe false
      businessync.events should contain(MidiDeviceDisconnectedEvent(id))
    }

    // TODO #288 purgeDisconnectedDevices closes the device behind the handle's back and drops the handle, so the
    //   handle's state contradicts isConnected and isOpen.
    "keep the handle of an open device that got unplugged, waiting to open it again" ignore new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      handle.state shouldEqual State.WaitingToOpen
      handle.isConnected shouldBe false
      handle.isOpen shouldBe false
      direction.deviceHandleOf(manager, id) shouldEqual Some(handle)
    }

    // TODO #288 A replugged device never reconnects to the handle that purgeDisconnectedDevices dropped.
    "open the handle of an unplugged device with the device it gets when replugged" ignore new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)
      val repluggedDevice: FakeMidiDevice = direction.newDevice(deviceName)
      environment.unplug(device.getDeviceInfo)
      manager.refresh()
      environment.plug(repluggedDevice)

      // When
      manager.refresh()

      // Then
      handle.state shouldEqual State.Open
      repluggedDevice.isOpen shouldBe true
      direction.openedDevices(manager) shouldEqual Seq(handle)
    }
  }

  "A new JavaMidiManager" should {
    "subscribe to the changes of the environment" in new Fixture {
      // When
      newManager()

      // Then
      environment.subscriberCount shouldEqual 1
    }
  }

  "The input devices" should {
    behave like deviceEndpoint(Input)
  }

  "The output devices" should {
    behave like deviceEndpoint(Output)
  }

  "refresh" should {
    "list each device in the directions its connection limits allow" in {
      // Given
      val cases = Table(
        ("maxTransmitters", "maxReceivers", "isInput", "isOutput"),
        (-1, 0, true, false),
        (0, -1, false, true),
        (-1, -1, true, true),
        (2, 1, true, true),
        (0, 0, false, false)
      )

      forAll(cases) { (maxTransmitters, maxReceivers, isInput, isOutput) =>
        new Fixture {
          // Given
          val device: FakeMidiDevice = FakeMidiDevice(deviceName, maxTransmitters = maxTransmitters,
            maxReceivers = maxReceivers)
          environment.plug(device)

          // When
          val manager: JavaMidiManager = newManager()

          // Then
          manager.isInputAvailable(device.id) shouldBe isInput
          manager.isOutputAvailable(device.id) shouldBe isOutput
        }
      }
    }

    "list the input and the output endpoint of one physical device under the same id" in new Fixture {
      // Given
      val inputDevice: FakeMidiDevice = Input.newDevice(deviceName)
      val outputDevice: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(inputDevice)
      environment.plug(outputDevice)
      val id: MidiDeviceId = inputDevice.id

      // When
      val manager: JavaMidiManager = newManager()

      // Then
      manager.inputDeviceIds shouldEqual Seq(id)
      manager.outputDeviceIds shouldEqual Seq(id)
      businessync.events shouldEqual Seq(MidiDeviceConnectedEvent(id), MidiDeviceConnectedEvent(id))

      // When
      manager.openOutput(id)

      // Then
      outputDevice.isOpen shouldBe true
      inputDevice.isOpen shouldBe false
    }

    "not report a device again while it stays plugged in" in new Fixture {
      // Given
      val device: FakeMidiDevice = Input.newDevice(deviceName)
      environment.plug(device)
      val manager: JavaMidiManager = newManager()

      // When
      manager.refresh()
      manager.refresh()

      // Then
      businessync.events shouldEqual Seq(MidiDeviceConnectedEvent(device.id))
    }

    "keep the device first resolved for an id that stays present" in new Fixture {
      // Given
      val firstDevice: FakeMidiDevice = Output.newDevice(deviceName)
      val laterDevice: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(firstDevice)
      val manager: JavaMidiManager = newManager()
      environment.unplug(firstDevice.getDeviceInfo)
      environment.plug(laterDevice)

      // When
      manager.refresh()
      manager.openOutput(firstDevice.id)

      // Then
      firstDevice.isOpen shouldBe true
      laterDevice.isOpen shouldBe false
    }

    "resolve the device afresh for an id that left and came back" in new Fixture {
      // Given
      val firstDevice: FakeMidiDevice = Output.newDevice(deviceName)
      val laterDevice: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(firstDevice)
      val manager: JavaMidiManager = newManager()
      environment.unplug(firstDevice.getDeviceInfo)
      manager.refresh()
      environment.plug(laterDevice)

      // When
      manager.refresh()
      manager.openOutput(firstDevice.id)

      // Then
      laterDevice.isOpen shouldBe true
      firstDevice.isOpen shouldBe false
    }

    "silently skip a device that is unavailable or that the environment does not know" in {
      // Given
      val failures = Table[Exception](
        "failure",
        MidiUnavailableException("The device is busy"),
        IllegalArgumentException("Unknown device")
      )

      forAll(failures) { failure =>
        new Fixture {
          // Given
          environment.plugUnresolvable(TestDeviceInfo(deviceName, "Roland", "Digital piano", "1.0"), failure)

          // When
          val manager: JavaMidiManager = newManager()

          // Then
          manager.inputDeviceIds shouldBe empty
          manager.outputDeviceIds shouldBe empty
          businessync.events shouldBe empty
        }
      }
    }

    "skip a device that fails to resolve for any other reason and report it as failed to connect" in new Fixture {
      // Given
      val failure: Exception = IllegalStateException("CoreMIDI failure")
      val javaInfo: TestDeviceInfo = TestDeviceInfo(deviceName, "Roland", "Digital piano", "1.0")
      environment.plugUnresolvable(javaInfo, failure)

      // When
      val manager: JavaMidiManager = newManager()

      // Then
      manager.inputDeviceIds shouldBe empty
      manager.outputDeviceIds shouldBe empty
      businessync.events shouldEqual Seq(MidiDeviceFailedToConnectEvent(javaInfo.asMidiDeviceId, failure))
    }
  }

  "An environment change" should {
    "be reported as MidiEnvironmentChangedEvent and followed by a refresh" in new Fixture {
      // Given
      val manager: JavaMidiManager = newManager()
      val device: FakeMidiDevice = Input.newDevice(deviceName)
      environment.plug(device)

      // When
      environment.notifyChanged()

      // Then
      manager.isInputAvailable(device.id) shouldBe true
      businessync.events shouldEqual Seq(MidiEnvironmentChangedEvent, MidiDeviceConnectedEvent(device.id))
    }
  }

  "close" should {
    "close every open device and stop watching the environment" in new Fixture {
      // Given
      val inputDevice: FakeMidiDevice = Input.newDevice("CoreMIDI4J - Seaboard")
      val outputDevice: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(inputDevice)
      environment.plug(outputDevice)
      val manager: JavaMidiManager = newManager()
      manager.openInput(inputDevice.id)
      manager.openOutput(outputDevice.id)

      // When
      manager.close()

      // Then
      inputDevice.isOpen shouldBe false
      outputDevice.isOpen shouldBe false
      manager.inputOpenedDevices shouldBe empty
      manager.outputOpenedDevices shouldBe empty
      environment.subscriberCount shouldEqual 0
    }

    // TODO #288 closeDevice releases a single open of a handle, so a device opened more than once is left open.
    "close a device opened more than once" ignore new Fixture {
      // Given
      val device: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(device)
      val manager: JavaMidiManager = newManager()
      manager.openOutput(device.id)
      manager.openOutput(device.id)

      // When
      manager.close()

      // Then
      device.isOpen shouldBe false
      manager.outputOpenedDevices shouldBe empty
    }
  }

  "Logging" should {
    val loggerName = classOf[JavaMidiManager].getName

    "report each connected device at debug level, with the connection limit of its direction" in new Fixture {
      // Given
      environment.plug(FakeMidiDevice("CoreMIDI4J - Seaboard", "ROLI", maxTransmitters = 2, maxReceivers = 0))
      environment.plug(FakeMidiDevice(deviceName, maxTransmitters = 0, maxReceivers = -1))

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        newManager()
      }

      // Then
      events.messagesAt(Level.DEBUG) shouldEqual Seq(
        """Input device "CoreMIDI4J - Seaboard" (ROLI) with 2 transmitters was connected.""",
        """Output device "CoreMIDI4J - FP-90" (Roland) with unlimited receivers was connected."""
      )
    }

    "report a device that fails to resolve at error level, with the failure" in new Fixture {
      // Given
      environment.plugUnresolvable(TestDeviceInfo(deviceName, "Roland", "Digital piano", "1.0"),
        IllegalStateException("CoreMIDI failure"))

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        newManager()
      }

      // Then
      events.failuresAt(Level.ERROR) shouldEqual
        Seq(("""Failed to connect to device "CoreMIDI4J - FP-90" (Roland)!""", Some("CoreMIDI failure")))
    }

    "warn that a device to open is not connected" in new Fixture {
      // Given
      val manager: JavaMidiManager = newManager()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        manager.openOutput(MidiDeviceId(deviceName, "Roland"))
      }

      // Then
      events.messagesAt(Level.WARN) shouldEqual
        Seq("""Output device "CoreMIDI4J - FP-90" (Roland) is not connected.""")
    }

    "report the opening and the closing of devices at info level" in new Fixture {
      // Given
      val device: FakeMidiDevice = Input.newDevice(deviceName)
      environment.plug(device)
      val manager: JavaMidiManager = newManager()
      val expectedFragments: Seq[String] = Seq("opened input device", "Closing MIDI connections",
        "Closing input device", "closed device", "Finished closing MIDI connections")

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        manager.openInput(device.id)
        manager.close()
      }

      // Then
      val messages = events.messagesAt(Level.INFO)
      messages should have size expectedFragments.size
      messages.zip(expectedFragments).foreach { case (message, fragment) => message should include(fragment) }
    }

    "report an environment change and the disconnection of a device at info level" in new Fixture {
      // Given
      val device: FakeMidiDevice = Input.newDevice(deviceName)
      environment.plug(device)
      newManager()
      environment.unplug(device.getDeviceInfo)

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        environment.notifyChanged()
      }

      // Then
      events.messagesAt(Level.INFO) shouldEqual Seq(
        "The MIDI environment has changed.",
        s"Input device ${device.id} was disconnected."
      )
    }
  }
}
