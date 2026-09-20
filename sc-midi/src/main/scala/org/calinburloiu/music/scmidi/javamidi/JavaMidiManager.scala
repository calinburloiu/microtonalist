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
 * The class has different sets of methods for inputs and outputs, because the Java MIDI API and CoreMIDI4J may
 * expose two [[MidiDevice]] ([[JavaMidiDeviceHandle]]) instances for the same physical device, one for input and the
 * other for output. Note that in this case, there is a single [[MidiDeviceId]].
 *
 * Each of the two endpoints, one for inputs and one for outputs, keeps a registry of its live
 * [[JavaMidiDeviceHandle]]s: one for every device that is connected, requested to open, or both.
 *
 * Each [[refresh]] resolves every device once through the [[JavaMidiEnvironment]] and reconciles each registry with
 * what it found (the device resolved last for an id wins). It hands each connected device to its handle and tells the
 * handles whose device is gone. A handle that ends up [[MidiDeviceHandle.State.Closed]] is forgotten. The manager also
 * refreshes whenever the environment reports a change, until it is closed.
 *
 * A manager-wide lock serialises the operations and the registry reads. The lock of a handle is only ever taken
 * inside it, never the other way around. One exclusive lock is enough, rather than a read-write one: the reads are a
 * handful of handle lookups made by the UI and the composition root, never on the MIDI path, and each of them takes
 * the lock of every handle it inspects anyway, so there is no read contention for a read-write lock to relieve — only
 * its higher uncontended cost and its read lock that cannot be upgraded.
 *
 * Resolving the devices happens before taking the lock. The [[MidiEvent]]s of an operation are published in order
 * once the lock is released, because the bus delivers them synchronously to subscribers that may use the manager or
 * send MIDI. Sending MIDI takes neither lock.
 *
 * @param businessync Used for publishing [[MidiEvent]]s.
 * @param environment The Java Sound environment to scan and subscribe to; the production default is
 *                    [[CoreMidi4JEnvironment]], a fake is what a test passes.
 */
@ThreadSafe
class JavaMidiManager(businessync: Businessync,
                      environment: JavaMidiEnvironment = CoreMidi4JEnvironment)
  extends MidiManager, Locking, StrictLogging {

  import JavaMidiManager.*

  private implicit val lock: Lock = ReentrantLock()

  private val inputEndpoint: MidiEndpoint = MidiEndpoint(MidiDirection.Input)
  private val outputEndpoint: MidiEndpoint = MidiEndpoint(MidiDirection.Output)

  private val environmentSubscription: AutoCloseable = init()

  private def init(): AutoCloseable = {
    refresh()

    // Automatically refresh when the MIDI environment has changed
    environment.subscribeToEnvironmentChanged(() => onEnvironmentChanged())
  }

  private def onEnvironmentChanged(): Unit = {
    logger.info("The MIDI environment has changed.")
    refreshAfter(Seq(MidiEnvironmentChangedEvent))
  }

  override def refresh(): Unit = refreshAfter(Seq.empty)

  /**
   * Refreshes, publishing `leadingEvents` before the events of the refresh.
   *
   * The environment is scanned before the lock is taken, so that resolving the devices — which calls into Java Sound
   * and can block — does not hold off the readers. Refreshes must therefore not overlap, as [[refresh]] states: the
   * one that scanned first can reconcile last and win with a stale snapshot. Nothing serialises them here, since
   * CoreMIDI4J delivers its environment callbacks one at a time and [[init]] is the only other caller.
   */
  private def refreshAfter(leadingEvents: Seq[MidiEvent]): Unit = {
    val resolutionEvents = mutable.Buffer.from(leadingEvents)
    val devices = environment.deviceInfos.flatMap { javaInfo =>
      resolveDevice(javaInfo, resolutionEvents).map(device => ConnectedDevice(device.asMidiDeviceInfo, device))
    }

    withLockThenPublish {
      val reconciliationEvents = inputEndpoint.reconcile(devices.filter(_.info.isInputDevice)) ++
        outputEndpoint.reconcile(devices.filter(_.info.isOutputDevice))
      ((), resolutionEvents.toSeq ++ reconciliationEvents)
    }
  }

  /**
   * Resolves the Java Sound device described by `javaInfo`, or `None` if it cannot be. A `MidiUnavailableException`
   * or an `IllegalArgumentException` drops the device silently. Any other exception is logged and collected into
   * `events` as a [[MidiDeviceFailedToConnectEvent]], to be published with the events of the refresh, before the
   * device is dropped.
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
  private def resolveDevice(javaInfo: MidiDevice.Info, events: mutable.Buffer[MidiEvent]): Option[MidiDevice] = {
    try {
      Some(environment.deviceOf(javaInfo))
    } catch {
      case _: MidiUnavailableException => None
      case _: IllegalArgumentException => None
      case exception: Exception =>
        val id = javaInfo.asMidiDeviceId
        logger.error(s"Failed to connect to device $id!", exception)
        events += MidiDeviceFailedToConnectEvent(id, exception)
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
    // the registries — and reconnect or reopen a handle — after they were closed.
    environmentSubscription.close()

    logger.info(s"Closing MIDI connections...")
    withLockThenPublish {
      ((), inputEndpoint.closeAll() ++ outputEndpoint.closeAll())
    }
    logger.info(s"Finished closing MIDI connections.")
  }

  override def isInputAvailable(deviceId: MidiDeviceId): Boolean = withLock {
    inputEndpoint.isDeviceAvailable(deviceId)
  }

  override def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = withLock {
    inputEndpoint.deviceInfoOf(deviceId)
  }

  override def inputDeviceIds: Seq[MidiDeviceId] = withLock {
    inputEndpoint.deviceIds
  }

  override def inputDevicesInfo: Seq[MidiDeviceInfo] = withLock {
    inputEndpoint.devicesInfo
  }

  override def openInput(deviceId: MidiDeviceId): MidiDeviceHandle = withLockThenPublish {
    inputEndpoint.openDevice(deviceId)
  }

  override def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = withLock {
    inputEndpoint.deviceHandleOf(deviceId)
  }

  override def inputOpenDevices: Seq[MidiDeviceHandle] = withLock {
    inputEndpoint.openDevices
  }

  override def inputDevicesRequestedToOpen: Seq[MidiDeviceHandle] = withLock {
    inputEndpoint.devicesRequestedToOpen
  }

  override def closeInput(deviceId: MidiDeviceId): Unit = withLockThenPublish {
    ((), inputEndpoint.closeDevice(deviceId))
  }

  override def isOutputAvailable(deviceId: MidiDeviceId): Boolean = withLock {
    outputEndpoint.isDeviceAvailable(deviceId)
  }

  override def outputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = withLock {
    outputEndpoint.deviceInfoOf(deviceId)
  }

  override def outputDeviceIds: Seq[MidiDeviceId] = withLock {
    outputEndpoint.deviceIds
  }

  override def outputDevicesInfo: Seq[MidiDeviceInfo] = withLock {
    outputEndpoint.devicesInfo
  }

  override def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle = withLockThenPublish {
    outputEndpoint.openDevice(deviceId)
  }

  override def outputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = withLock {
    outputEndpoint.deviceHandleOf(deviceId)
  }

  override def outputOpenDevices: Seq[MidiDeviceHandle] = withLock {
    outputEndpoint.openDevices
  }

  override def outputDevicesRequestedToOpen: Seq[MidiDeviceHandle] = withLock {
    outputEndpoint.devicesRequestedToOpen
  }

  override def closeOutput(deviceId: MidiDeviceId): Unit = withLockThenPublish {
    ((), outputEndpoint.closeDevice(deviceId))
  }
}

object JavaMidiManager {

  /** A device present in the environment: its API-level information and the resolved Java Sound device. */
  private case class ConnectedDevice(info: MidiDeviceInfo, device: MidiDevice) {
    def id: MidiDeviceId = info.id
  }

  /**
   * The registry of the live handles of one direction. The Java MIDI API lists input and output devices separately,
   * so the same physical device may appear twice, under the same [[MidiDeviceId]].
   *
   * It is not thread-safe: [[JavaMidiManager]] uses it only under its lock. Each operation returns the events of the
   * transitions it made, for the manager to publish.
   *
   * @param direction whether the devices managed are input or output devices.
   */
  @NotThreadSafe
  private class MidiEndpoint(val direction: MidiDirection) extends StrictLogging {

    /** The live handles, which are connected, requested to open, or both, in the order they were created. */
    private val handles: mutable.LinkedHashMap[MidiDeviceId, JavaMidiDeviceHandle] = mutable.LinkedHashMap()

    /**
     * Reconciles the live handles with the devices of this direction that a refresh found: each device is handed to
     * the handle of its id, created if there is none, and each connected handle whose device was not found is
     * disconnected, and forgotten if that leaves it closed.
     */
    def reconcile(devices: Seq[ConnectedDevice]): Seq[MidiEvent] = {
      // Two devices of this direction can share an id, a MidiDeviceId being a name and a vendor: two identical
      // devices of the same model plugged in at once are told apart by nothing else. Only one can have the handle of
      // that id, and the device resolved last for it wins.
      // TODO #306 Give each of them a handle, keying the registry on a platform key instead of the MidiDeviceId.
      val devicesById = devices.foldLeft(VectorMap.empty[MidiDeviceId, ConnectedDevice]) { (devicesById, device) =>
        if (devicesById.contains(device.id)) {
          logger.warn(s"Found more than one $direction device with id ${device.id}; only the last one resolved is " +
            "used and the others are ignored. Rename one of them in the MIDI setup of the operating system to tell " +
            "them apart.")
        }

        devicesById.updated(device.id, device)
      }

      val connectionEvents = devicesById.values.flatMap { connectedDevice =>
        handleOf(connectedDevice.id).connect(connectedDevice.info, connectedDevice.device)
      }
      val disconnectionEvents = handles.values
        .filter(handle => handle.isConnected && !devicesById.contains(handle.id))
        .flatMap(handle => forgettingIfClosed(handle)(handle.disconnect()))

      Seq.from(connectionEvents ++ disconnectionEvents)
    }

    def isDeviceAvailable(deviceId: MidiDeviceId): Boolean = handles.get(deviceId).exists(_.isConnected)

    def deviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = handles.get(deviceId).flatMap(_.info)

    def deviceIds: Seq[MidiDeviceId] = connectedHandles.map(_.id)

    def devicesInfo: Seq[MidiDeviceInfo] = connectedHandles.flatMap(_.info)

    /** Takes one reference to the device, through its live handle, created if there is none. */
    def openDevice(deviceId: MidiDeviceId): (JavaMidiDeviceHandle, Seq[MidiEvent]) = {
      val handle = handleOf(deviceId)
      val events = handle.open()
      if (handle.state == State.WaitingToOpen) {
        logger.warn(s"${direction.toString.capitalize} device $deviceId is not connected; it will be opened once it " +
          "gets connected.")
      }

      (handle, events)
    }

    def deviceHandleOf(deviceId: MidiDeviceId): Option[JavaMidiDeviceHandle] = handles.get(deviceId)

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

    private def connectedHandles: Seq[JavaMidiDeviceHandle] = handles.values.filter(_.isConnected).toSeq

    private def handleOf(deviceId: MidiDeviceId): JavaMidiDeviceHandle =
      handles.getOrElseUpdate(deviceId, JavaMidiDeviceHandle(deviceId, direction))

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
