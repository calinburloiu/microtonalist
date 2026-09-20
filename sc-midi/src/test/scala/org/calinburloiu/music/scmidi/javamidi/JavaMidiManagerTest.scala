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
import org.calinburloiu.businessync.{Businessync, BusinessyncEvent}
import org.calinburloiu.music.microtonalist.common.LogCapture
import org.calinburloiu.music.microtonalist.common.LogCapture.*
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CompletableFuture, TimeUnit}
import javax.sound.midi.MidiUnavailableException
import scala.collection.mutable

/**
 * Tests [[JavaMidiManager]] over a [[FakeJavaMidiEnvironment]].
 *
 * The manager keeps inputs and outputs apart, behind two mirrored halves of its API; the behaviours of each half are
 * shared (see [[deviceEndpoint]]) and run once per [[Endpoint]]. The other sections cover what is common to both.
 */
class JavaMidiManagerTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks with Stubs {

  private val deviceName: String = "CoreMIDI4J - FP-90"

  /** The Java Sound information of a device that a test plugs in without a device behind it. */
  private val javaDeviceInfo: TestDeviceInfo = TestDeviceInfo(deviceName, "Roland", "Digital piano", "1.0")

  /** One endpoint of the [[MidiManager]] API, so that the same behaviours run for inputs and for outputs. */
  private trait Endpoint {
    /** The [[MidiDirection]] that events about a device of this endpoint carry. */
    val direction: MidiDirection

    /** Creates a device that works in this endpoint's direction only. */
    def newDevice(name: String): FakeMidiDevice

    def isAvailable(manager: MidiManager, id: MidiDeviceId): Boolean

    def deviceInfoOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceInfo]

    def deviceIds(manager: MidiManager): Seq[MidiDeviceId]

    def devicesInfo(manager: MidiManager): Seq[MidiDeviceInfo]

    def open(manager: MidiManager, id: MidiDeviceId): MidiDeviceHandle

    def deviceHandleOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceHandle]

    def openDevices(manager: MidiManager): Seq[MidiDeviceHandle]

    def devicesRequestedToOpen(manager: MidiManager): Seq[MidiDeviceHandle]

    def close(manager: MidiManager, id: MidiDeviceId): Unit
  }

  private object Input extends Endpoint {
    override val direction: MidiDirection = MidiDirection.Input

    override def newDevice(name: String): FakeMidiDevice = FakeMidiDevice(name, maxTransmitters = -1, maxReceivers = 0)

    override def isAvailable(manager: MidiManager, id: MidiDeviceId): Boolean = manager.isInputAvailable(id)

    override def deviceInfoOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceInfo] =
      manager.inputDeviceInfoOf(id)

    override def deviceIds(manager: MidiManager): Seq[MidiDeviceId] = manager.inputDeviceIds

    override def devicesInfo(manager: MidiManager): Seq[MidiDeviceInfo] = manager.inputDevicesInfo

    override def open(manager: MidiManager, id: MidiDeviceId): MidiDeviceHandle = manager.openInput(id)

    override def deviceHandleOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceHandle] =
      manager.inputDeviceHandleOf(id)

    override def openDevices(manager: MidiManager): Seq[MidiDeviceHandle] = manager.inputOpenDevices

    override def devicesRequestedToOpen(manager: MidiManager): Seq[MidiDeviceHandle] =
      manager.inputDevicesRequestedToOpen

    override def close(manager: MidiManager, id: MidiDeviceId): Unit = manager.closeInput(id)
  }

  private object Output extends Endpoint {
    override val direction: MidiDirection = MidiDirection.Output

    override def newDevice(name: String): FakeMidiDevice = FakeMidiDevice(name, maxTransmitters = 0, maxReceivers = -1)

    override def isAvailable(manager: MidiManager, id: MidiDeviceId): Boolean = manager.isOutputAvailable(id)

    override def deviceInfoOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceInfo] =
      manager.outputDeviceInfoOf(id)

    override def deviceIds(manager: MidiManager): Seq[MidiDeviceId] = manager.outputDeviceIds

    override def devicesInfo(manager: MidiManager): Seq[MidiDeviceInfo] = manager.outputDevicesInfo

    override def open(manager: MidiManager, id: MidiDeviceId): MidiDeviceHandle = manager.openOutput(id)

    override def deviceHandleOf(manager: MidiManager, id: MidiDeviceId): Option[MidiDeviceHandle] =
      manager.outputDeviceHandleOf(id)

    override def openDevices(manager: MidiManager): Seq[MidiDeviceHandle] = manager.outputOpenDevices

    override def devicesRequestedToOpen(manager: MidiManager): Seq[MidiDeviceHandle] =
      manager.outputDevicesRequestedToOpen

    override def close(manager: MidiManager, id: MidiDeviceId): Unit = manager.closeOutput(id)
  }

  private trait Fixture {
    /** What the bus does with each published event, besides recording it; a test may replace it. */
    var onPublish: BusinessyncEvent => Unit = _ => ()

    val businessync: Stub[Businessync] = stub[Businessync]
    businessync.publish.returns(event => onPublish(event))

    val environment: FakeJavaMidiEnvironment = FakeJavaMidiEnvironment()

    /** Creates the manager, which scans the devices plugged into [[environment]] so far. */
    def newManager(): JavaMidiManager = JavaMidiManager(businessync, environment)
  }

  /**
   * The behaviours of the half of the API that handles the devices of `endpoint`.
   */
  private def deviceEndpoint(endpoint: Endpoint): Unit = {
    /** Shorthand for the [[MidiDirection]] that events about a device of `endpoint` carry. */
    val et: MidiDirection = endpoint.direction

    /**
     * A manager over [[device]], a device of `endpoint`, plugged in before the manager is created unless
     * `isPluggedAtStart` is false.
     */
    abstract class EndpointFixture(isPluggedAtStart: Boolean = true) extends Fixture {
      val device: FakeMidiDevice = endpoint.newDevice(deviceName)
      val id: MidiDeviceId = device.id

      if (isPluggedAtStart) {
        environment.plug(device)
      }

      val manager: JavaMidiManager = newManager()
    }

    "list a device plugged in at start-up, with its info and a live handle in Connected, and report it as connected" in
      new EndpointFixture {
        // Then
        endpoint.isAvailable(manager, id) shouldBe true
        endpoint.deviceIds(manager) shouldEqual Seq(id)
        endpoint.devicesInfo(manager) shouldEqual Seq(device.asMidiDeviceInfo)
        endpoint.deviceInfoOf(manager, id) shouldEqual Some(device.asMidiDeviceInfo)
        endpoint.deviceHandleOf(manager, id).map(_.state) shouldEqual Some(State.Connected)
        endpoint.openDevices(manager) shouldBe empty
        endpoint.devicesRequestedToOpen(manager) shouldBe empty
        businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et))
      }

    "know nothing of a device that is not plugged in" in new EndpointFixture(isPluggedAtStart = false) {
      // Then
      endpoint.isAvailable(manager, id) shouldBe false
      endpoint.deviceIds(manager) shouldBe empty
      endpoint.devicesInfo(manager) shouldBe empty
      endpoint.deviceInfoOf(manager, id) shouldBe empty
      endpoint.deviceHandleOf(manager, id) shouldBe empty
      businessync.publish.calls shouldBe empty
    }

    "report a device plugged in after start-up as connected on refresh" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        environment.plug(device)

        // When
        manager.refresh()

        // Then
        endpoint.isAvailable(manager, id) shouldBe true
        businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et))
      }

    "report an unplugged device as disconnected on refresh, and forget its handle" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = endpoint.deviceHandleOf(manager, id).get
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      endpoint.isAvailable(manager, id) shouldBe false
      endpoint.deviceIds(manager) shouldBe empty
      endpoint.deviceHandleOf(manager, id) shouldBe empty
      handle.state shouldEqual State.Closed
      businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceDisconnectedEvent(id, et))
    }

    "open a connected device, handing it to an open handle, and report it as opened" in new EndpointFixture {
      // When
      val handle: MidiDeviceHandle = endpoint.open(manager, id)

      // Then
      handle.id shouldEqual id
      handle.state shouldEqual State.Open
      handle.info shouldEqual Some(device.asMidiDeviceInfo)
      device.isOpen shouldBe true
      endpoint.deviceHandleOf(manager, id) shouldEqual Some(handle)
      endpoint.openDevices(manager) shouldEqual Seq(handle)
      endpoint.devicesRequestedToOpen(manager) shouldEqual Seq(handle)
      businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et))
    }

    "report a device that fails to open, keeping its handle live and requested to open by nobody" in
      new EndpointFixture {
        // Given
        val failure: Exception = MidiUnavailableException("The device is busy")
        device.openFailure = Some(failure)

        // When
        val handle: MidiDeviceHandle = endpoint.open(manager, id)

        // Then
        handle.state shouldEqual State.Connected
        handle.isOpenRequested shouldBe false
        device.isOpen shouldBe false
        endpoint.openDevices(manager) shouldBe empty
        endpoint.devicesRequestedToOpen(manager) shouldBe empty
        // The handle stays live and listed, the device being still connected, so opening it again can retry
        endpoint.deviceHandleOf(manager, id) shouldEqual Some(handle)
        businessync.publish.calls shouldEqual
          Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceFailedToOpenEvent(id, et, failure))
      }

    "return the same handle when a device is opened again" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = endpoint.open(manager, id)

      // When
      val handleOpenedAgain: MidiDeviceHandle = endpoint.open(manager, id)

      // Then
      handleOpenedAgain should be theSameInstanceAs handle
      endpoint.devicesRequestedToOpen(manager) shouldEqual Seq(handle)
    }

    "keep the device open when it is opened again" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = endpoint.open(manager, id)

      // When
      endpoint.open(manager, id)

      // Then
      device.closeCount shouldEqual 0
      device.isOpen shouldBe true
      handle.isOpen shouldBe true
    }

    "make the handle of a device that is not connected wait to open, without reporting anything" in
      new EndpointFixture(isPluggedAtStart = false) {
        // When
        val handle: MidiDeviceHandle = endpoint.open(manager, id)

        // Then
        handle.id shouldEqual id
        handle.state shouldEqual State.WaitingToOpen
        handle.isConnected shouldBe false
        handle.isOpen shouldBe false
        endpoint.deviceHandleOf(manager, id) shouldEqual Some(handle)
        endpoint.devicesRequestedToOpen(manager) shouldEqual Seq(handle)
        endpoint.isAvailable(manager, id) shouldBe false
        businessync.publish.calls shouldBe empty
      }

    "open the device of a handle requested before the device got connected, once it gets connected" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val handle: MidiDeviceHandle = endpoint.open(manager, id)
        environment.plug(device)

        // When
        manager.refresh()

        // Then
        handle.state shouldEqual State.Open
        device.isOpen shouldBe true
        businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et))
      }

    "close an open device, keeping its handle live in Connected but no longer listed as opened" in
      new EndpointFixture {
        // Given
        val handle: MidiDeviceHandle = endpoint.open(manager, id)

        // When
        endpoint.close(manager, id)

        // Then
        device.isOpen shouldBe false
        handle.state shouldEqual State.Connected
        endpoint.deviceHandleOf(manager, id) shouldEqual Some(handle)
        endpoint.devicesRequestedToOpen(manager) shouldBe empty
        businessync.publish.calls shouldEqual
          Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et), MidiDeviceClosedEvent(id, et))
      }

    "keep a device opened twice open until it is closed twice" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = endpoint.open(manager, id)
      endpoint.open(manager, id)

      // When
      endpoint.close(manager, id)

      // Then
      device.isOpen shouldBe true
      endpoint.devicesRequestedToOpen(manager) shouldEqual Seq(handle)

      // When
      endpoint.close(manager, id)

      // Then
      device.isOpen shouldBe false
      handle.state shouldEqual State.Connected
      endpoint.devicesRequestedToOpen(manager) shouldBe empty
    }

    "ignore closing a device that is not open" in new EndpointFixture {
      // When
      endpoint.close(manager, id)

      // Then
      device.closeCount shouldEqual 0
      businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et))
    }

    "release and forget the handle of a device that is not connected when it is closed" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val handle: MidiDeviceHandle = endpoint.open(manager, id)

        // When
        endpoint.close(manager, id)

        // Then
        handle.state shouldEqual State.Closed
        endpoint.deviceHandleOf(manager, id) shouldBe empty
        endpoint.devicesRequestedToOpen(manager) shouldBe empty

        // When
        environment.plug(device)
        manager.refresh()

        // Then
        device.isOpen shouldBe false
      }

    "return a new handle for a device requested again after its handle was forgotten" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val forgottenHandle: MidiDeviceHandle = endpoint.open(manager, id)
        endpoint.close(manager, id)

        // When
        val handle: MidiDeviceHandle = endpoint.open(manager, id)

        // Then
        handle should not be theSameInstanceAs(forgottenHandle)
        forgottenHandle.state shouldEqual State.Closed
        handle.state shouldEqual State.WaitingToOpen
      }

    "close an open device that got unplugged, keeping its handle waiting to open it again" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = endpoint.open(manager, id)
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      device.isOpen shouldBe false
      handle.state shouldEqual State.WaitingToOpen
      endpoint.deviceHandleOf(manager, id) shouldEqual Some(handle)
      endpoint.openDevices(manager) shouldBe empty
      endpoint.devicesRequestedToOpen(manager) shouldEqual Seq(handle)
      endpoint.isAvailable(manager, id) shouldBe false
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceConnectedEvent(id, et),
        MidiDeviceOpenedEvent(id, et),
        MidiDeviceClosedEvent(id, et),
        MidiDeviceDisconnectedEvent(id, et)
      )
    }

    "open the handle of an unplugged device with the device it gets when replugged" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = endpoint.open(manager, id)
      val repluggedDevice: FakeMidiDevice = endpoint.newDevice(deviceName)
      environment.unplug(device.getDeviceInfo)
      manager.refresh()
      environment.plug(repluggedDevice)

      // When
      manager.refresh()

      // Then
      handle.state shouldEqual State.Open
      repluggedDevice.isOpen shouldBe true
      endpoint.devicesRequestedToOpen(manager) shouldEqual Seq(handle)
      businessync.publish.calls.drop(4) shouldEqual
        Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et))
    }

    "close the device of an open handle and open the one it got swapped for between two refreshes" in
      new EndpointFixture {
        // Given
        val handle: MidiDeviceHandle = endpoint.open(manager, id)
        val swappedDevice: FakeMidiDevice = endpoint.newDevice(deviceName)
        environment.unplug(device.getDeviceInfo)
        // CoreMIDI4J closes the instance of a vanished endpoint before it notifies the change
        device.close()
        environment.plug(swappedDevice)

        // When
        manager.refresh()

        // Then
        handle.state shouldEqual State.Open
        device.isOpen shouldBe false
        swappedDevice.isOpen shouldBe true
        businessync.publish.calls shouldEqual Seq(
          MidiDeviceConnectedEvent(id, et),
          MidiDeviceOpenedEvent(id, et),
          MidiDeviceClosedEvent(id, et),
          MidiDeviceOpenedEvent(id, et)
        )
      }

    "keep an open device open, reporting nothing, on a refresh that finds the same instance of it" in
      new EndpointFixture {
        // Given
        val handle: MidiDeviceHandle = endpoint.open(manager, id)

        // When
        manager.refresh()

        // Then
        handle.state shouldEqual State.Open
        device.isOpen shouldBe true
        device.closeCount shouldEqual 0
        businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et))
      }

    "keep an open device that resolves to a new instance on every lookup open, reporting nothing, on a refresh" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        // A JDK Sequencer or Synthesizer provider builds a new instance on every lookup
        val instances: mutable.Buffer[FakeMidiDevice] = mutable.ArrayBuffer()
        environment.plugResolvingAfresh(device.getDeviceInfo, () => {
          val instance = endpoint.newDevice(deviceName)
          instances += instance
          instance
        })
        manager.refresh()
        val handle: MidiDeviceHandle = endpoint.open(manager, id)
        val heldInstance: FakeMidiDevice = instances.last

        // When
        manager.refresh()

        // Then
        instances should have size 2
        handle.state shouldEqual State.Open
        heldInstance.isOpen shouldBe true
        heldInstance.closeCount shouldEqual 0
        instances.last.openCount shouldEqual 0
        businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et))
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
    "serialise concurrent refreshes, so that one never scans the environment while another is refreshing" in
      new Fixture {
        // Given
        val manager: JavaMidiManager = newManager()
        val scansInFlight: AtomicInteger = AtomicInteger()
        val mostScansInFlight: AtomicInteger = AtomicInteger()
        // Each scan lingers, so that a second refresh allowed to run alongside would be caught in the act. Serialised
        // refreshes can never overlap, whatever the timing, so the assertion below cannot fail spuriously.
        environment.onScan = () => {
          mostScansInFlight.accumulateAndGet(scansInFlight.incrementAndGet(), Math.max)
          Thread.sleep(100)
          scansInFlight.decrementAndGet()
        }

        // When
        val refreshes: Seq[CompletableFuture[Void]] =
          Seq.fill(2)(CompletableFuture.runAsync(() => manager.refresh()))

        // Then
        refreshes.foreach(_.get(5, TimeUnit.SECONDS))
        mostScansInFlight.get shouldEqual 1
      }

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

    "list the input and the output endpoint of one physical device under the same id, reporting each direction" in
      new Fixture {
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
        businessync.publish.calls shouldEqual Seq(
          MidiDeviceConnectedEvent(id, MidiDirection.Input),
          MidiDeviceConnectedEvent(id, MidiDirection.Output)
        )

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
      businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(device.id, MidiDirection.Input))
    }

    "replace the device resolved earlier for an id that stays present, without reporting it as connected again" in
      new Fixture {
        // Given
        val firstDevice: FakeMidiDevice = Output.newDevice(deviceName)
        val laterDevice: FakeMidiDevice = Output.newDevice(deviceName)
        val id: MidiDeviceId = firstDevice.id
        environment.plug(firstDevice)
        val manager: JavaMidiManager = newManager()
        environment.unplug(firstDevice.getDeviceInfo)
        environment.plug(laterDevice)

        // When
        manager.refresh()
        manager.openOutput(id)

        // Then
        laterDevice.isOpen shouldBe true
        firstDevice.isOpen shouldBe false
        businessync.publish.calls shouldEqual Seq(
          MidiDeviceConnectedEvent(id, MidiDirection.Output), MidiDeviceOpenedEvent(id, MidiDirection.Output)
        )
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

    "keep the device resolved last when the environment lists an id twice" in new Fixture {
      // Given
      val firstDevice: FakeMidiDevice = Output.newDevice(deviceName)
      val lastDevice: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(firstDevice)
      environment.plug(lastDevice)
      val manager: JavaMidiManager = newManager()

      // When
      manager.openOutput(firstDevice.id)

      // Then
      lastDevice.isOpen shouldBe true
      firstDevice.isOpen shouldBe false
      manager.outputDeviceIds shouldEqual Seq(firstDevice.id)
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceConnectedEvent(firstDevice.id, MidiDirection.Output),
        MidiDeviceOpenedEvent(firstDevice.id, MidiDirection.Output)
      )
    }

    "silently skip a device that is unavailable or that is gone by the time it is resolved" in {
      // Given
      val failures = Table[Exception](
        "failure",
        // The device is there, but another application holds its resources.
        MidiUnavailableException("The device is busy"),
        // The device was unplugged between the listing and the resolution, so its info no longer describes an
        // installed device, which is what MidiSystem.getMidiDevice reports this way.
        IllegalArgumentException("Unknown device")
      )

      forAll(failures) { failure =>
        new Fixture {
          // Given
          environment.plugUnresolvable(javaDeviceInfo, failure)

          // When
          val manager: JavaMidiManager = newManager()

          // Then
          manager.inputDeviceIds shouldBe empty
          manager.outputDeviceIds shouldBe empty
          businessync.publish.calls shouldBe empty
        }
      }
    }

    "skip a device that fails to resolve for any other reason and report it as failed to connect" in new Fixture {
      // Given
      val failure: Exception = IllegalStateException("CoreMIDI failure")
      environment.plugUnresolvable(javaDeviceInfo, failure)

      // When
      val manager: JavaMidiManager = newManager()

      // Then
      manager.inputDeviceIds shouldBe empty
      manager.outputDeviceIds shouldBe empty
      businessync.publish.calls shouldEqual Seq(MidiDeviceFailedToConnectEvent(javaDeviceInfo.asMidiDeviceId, failure))
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
      businessync.publish.calls shouldEqual
        Seq(MidiEnvironmentChangedEvent, MidiDeviceConnectedEvent(device.id, MidiDirection.Input))
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
      manager.inputDevicesRequestedToOpen shouldBe empty
      manager.outputDevicesRequestedToOpen shouldBe empty
      environment.subscriberCount shouldEqual 0
    }

    "close a device opened more than once" in new Fixture {
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
      manager.outputDevicesRequestedToOpen shouldBe empty
    }

    "forget the handle of a device waiting to open" in new Fixture {
      // Given
      val manager: JavaMidiManager = newManager()
      val handle: MidiDeviceHandle = manager.openOutput(MidiDeviceId(deviceName, "Roland"))

      // When
      manager.close()

      // Then
      handle.state shouldEqual State.Closed
      manager.outputDeviceHandleOf(handle.id) shouldBe empty
    }
  }

  "Publishing" should {
    "publish the events of an operation in order once it completes, outside the lock of the manager" in new Fixture {
      // Given
      val device: FakeMidiDevice = Output.newDevice(deviceName)
      val manager: JavaMidiManager = newManager()
      manager.openOutput(device.id)
      environment.plug(device)
      // What a subscriber on another thread sees of the handle when each event reaches it; with the lock still held,
      // the lookup would time out.
      val observedStates: mutable.Buffer[Option[State]] = mutable.ArrayBuffer()
      onPublish = _ => observedStates += CompletableFuture
        .supplyAsync(() => manager.outputDeviceHandleOf(device.id).map(_.state))
        .get(5, TimeUnit.SECONDS)

      // When
      manager.refresh()

      // Then
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceConnectedEvent(device.id, MidiDirection.Output),
        MidiDeviceOpenedEvent(device.id, MidiDirection.Output)
      )
      observedStates shouldEqual Seq(Some(State.Open), Some(State.Open))
    }
  }

  "Logging" should {
    val loggerName = classOf[JavaMidiManager].getName

    "report a device that fails to resolve at error level, with the failure" in new Fixture {
      // Given
      environment.plugUnresolvable(javaDeviceInfo, IllegalStateException("CoreMIDI failure"))

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        newManager()
      }

      // Then
      events.failuresAt(Level.ERROR) shouldEqual
        Seq(("""Failed to connect to device "CoreMIDI4J - FP-90" (Roland)!""", Some("CoreMIDI failure")))
    }

    "warn that two devices of a direction share an id, only the last resolved being used" in new Fixture {
      // Given
      environment.plug(Output.newDevice(deviceName))
      environment.plug(Output.newDevice(deviceName))

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        newManager()
      }

      // Then
      events.messagesAt(Level.WARN) shouldEqual
        Seq("""Found more than one output device with id "CoreMIDI4J - FP-90" (Roland); only the last one resolved """ +
          "is used and the others are ignored. Rename one of them in the MIDI setup of the operating system to tell " +
          "them apart.")
    }

    "warn that a device to open is not connected and will be opened once it gets connected" in new Fixture {
      // Given
      val manager: JavaMidiManager = newManager()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        manager.openOutput(MidiDeviceId(deviceName, "Roland"))
      }

      // Then
      events.messagesAt(Level.WARN) shouldEqual
        Seq("""Output device "CoreMIDI4J - FP-90" (Roland) is not connected; it will be opened once it gets """ +
          "connected.")
    }

    "report the closing of the MIDI connections at info level" in new Fixture {
      // Given
      val device: FakeMidiDevice = Input.newDevice(deviceName)
      environment.plug(device)
      val manager: JavaMidiManager = newManager()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        manager.openInput(device.id)
        manager.close()
      }

      // Then
      events.messagesAt(Level.INFO) shouldEqual Seq("Closing MIDI connections...", "Finished closing MIDI connections.")
    }

    "report an environment change at info level" in new Fixture {
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
      events.messagesAt(Level.INFO) shouldEqual Seq("The MIDI environment has changed.")
    }
  }
}
