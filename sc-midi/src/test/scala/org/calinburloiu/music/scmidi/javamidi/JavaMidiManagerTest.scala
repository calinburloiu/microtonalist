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
import org.calinburloiu.music.scmidi.message.NoteOnMidiMsg
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import javax.sound.midi.MidiUnavailableException
import scala.collection.mutable

/**
 * Tests [[JavaMidiManager]] over a [[FakeJavaMidiEnvironment]].
 *
 * The manager keeps inputs and outputs apart, in two endpoints that its methods address by direction; the behaviours of
 * an endpoint are shared (see [[deviceEndpoint]]) and run once per [[Endpoint]]. The other sections cover what is
 * common to both, including the directions that address neither.
 */
class JavaMidiManagerTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks with Stubs {

  private val deviceName: String = "CoreMIDI4J - FP-90"

  /** How long a test waits for something that should happen at once; only a broken manager ever reaches it. */
  private val AwaitTimeoutMillis: Long = 5000

  /**
   * How long a scan stays open for another to join it. Only a manager that lets refreshes overlap fills it; a
   * correct one pays it once, so it is kept short.
   */
  private val ScanOverlapWindowMillis: Long = 25

  /** The Java Sound information of a device that a test plugs in without a device behind it. */
  private val javaDeviceInfo: TestDeviceInfo = TestDeviceInfo(deviceName, "Roland", "Digital piano", "1.0")

  /** One endpoint of the [[MidiManager]], so that the same behaviours run for inputs and for outputs. */
  private trait Endpoint {
    /** The [[MidiDirection]] that addresses this endpoint, and that the events about its devices carry. */
    val direction: MidiDirection

    /** Creates a device that works in this endpoint's direction only. */
    def newDevice(name: String): FakeMidiDevice
  }

  private object Input extends Endpoint {
    override val direction: MidiDirection = MidiDirection.Input

    override def newDevice(name: String): FakeMidiDevice = FakeMidiDevice(name, maxTransmitters = -1, maxReceivers = 0)
  }

  private object Output extends Endpoint {
    override val direction: MidiDirection = MidiDirection.Output

    override def newDevice(name: String): FakeMidiDevice = FakeMidiDevice(name, maxTransmitters = 0, maxReceivers = -1)
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

  /** The behaviours of the manager for the devices of `endpoint`. */
  private def deviceEndpoint(endpoint: Endpoint): Unit = {
    /** The [[MidiDirection]] that addresses `endpoint`, and that the events about its devices carry. */
    val direction: MidiDirection = endpoint.direction

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

    "list a device plugged in at start-up, with its info and a live handle in Available, and report it as available" in
      new EndpointFixture {
        // Then
        manager.isDeviceAvailable(id, direction) shouldBe true
        manager.deviceIdsFor(direction) shouldEqual Seq(id)
        manager.devicesInfoFor(direction) shouldEqual Seq(device.asMidiDeviceInfo)
        manager.deviceInfoOf(id, direction) shouldEqual Some(device.asMidiDeviceInfo)
        manager.deviceOf(id, direction).map(_.state) shouldEqual Some(State.Available)
        manager.openDevicesFor(direction) shouldBe empty
        manager.devicesRequestedToOpenFor(direction) shouldBe empty
        businessync.publish.calls shouldEqual Seq(MidiDeviceAvailableEvent(id, direction))
      }

    "know nothing of a device that is not plugged in" in new EndpointFixture(isPluggedAtStart = false) {
      // Then
      manager.isDeviceAvailable(id, direction) shouldBe false
      manager.deviceIdsFor(direction) shouldBe empty
      manager.devicesInfoFor(direction) shouldBe empty
      manager.deviceInfoOf(id, direction) shouldBe empty
      manager.deviceOf(id, direction) shouldBe empty
      businessync.publish.calls shouldBe empty
    }

    "report a device plugged in after start-up as available on refresh" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        environment.plug(device)

        // When
        manager.refresh()

        // Then
        manager.isDeviceAvailable(id, direction) shouldBe true
        businessync.publish.calls shouldEqual Seq(MidiDeviceAvailableEvent(id, direction))
      }

    "report an unplugged device as unavailable on refresh, and forget its handle" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = manager.deviceOf(id, direction).get
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      manager.isDeviceAvailable(id, direction) shouldBe false
      manager.deviceIdsFor(direction) shouldBe empty
      manager.deviceOf(id, direction) shouldBe empty
      handle.state shouldEqual State.Closed
      businessync.publish.calls shouldEqual
        Seq(MidiDeviceAvailableEvent(id, direction), MidiDeviceUnavailableEvent(id, direction))
    }

    "open an available device, handing it to an open handle, and report it as opened" in new EndpointFixture {
      // When
      val handle: MidiDeviceHandle = manager.openDevice(id, direction)

      // Then
      handle.id shouldEqual id
      handle.state shouldEqual State.Open
      handle.info shouldEqual Some(device.asMidiDeviceInfo)
      device.isOpen shouldBe true
      manager.deviceOf(id, direction) shouldEqual Some(handle)
      manager.openDevicesFor(direction) shouldEqual Seq(handle)
      manager.devicesRequestedToOpenFor(direction) shouldEqual Seq(handle)
      businessync.publish.calls shouldEqual
        Seq(MidiDeviceAvailableEvent(id, direction), MidiDeviceOpenedEvent(id, direction))
    }

    "report a device that fails to open, keeping its handle live and requested to open by nobody" in
      new EndpointFixture {
        // Given
        val failure: Exception = MidiUnavailableException("The device is busy")
        device.openFailure = Some(failure)

        // When
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)

        // Then
        handle.state shouldEqual State.Available
        handle.isOpenRequested shouldBe false
        device.isOpen shouldBe false
        manager.openDevicesFor(direction) shouldBe empty
        manager.devicesRequestedToOpenFor(direction) shouldBe empty
        // The handle stays live and listed, the device being still available, so opening it again can retry
        manager.deviceOf(id, direction) shouldEqual Some(handle)
        businessync.publish.calls shouldEqual
          Seq(MidiDeviceAvailableEvent(id, direction), MidiDeviceFailedToOpenEvent(id, direction, failure))
      }

    "return the same handle when a device is opened again" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = manager.openDevice(id, direction)

      // When
      val handleOpenedAgain: MidiDeviceHandle = manager.openDevice(id, direction)

      // Then
      handleOpenedAgain should be theSameInstanceAs handle
      manager.devicesRequestedToOpenFor(direction) shouldEqual Seq(handle)
    }

    "keep the device open when it is opened again" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = manager.openDevice(id, direction)

      // When
      manager.openDevice(id, direction)

      // Then
      device.closeCount shouldEqual 0
      device.isOpen shouldBe true
      handle.isOpen shouldBe true
    }

    "make the handle of a device that is not available wait to open, without reporting anything" in
      new EndpointFixture(isPluggedAtStart = false) {
        // When
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)

        // Then
        handle.id shouldEqual id
        handle.state shouldEqual State.WaitingToOpen
        handle.isAvailable shouldBe false
        handle.isOpen shouldBe false
        manager.deviceOf(id, direction) shouldEqual Some(handle)
        manager.devicesRequestedToOpenFor(direction) shouldEqual Seq(handle)
        manager.isDeviceAvailable(id, direction) shouldBe false
        businessync.publish.calls shouldBe empty
      }

    "open the device of a handle requested before the device became available, once it becomes available" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)
        environment.plug(device)

        // When
        manager.refresh()

        // Then
        handle.state shouldEqual State.Open
        device.isOpen shouldBe true
        businessync.publish.calls shouldEqual
          Seq(MidiDeviceAvailableEvent(id, direction), MidiDeviceOpenedEvent(id, direction))
      }

    "close an open device, keeping its handle live in Available but no longer listed as opened" in
      new EndpointFixture {
        // Given
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)

        // When
        manager.closeDevice(id, direction)

        // Then
        device.isOpen shouldBe false
        handle.state shouldEqual State.Available
        manager.deviceOf(id, direction) shouldEqual Some(handle)
        manager.devicesRequestedToOpenFor(direction) shouldBe empty
        businessync.publish.calls shouldEqual Seq(
          MidiDeviceAvailableEvent(id, direction),
          MidiDeviceOpenedEvent(id, direction),
          MidiDeviceClosedEvent(id, direction)
        )
      }

    "keep a device opened twice open until it is closed twice" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = manager.openDevice(id, direction)
      manager.openDevice(id, direction)

      // When
      manager.closeDevice(id, direction)

      // Then
      device.isOpen shouldBe true
      manager.devicesRequestedToOpenFor(direction) shouldEqual Seq(handle)

      // When
      manager.closeDevice(id, direction)

      // Then
      device.isOpen shouldBe false
      handle.state shouldEqual State.Available
      manager.devicesRequestedToOpenFor(direction) shouldBe empty
    }

    "ignore closing a device that is not open" in new EndpointFixture {
      // When
      manager.closeDevice(id, direction)

      // Then
      device.closeCount shouldEqual 0
      businessync.publish.calls shouldEqual Seq(MidiDeviceAvailableEvent(id, direction))
    }

    "release and forget the handle of a device that is not available when it is closed" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)

        // When
        manager.closeDevice(id, direction)

        // Then
        handle.state shouldEqual State.Closed
        manager.deviceOf(id, direction) shouldBe empty
        manager.devicesRequestedToOpenFor(direction) shouldBe empty

        // When
        environment.plug(device)
        manager.refresh()

        // Then
        device.isOpen shouldBe false
      }

    "return a new handle for a device requested again after its handle was forgotten" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val forgottenHandle: MidiDeviceHandle = manager.openDevice(id, direction)
        manager.closeDevice(id, direction)

        // When
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)

        // Then
        handle should not be theSameInstanceAs(forgottenHandle)
        forgottenHandle.state shouldEqual State.Closed
        handle.state shouldEqual State.WaitingToOpen
      }

    "close an open device that got unplugged, keeping its handle waiting to open it again" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = manager.openDevice(id, direction)
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      device.isOpen shouldBe false
      handle.state shouldEqual State.WaitingToOpen
      manager.deviceOf(id, direction) shouldEqual Some(handle)
      manager.openDevicesFor(direction) shouldBe empty
      manager.devicesRequestedToOpenFor(direction) shouldEqual Seq(handle)
      manager.isDeviceAvailable(id, direction) shouldBe false
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceAvailableEvent(id, direction),
        MidiDeviceOpenedEvent(id, direction),
        MidiDeviceClosedEvent(id, direction),
        MidiDeviceUnavailableEvent(id, direction)
      )
    }

    "open the handle of an unplugged device with the device it gets when replugged" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = manager.openDevice(id, direction)
      val repluggedDevice: FakeMidiDevice = endpoint.newDevice(deviceName)
      environment.unplug(device.getDeviceInfo)
      manager.refresh()
      environment.plug(repluggedDevice)

      // When
      manager.refresh()

      // Then
      handle.state shouldEqual State.Open
      repluggedDevice.isOpen shouldBe true
      manager.devicesRequestedToOpenFor(direction) shouldEqual Seq(handle)
      businessync.publish.calls.drop(4) shouldEqual
        Seq(MidiDeviceAvailableEvent(id, direction), MidiDeviceOpenedEvent(id, direction))
    }

    "close the device of an open handle and open the one it got swapped for between two refreshes" in
      new EndpointFixture {
        // Given
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)
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
          MidiDeviceAvailableEvent(id, direction),
          MidiDeviceOpenedEvent(id, direction),
          MidiDeviceClosedEvent(id, direction),
          MidiDeviceOpenedEvent(id, direction)
        )
      }

    "keep an open device open, reporting nothing, on a refresh that finds the same instance of it" in
      new EndpointFixture {
        // Given
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)

        // When
        manager.refresh()

        // Then
        handle.state shouldEqual State.Open
        device.isOpen shouldBe true
        device.closeCount shouldEqual 0
        businessync.publish.calls shouldEqual
          Seq(MidiDeviceAvailableEvent(id, direction), MidiDeviceOpenedEvent(id, direction))
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
        val handle: MidiDeviceHandle = manager.openDevice(id, direction)
        val heldInstance: FakeMidiDevice = instances.last

        // When
        manager.refresh()

        // Then
        instances should have size 2
        handle.state shouldEqual State.Open
        heldInstance.isOpen shouldBe true
        heldInstance.closeCount shouldEqual 0
        instances.last.openCount shouldEqual 0
        businessync.publish.calls shouldEqual
          Seq(MidiDeviceAvailableEvent(id, direction), MidiDeviceOpenedEvent(id, direction))
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

  "A method taking a direction" should {
    "reject None, which names no endpoint, and InputOutput, which names neither of the manager's two" in new Fixture {
      // Given
      val manager: JavaMidiManager = newManager()
      val id: MidiDeviceId = MidiDeviceId(deviceName, "Roland")
      val methods: Seq[(String, MidiDirection => Any)] = Seq(
        ("isDeviceAvailable", manager.isDeviceAvailable(id, _)),
        ("deviceInfoOf", manager.deviceInfoOf(id, _)),
        ("deviceIdsFor", manager.deviceIdsFor(_)),
        ("devicesInfoFor", manager.devicesInfoFor(_)),
        ("openDevice", manager.openDevice(id, _)),
        ("deviceOf", manager.deviceOf(id, _)),
        ("openDevicesFor", manager.openDevicesFor(_)),
        ("devicesRequestedToOpenFor", manager.devicesRequestedToOpenFor(_)),
        ("closeDevice", manager.closeDevice(id, _))
      )
      val cases = Table(
        ("method", "direction", "call"),
        (for {
          (method, call) <- methods
          direction <- Seq(MidiDirection.None, MidiDirection.InputOutput)
        } yield (method, direction, call))*
      )

      forAll(cases) { (_, direction, call) =>
        // When / Then
        an[IllegalArgumentException] should be thrownBy call(direction)
      }
    }
  }

  "refresh" should {
    "serialise concurrent refreshes, so that one never scans the environment while another is refreshing" in
      new Fixture {
        // Given
        val manager: JavaMidiManager = newManager()
        val scansInFlight: AtomicInteger = AtomicInteger()
        val mostScansInFlight: AtomicInteger = AtomicInteger()
        val otherRefreshRunning: CountDownLatch = CountDownLatch(1)
        val bothScanning: CountDownLatch = CountDownLatch(2)

        environment.onScan = () => {
          mostScansInFlight.accumulateAndGet(scansInFlight.incrementAndGet(), Math.max)
          bothScanning.countDown()
          // Hold this scan open only until a second one joins it, which a refresh allowed to run alongside does at
          // once, releasing both. Serialised, no second scan starts, so this one waits out the window below and the
          // other, running afterwards, finds the latch already at zero and returns immediately. The window opens
          // only once the other refresh is known to be running, so it measures the lock and not the thread start.
          otherRefreshRunning.await(AwaitTimeoutMillis, TimeUnit.MILLISECONDS)
          bothScanning.await(ScanOverlapWindowMillis, TimeUnit.MILLISECONDS)
          scansInFlight.decrementAndGet()
        }

        // When
        val firstRefresh: CompletableFuture[Void] = CompletableFuture.runAsync(() => manager.refresh())
        val secondRefresh: CompletableFuture[Void] = CompletableFuture.runAsync { () =>
          otherRefreshRunning.countDown()
          manager.refresh()
        }

        // Then
        Seq(firstRefresh, secondRefresh).foreach(_.get(AwaitTimeoutMillis, TimeUnit.MILLISECONDS))
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
          manager.isDeviceAvailable(device.id, MidiDirection.Input) shouldBe isInput
          manager.isDeviceAvailable(device.id, MidiDirection.Output) shouldBe isOutput
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
        manager.deviceIdsFor(MidiDirection.Input) shouldEqual Seq(id)
        manager.deviceIdsFor(MidiDirection.Output) shouldEqual Seq(id)
        businessync.publish.calls shouldEqual Seq(
          MidiDeviceAvailableEvent(id, MidiDirection.Input),
          MidiDeviceAvailableEvent(id, MidiDirection.Output)
        )

        // When
        manager.openDevice(id, MidiDirection.Output)

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
      businessync.publish.calls shouldEqual Seq(MidiDeviceAvailableEvent(device.id, MidiDirection.Input))
    }

    "replace the device resolved earlier for an id that stays present, without reporting it as available again" in
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
        manager.openDevice(id, MidiDirection.Output)

        // Then
        laterDevice.isOpen shouldBe true
        firstDevice.isOpen shouldBe false
        businessync.publish.calls shouldEqual Seq(
          MidiDeviceAvailableEvent(id, MidiDirection.Output), MidiDeviceOpenedEvent(id, MidiDirection.Output)
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
      manager.openDevice(firstDevice.id, MidiDirection.Output)

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
      manager.openDevice(firstDevice.id, MidiDirection.Output)

      // Then
      lastDevice.isOpen shouldBe true
      firstDevice.isOpen shouldBe false
      manager.deviceIdsFor(MidiDirection.Output) shouldEqual Seq(firstDevice.id)
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceAvailableEvent(firstDevice.id, MidiDirection.Output),
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
          manager.deviceIdsFor(MidiDirection.Input) shouldBe empty
          manager.deviceIdsFor(MidiDirection.Output) shouldBe empty
          businessync.publish.calls shouldBe empty
        }
      }
    }

    "skip a device that fails to resolve for any other reason and report it as failed to become available" in
      new Fixture {
        // Given
        val failure: Exception = IllegalStateException("CoreMIDI failure")
        environment.plugUnresolvable(javaDeviceInfo, failure)

        // When
        val manager: JavaMidiManager = newManager()

        // Then
        manager.deviceIdsFor(MidiDirection.Input) shouldBe empty
        manager.deviceIdsFor(MidiDirection.Output) shouldBe empty
        businessync.publish.calls shouldEqual
          Seq(MidiDeviceFailedToBecomeAvailableEvent(javaDeviceInfo.asMidiDeviceId, failure))
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
      manager.isDeviceAvailable(device.id, MidiDirection.Input) shouldBe true
      businessync.publish.calls shouldEqual
        Seq(MidiEnvironmentChangedEvent, MidiDeviceAvailableEvent(device.id, MidiDirection.Input))
    }
  }

  "A device that works in both directions" should {
    /** A manager over [[device]], a device working in both directions, open both as an input and as an output. */
    abstract class BidirectionalFixture extends Fixture {
      val device: FakeMidiDevice = FakeMidiDevice(deviceName, maxTransmitters = -1, maxReceivers = -1)
      environment.plug(device)
      val manager: JavaMidiManager = newManager()
      val inputHandle: MidiDeviceHandle = manager.openDevice(device.id, MidiDirection.Input)
      val outputHandle: MidiDeviceHandle = manager.openDevice(device.id, MidiDirection.Output)
    }

    /**
     * A manager over the JDK Real Time Sequencer, whose provider builds on every lookup a new instance working in both
     * directions; [[instances]] holds them in the order they were resolved.
     */
    abstract class SequencerFixture extends Fixture {
      val instances: mutable.Buffer[FakeMidiDevice] = mutable.ArrayBuffer()
      environment.plugResolvingAfresh(javaDeviceInfo, () => {
        val instance = FakeMidiDevice(deviceName, maxTransmitters = -1, maxReceivers = -1)
        instances += instance
        instance
      })
      val manager: JavaMidiManager = newManager()
      val id: MidiDeviceId = instances.head.id
    }

    "stay open as an output when closed as an input, and the other way around" in {
      val directions = Table(("closed", "remaining"),
        (MidiDirection.Input, MidiDirection.Output),
        (MidiDirection.Output, MidiDirection.Input))

      forAll(directions) { (closed, remaining) =>
        new BidirectionalFixture {
          // When
          manager.closeDevice(device.id, closed)

          // Then
          device.isOpen shouldBe true
          manager.deviceOf(device.id, remaining).map(_.state) shouldEqual Some(State.Open)

          // When
          manager.closeDevice(device.id, remaining)

          // Then
          device.isOpen shouldBe false
          device.closeCount shouldEqual 1
        }
      }
    }

    "stay open as an output when closed as an input after a refresh, although it resolves to a new instance on every " +
      "lookup" in new SequencerFixture {
        // Given
        manager.openDevice(id, MidiDirection.Input)
        val outputHandle: MidiDeviceHandle = manager.openDevice(id, MidiDirection.Output)
        val heldInstance: FakeMidiDevice = instances.head
        manager.refresh()

        // When
        manager.closeDevice(id, MidiDirection.Input)
        outputHandle.receiver.send(NoteOnMidiMsg(2, MidiNote.C4, 100), 1L)

        // Then
        instances should have size 2
        instances.last.openCount shouldEqual 0
        heldInstance.openCount shouldEqual 1
        heldInstance.isOpen shouldBe true
        outputHandle.state shouldEqual State.Open
        heldInstance.receivedMessages.map { case (message, timeStamp) => (message.asScala, timeStamp) } shouldEqual
          Seq(NoteOnMidiMsg(2, MidiNote.C4, 100) -> 1L)

        // When
        manager.closeDevice(id, MidiDirection.Output)

        // Then
        heldInstance.isOpen shouldBe false
        heldInstance.closeCount shouldEqual 1
      }

    "be opened in the other direction on the instance it is open on in one, after a refresh, although it resolves to " +
      "a new instance on every lookup" in {
      val directions = Table(("openedFirst", "openedLater"),
        (MidiDirection.Input, MidiDirection.Output),
        (MidiDirection.Output, MidiDirection.Input))

      forAll(directions) { (openedFirst, openedLater) =>
        new SequencerFixture {
          // Given
          manager.openDevice(id, openedFirst)
          val heldInstance: FakeMidiDevice = instances.last
          manager.refresh()

          // When
          manager.openDevice(id, openedLater)

          // Then
          instances should have size 2
          instances.last.openCount shouldEqual 0
          heldInstance.openCount shouldEqual 1

          // When
          manager.closeDevice(id, openedFirst)

          // Then
          heldInstance.isOpen shouldBe true
          manager.deviceOf(id, openedLater).map(_.state) shouldEqual Some(State.Open)
        }
      }
    }

    "move to a new instance in both directions on a refresh once the instance it is open on got closed, although it " +
      "resolves to a new instance on every lookup" in new SequencerFixture {
      // Given
      val inputHandle: MidiDeviceHandle = manager.openDevice(id, MidiDirection.Input)
      instances.last.close()
      manager.refresh()

      // When
      val outputHandle: MidiDeviceHandle = manager.openDevice(id, MidiDirection.Output)

      // Then
      instances should have size 2
      instances.last.isOpen shouldBe true
      instances.last.openCount shouldEqual 1
      Seq(inputHandle, outputHandle).map(_.state) shouldEqual Seq(State.Open, State.Open)
    }

    "be opened on its source as an input and on its destination as an output, after a refresh, when exposed as two " +
      "instances sharing its id" in new Fixture {
      // Given
      // A hardware device, which CoreMIDI4J exposes as a source and a destination
      val source: FakeMidiDevice = Input.newDevice(deviceName)
      val destination: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(source)
      environment.plug(destination)
      val manager: JavaMidiManager = newManager()
      manager.openDevice(source.id, MidiDirection.Input)
      manager.refresh()

      // When
      val outputHandle: MidiDeviceHandle = manager.openDevice(destination.id, MidiDirection.Output)

      // Then
      source.openCount shouldEqual 1
      destination.isOpen shouldBe true
      outputHandle.state shouldEqual State.Open
    }

    "keep sending to the device as an output after it is closed as an input" in new BidirectionalFixture {
      // When
      manager.closeDevice(device.id, MidiDirection.Input)
      outputHandle.receiver.send(NoteOnMidiMsg(2, MidiNote.C4, 100), 1L)

      // Then
      device.receivedMessages.map { case (message, timeStamp) => (message.asScala, timeStamp) } shouldEqual
        Seq(NoteOnMidiMsg(2, MidiNote.C4, 100) -> 1L)
    }

    "be closed by closing the manager" in new BidirectionalFixture {
      // When
      manager.close()

      // Then
      device.isOpen shouldBe false
      Seq(inputHandle, outputHandle).map(_.state) shouldEqual Seq(State.Available, State.Available)
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
      manager.openDevice(inputDevice.id, MidiDirection.Input)
      manager.openDevice(outputDevice.id, MidiDirection.Output)

      // When
      manager.close()

      // Then
      inputDevice.isOpen shouldBe false
      outputDevice.isOpen shouldBe false
      manager.devicesRequestedToOpenFor(MidiDirection.Input) shouldBe empty
      manager.devicesRequestedToOpenFor(MidiDirection.Output) shouldBe empty
      environment.subscriberCount shouldEqual 0
    }

    "close a device opened more than once" in new Fixture {
      // Given
      val device: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(device)
      val manager: JavaMidiManager = newManager()
      manager.openDevice(device.id, MidiDirection.Output)
      manager.openDevice(device.id, MidiDirection.Output)

      // When
      manager.close()

      // Then
      device.isOpen shouldBe false
      manager.devicesRequestedToOpenFor(MidiDirection.Output) shouldBe empty
    }

    "forget the handle of a device waiting to open" in new Fixture {
      // Given
      val manager: JavaMidiManager = newManager()
      val handle: MidiDeviceHandle = manager.openDevice(MidiDeviceId(deviceName, "Roland"), MidiDirection.Output)

      // When
      manager.close()

      // Then
      handle.state shouldEqual State.Closed
      manager.deviceOf(handle.id, MidiDirection.Output) shouldBe empty
    }
  }

  "Publishing" should {
    "publish the events of an operation in order once it completes, outside the lock of the manager" in new Fixture {
      // Given
      val device: FakeMidiDevice = Output.newDevice(deviceName)
      val manager: JavaMidiManager = newManager()
      manager.openDevice(device.id, MidiDirection.Output)
      environment.plug(device)
      // What a subscriber on another thread sees of the handle when each event reaches it; with the lock still held,
      // the lookup would time out.
      val observedStates: mutable.Buffer[Option[State]] = mutable.ArrayBuffer()
      onPublish = _ => observedStates += CompletableFuture
        .supplyAsync(() => manager.deviceOf(device.id, MidiDirection.Output).map(_.state))
        .get(5, TimeUnit.SECONDS)

      // When
      manager.refresh()

      // Then
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceAvailableEvent(device.id, MidiDirection.Output),
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
        Seq(("""Failed to resolve device "CoreMIDI4J - FP-90" (Roland)!""", Some("CoreMIDI failure")))
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

    "warn that a device to open is not available and will be opened once it becomes available" in new Fixture {
      // Given
      val manager: JavaMidiManager = newManager()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        manager.openDevice(MidiDeviceId(deviceName, "Roland"), MidiDirection.Output)
      }

      // Then
      events.messagesAt(Level.WARN) shouldEqual
        Seq("""Output device "CoreMIDI4J - FP-90" (Roland) is not available; it will be opened once it """ +
          "becomes available.")
    }

    "report the closing of the MIDI devices at info level" in new Fixture {
      // Given
      val device: FakeMidiDevice = Input.newDevice(deviceName)
      environment.plug(device)
      val manager: JavaMidiManager = newManager()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        manager.openDevice(device.id, MidiDirection.Input)
        manager.close()
      }

      // Then
      events.messagesAt(Level.INFO) shouldEqual Seq("Closing MIDI devices...", "Finished closing MIDI devices.")
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
