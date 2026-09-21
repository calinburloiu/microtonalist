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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*

import java.util.concurrent.locks.{Lock, ReentrantLock}
import javax.annotation.concurrent.{NotThreadSafe, ThreadSafe}
import javax.sound.midi.{MidiDevice, MidiUnavailableException}
import scala.collection.immutable.VectorMap
import scala.collection.mutable

/**
 * [[MidiManager]] over Java Sound and CoreMIDI4J.
 *
 * The Java MIDI API and CoreMIDI4J may expose two [[MidiDevice]] instances for the same physical device, one for input
 * and the other for output, under a single [[MidiDeviceId]]. The manager therefore keeps its devices in two separate
 * endpoints, and the `direction` its methods take accepts only [[MidiDirection.Input]] and [[MidiDirection.Output]]:
 * [[MidiDirection.None]] and [[MidiDirection.InputOutput]] throw an `IllegalArgumentException` (see [[MidiManager]]).
 *
 * Each of the two endpoints, one for inputs and one for outputs, keeps a registry of its live
 * [[JavaMidiDeviceHandle]]s: one for every device that is available, requested to open, or both.
 *
 * Each [[refresh]] resolves every device once through the [[JavaMidiEnvironment]] and reconciles each registry with
 * what it found (the device resolved last for an id wins). It hands each available device to its handle and tells the
 * handles whose device is gone. A device that works in both directions is handed to both of its handles over one
 * instance: the one either of them holds open, if any, rather than the one just resolved. A handle that ends up
 * [[MidiDeviceHandle.State.Closed]] is forgotten. The manager also refreshes whenever the environment reports a change,
 * until it is closed.
 *
 * There are two locks, always taken in this order and never the other way around:
 *
 *   1. a refresh lock, held for the whole of a refresh, so that two refreshes cannot interleave and the one that
 *      scanned first cannot reconcile last and win with a stale snapshot;
 *   1. a manager-wide lock, which serialises the operations and the registry reads. The lock of a handle is only ever
 *      taken inside it, and that of the [[JavaMidiDeviceReferenceCounter]] the handles share only inside the lock of a
 *      handle. One exclusive lock is enough here, rather than a read-write one: the reads are a handful of
 *      handle lookups made by the UI and the composition root, never on the MIDI path, and each of them takes the
 *      lock of every handle it inspects anyway, so there is no read contention for a read-write lock to relieve —
 *      only its higher uncontended cost and its read lock that cannot be upgraded.
 *
 * Resolving the devices happens inside the refresh lock but before the manager lock is taken, so that a call into
 * Java Sound, which can block, neither holds off the readers nor runs while holding the lock that the environment
 * callbacks need. The [[MidiEvent]]s of an operation are published in order once the locks are released, because the
 * bus delivers them synchronously to subscribers that may use the manager or send MIDI. Sending MIDI takes no lock.
 *
 * Build one through [[JavaMidiManager.apply]], which starts it once it is constructed; the constructor itself
 * neither publishes nor subscribes.
 *
 * @param businessync Used for publishing [[MidiEvent]]s.
 * @param environment The Java Sound environment to scan and subscribe to; the production default is
 *                    [[CoreMidi4JEnvironment]], a fake is what a test passes.
 */
@ThreadSafe
class JavaMidiManager private(businessync: Businessync, environment: JavaMidiEnvironment)
  extends MidiManager, Locking, StrictLogging {

  import JavaMidiManager.*

  private implicit val lock: Lock = ReentrantLock()

  /**
   * Taken for the whole of a refresh, scan included, so that refreshes cannot overlap. It is always taken before
   * [[lock]] and never while holding it, the two being ordered that way.
   */
  private val refreshLock: Lock = ReentrantLock()

  /**
   * Counts the references the handles of both endpoints hold to each Java Sound device, which the two endpoints may
   * share: a device that works in both directions has one handle in each over the same instance.
   */
  private val javaDeviceReferences: JavaMidiDeviceReferenceCounter = JavaMidiDeviceReferenceCounter()

  private val inputEndpoint: MidiEndpoint = MidiEndpoint(MidiDirection.Input, javaDeviceReferences)
  private val outputEndpoint: MidiEndpoint = MidiEndpoint(MidiDirection.Output, javaDeviceReferences)

  @volatile private var environmentSubscription: Option[AutoCloseable] = None

  /**
   * Scans the environment for the first time and subscribes to its changes.
   *
   * It is called by [[JavaMidiManager.apply]] once the instance is built, rather than from the constructor: it
   * publishes the events of that first scan, and hands the environment a callback holding this instance, neither of
   * which may happen while the instance is still being constructed.
   */
  private def start(): Unit = {
    refresh()

    // Automatically refresh when the MIDI environment has changed
    environmentSubscription = Some(environment.subscribeToEnvironmentChanged(() => onEnvironmentChanged()))
  }

  private def onEnvironmentChanged(): Unit = {
    logger.info("The MIDI environment has changed.")
    refreshAfter(Seq(MidiEnvironmentChangedEvent))
  }

  override def refresh(): Unit = refreshAfter(Seq.empty)

  /**
   * Refreshes, publishing `leadingEvents` before the events of the refresh.
   *
   * [[refreshLock]] covers the whole refresh, so that two of them cannot interleave: were only the reconciliation
   * locked, the refresh that scanned first could reconcile last and win with a stale snapshot, which nothing would
   * correct until the next change. [[lock]] is taken only for the reconciliation itself, so that scanning the
   * environment — which calls into Java Sound and can block — neither holds off the readers nor calls into Java
   * Sound while holding the lock that its own callbacks need.
   *
   * The events are published once both locks are released, so that a subscriber runs inside neither.
   */
  private def refreshAfter(leadingEvents: Seq[MidiEvent]): Unit = {
    val events = withLock {
      val resolutionEvents = mutable.Buffer.from(leadingEvents)
      val devices = environment.javaDeviceInfos.flatMap { javaInfo =>
        resolveJavaDevice(javaInfo, resolutionEvents)
          .map(javaDevice => AvailableDevice(javaDevice.asMidiDeviceInfo, javaDevice))
      }

      withLock {
        val sharedDevices = devices.map(withInstanceHeldOpen)
        val reconciliationEvents = inputEndpoint.reconcile(sharedDevices.filter(_.info.isInputDevice)) ++
          outputEndpoint.reconcile(sharedDevices.filter(_.info.isOutputDevice))
        resolutionEvents.toSeq ++ reconciliationEvents
      }(lock)
    }(refreshLock)

    events.foreach(businessync.publish)
  }

  /**
   * Returns `device`, found by a refresh, over the instance that a handle of its id holds open, instead of the one just
   * resolved, if the device works in both directions and such a handle exists.
   *
   * A device that works in both directions has a handle in each endpoint, which must share one instance for the
   * [[JavaMidiDeviceReferenceCounter]] to count their references together. A provider that builds a new instance on
   * every lookup, as that of the JDK `Real Time Sequencer` does, would split them otherwise: an [[State.Open]] handle
   * keeps the instance it holds while that is still open, but an [[State.Available]] one takes the new instance, and
   * opening it later would open a second device. The check is the one the handle makes: an instance that got closed,
   * as CoreMIDI4J closes that of a vanished endpoint, is not held open, and both handles move to the new one.
   *
   * A device that works in one direction only is returned unchanged: a hardware device that works in both is exposed as
   * a source and a destination sharing its id, each of which belongs to one endpoint only.
   */
  private def withInstanceHeldOpen(device: AvailableDevice): AvailableDevice = {
    if (device.info.direction == MidiDirection.InputOutput) {
      val instanceHeldOpen = Seq(inputEndpoint, outputEndpoint).iterator
        .flatMap(_.deviceOf(device.id))
        .flatMap(_.javaDevice)
        .find(_.isOpen)
      instanceHeldOpen.fold(device)(javaDevice => device.copy(javaDevice = javaDevice))
    } else {
      device
    }
  }

  /**
   * Resolves the Java Sound device described by `javaInfo`, or `None` if it cannot be. A `MidiUnavailableException`
   * or an `IllegalArgumentException` drops the device silently. Any other exception is logged and collected into
   * `events` as a [[MidiDeviceFailedToBecomeAvailableEvent]], to be published with the events of the refresh,
   * before the device is dropped.
   *
   * Both exceptions dropped silently mean that the device listed a moment earlier is not usable right now, which is
   * routine while devices are plugged in and unplugged, and which every [[refresh]] would report again:
   *
   *   - `MidiUnavailableException` means the device is there but its resources are not, typically because another
   *     application holds it exclusively.
   *   - `IllegalArgumentException` is what `MidiSystem.getMidiDevice` (and CoreMIDI4J's provider) throws for an info
   *     that no longer describes an installed device, so it is the outcome of the race between listing the devices
   *     and resolving them: the device was unplugged in between, and the next refresh will not list it at all.
   */
  private def resolveJavaDevice(javaInfo: MidiDevice.Info, events: mutable.Buffer[MidiEvent]): Option[MidiDevice] = {
    try {
      Some(environment.javaDeviceOf(javaInfo))
    } catch {
      case _: MidiUnavailableException => None
      case _: IllegalArgumentException => None
      case exception: Exception =>
        val id = javaInfo.asMidiDeviceId
        logger.error(s"Failed to make device $id available!", exception)
        events += MidiDeviceFailedToBecomeAvailableEvent(id, exception)
        None
    }
  }

  /**
   * Runs `operation` under the lock of the manager and then, once the lock is released, publishes in order the events
   * it returned, so that a subscriber, which the bus calls on this thread, never runs inside the lock.
   *
   * @return the result of the operation.
   */
  private def withLockThenPublish[R](operation: => (R, Seq[MidiEvent])): R = {
    val (result, events) = withLock { operation }
    events.foreach(businessync.publish)
    result
  }

  override def close(): Unit = {
    // Stop watching the environment before closing the devices, so that a change reported meanwhile cannot refresh
    // the registries — and make available again or reopen a handle — after they were closed.
    environmentSubscription.foreach(_.close())

    logger.info(s"Closing MIDI devices...")
    withLockThenPublish {
      ((), inputEndpoint.closeAll() ++ outputEndpoint.closeAll())
    }
    logger.info(s"Finished closing MIDI devices.")
  }

  override def isDeviceAvailable(deviceId: MidiDeviceId, direction: MidiDirection): Boolean = withLock {
    endpointOf(direction).isDeviceAvailable(deviceId)
  }

  override def deviceInfoOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceInfo] = withLock {
    endpointOf(direction).deviceInfoOf(deviceId)
  }

  override def deviceIdsFor(direction: MidiDirection): Seq[MidiDeviceId] = withLock {
    endpointOf(direction).deviceIds
  }

  override def devicesInfoFor(direction: MidiDirection): Seq[MidiDeviceInfo] = withLock {
    endpointOf(direction).devicesInfo
  }

  override def openDevice(deviceId: MidiDeviceId, direction: MidiDirection): MidiDeviceHandle = withLockThenPublish {
    endpointOf(direction).openDevice(deviceId)
  }

  override def deviceOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceHandle] = withLock {
    endpointOf(direction).deviceOf(deviceId)
  }

  override def openDevicesFor(direction: MidiDirection): Seq[MidiDeviceHandle] = withLock {
    endpointOf(direction).openDevices
  }

  override def devicesRequestedToOpenFor(direction: MidiDirection): Seq[MidiDeviceHandle] = withLock {
    endpointOf(direction).devicesRequestedToOpen
  }

  override def closeDevice(deviceId: MidiDeviceId, direction: MidiDirection): Unit = withLockThenPublish {
    ((), endpointOf(direction).closeDevice(deviceId))
  }

  /** @return the endpoint that keeps the devices of `direction`, which must be `Input` or `Output`. */
  private def endpointOf(direction: MidiDirection): MidiEndpoint = direction match {
    case MidiDirection.Input => inputEndpoint
    case MidiDirection.Output => outputEndpoint
    case other => throw IllegalArgumentException(
      s"This MIDI manager keeps a device in an input or an output endpoint, not in $other!")
  }
}

object JavaMidiManager {

  /**
   * Creates a manager, scans the MIDI environment and subscribes to its changes.
   *
   * @param businessync Used for publishing [[MidiEvent]]s.
   * @param environment The Java Sound environment to scan and subscribe to.
   * @return the manager, fully constructed and watching the environment.
   */
  def apply(businessync: Businessync,
            environment: JavaMidiEnvironment = CoreMidi4JEnvironment): JavaMidiManager = {
    val manager = new JavaMidiManager(businessync, environment)
    manager.start()
    manager
  }

  /** A device present in the environment: its API-level information and the resolved Java Sound device. */
  private case class AvailableDevice(info: MidiDeviceInfo, javaDevice: MidiDevice) {
    def id: MidiDeviceId = info.id
  }

  /**
   * The registry of the live handles of one direction. The Java MIDI API lists input and output devices separately,
   * so the same physical device may appear twice, under the same [[MidiDeviceId]].
   *
   * It is not thread-safe: [[JavaMidiManager]] uses it only under its lock. Each operation returns the events of the
   * transitions it made, for the manager to publish.
   *
   * @param direction            whether the devices managed are input or output devices.
   * @param javaDeviceReferences the reference counter of the Java Sound devices, shared with the other endpoint.
   */
  @NotThreadSafe
  private class MidiEndpoint(val direction: MidiDirection, javaDeviceReferences: JavaMidiDeviceReferenceCounter)
    extends StrictLogging {

    /** The live handles, which are available, requested to open, or both, in the order they were created. */
    private val handles: mutable.LinkedHashMap[MidiDeviceId, JavaMidiDeviceHandle] = mutable.LinkedHashMap()

    /**
     * Reconciles the live handles with the devices of this direction that a refresh found: each device is handed to
     * the handle of its id, created if there is none, and each available handle whose device was not found is
     * made unavailable, and forgotten if that leaves it closed.
     */
    def reconcile(devices: Seq[AvailableDevice]): Seq[MidiEvent] = {
      // Two devices of this direction can share an id, a MidiDeviceId being a name and a vendor: two identical
      // devices of the same model plugged in at once are told apart by nothing else. Only one can have the handle of
      // that id, and the device resolved last for it wins.
      // TODO #306 Give each of them a handle, keying the registry on a platform key instead of the MidiDeviceId.
      val devicesById = devices.foldLeft(VectorMap.empty[MidiDeviceId, AvailableDevice]) { (devicesById, device) =>
        if (devicesById.contains(device.id)) {
          logger.warn(s"Found more than one $direction device with id ${device.id}; only the last one resolved is " +
            "used and the others are ignored. Rename one of them in the MIDI setup of the operating system to tell " +
            "them apart.")
        }

        devicesById.updated(device.id, device)
      }

      val availableEvents = devicesById.values.flatMap { availableDevice =>
        handleOf(availableDevice.id).becomeAvailable(availableDevice.info, availableDevice.javaDevice)
      }
      val unavailableEvents = handles.values
        .filter(handle => handle.isAvailable && !devicesById.contains(handle.id))
        .flatMap(handle => forgettingIfClosed(handle)(handle.becomeUnavailable()))

      Seq.from(availableEvents ++ unavailableEvents)
    }

    def isDeviceAvailable(deviceId: MidiDeviceId): Boolean = handles.get(deviceId).exists(_.isAvailable)

    def deviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = handles.get(deviceId).flatMap(_.info)

    def deviceIds: Seq[MidiDeviceId] = availableHandles.map(_.id)

    def devicesInfo: Seq[MidiDeviceInfo] = availableHandles.flatMap(_.info)

    /** Takes one reference to the device, through its live handle, created if there is none. */
    def openDevice(deviceId: MidiDeviceId): (JavaMidiDeviceHandle, Seq[MidiEvent]) = {
      val handle = handleOf(deviceId)
      val events = handle.open()
      if (handle.state == State.WaitingToOpen) {
        logger.warn(s"${direction.toString.capitalize} device $deviceId is not available; it will be opened once " +
          "it becomes available.")
      }

      (handle, events)
    }

    def deviceOf(deviceId: MidiDeviceId): Option[JavaMidiDeviceHandle] = handles.get(deviceId)

    def openDevices: Seq[JavaMidiDeviceHandle] = handles.values.filter(_.isOpen).toSeq

    def devicesRequestedToOpen: Seq[JavaMidiDeviceHandle] = handles.values.filter(_.isOpenRequested).toSeq

    /** Releases one reference to the device, if its live handle is requested to open. */
    def closeDevice(deviceId: MidiDeviceId): Seq[MidiEvent] = handles.get(deviceId) match {
      case Some(handle) if handle.isOpenRequested => forgettingIfClosed(handle)(handle.close())
      case _ => Seq.empty
    }

    /** Releases every reference to every device. */
    def closeAll(): Seq[MidiEvent] = handles.values.flatMap { handle =>
      forgettingIfClosed(handle)(handle.closeAll())
    }.toSeq

    private def availableHandles: Seq[JavaMidiDeviceHandle] = handles.values.filter(_.isAvailable).toSeq

    private def handleOf(deviceId: MidiDeviceId): JavaMidiDeviceHandle =
      handles.getOrElseUpdate(deviceId, JavaMidiDeviceHandle(deviceId, direction, javaDeviceReferences))

    /** Runs `command`, a command of `handle`, then forgets the handle if the command left it closed. */
    private def forgettingIfClosed(handle: JavaMidiDeviceHandle)(command: => Seq[MidiEvent]): Seq[MidiEvent] = {
      val events = command
      if (handle.state == State.Closed) {
        handles.remove(handle.id)
      }

      events
    }
  }
}
