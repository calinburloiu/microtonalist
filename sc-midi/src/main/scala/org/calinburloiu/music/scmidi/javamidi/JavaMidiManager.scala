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
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*

import java.util.concurrent.ConcurrentHashMap
import javax.sound.midi.{MidiDevice, MidiUnavailableException}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * [[MidiManager]] over Java Sound and CoreMIDI4J.
 *
 * The class has different sets of methods for inputs and outputs, because the Java MIDI API and CoreMIDI4J may
 * expose two [[MidiDevice]] ([[JavaMidiDeviceHandle]]) instances for the same physical device, one for input and the
 * other for output. Note that in this case, there is a single [[MidiDeviceId]].
 *
 * Each [[refresh]] resolves every device once through the [[JavaMidiEnvironment]], builds its [[MidiDeviceInfo]] and
 * keeps the resolved device so that it can be handed to the [[JavaMidiDeviceHandle]] when the device is opened. The
 * manager also refreshes whenever the environment reports a change, until it is closed.
 *
 * @param businessync Used for publishing [[MidiEvent]]s.
 * @param environment The Java Sound environment to scan and subscribe to; the production default is
 *                    [[CoreMidi4JEnvironment]], a fake is what a test passes.
 */
class JavaMidiManager(businessync: Businessync,
                      environment: JavaMidiEnvironment = CoreMidi4JEnvironment)
  extends MidiManager with StrictLogging {

  import JavaMidiManager.*

  private val inputEndpoint: MidiEndpoint = MidiEndpoint(MidiEndpointType.Input, businessync)
  private val outputEndpoint: MidiEndpoint = MidiEndpoint(MidiEndpointType.Output, businessync)

  private val environmentSubscription: AutoCloseable = init()

  private def init(): AutoCloseable = {
    refresh()

    // Automatically refresh when the MIDI environment has changed
    environment.subscribeToEnvironmentChanged(() => onEnvironmentChanged())
  }

  private def onEnvironmentChanged(): Unit = {
    logger.info("The MIDI environment has changed.")
    businessync.publish(MidiEnvironmentChangedEvent)
    refresh()
  }

  override def refresh(): Unit = {
    val currentInputDevices: mutable.Buffer[ConnectedDevice] = mutable.Buffer()
    val currentOutputDevices: mutable.Buffer[ConnectedDevice] = mutable.Buffer()
    for (javaInfo <- environment.deviceInfos; device <- resolveDevice(javaInfo)) {
      val connectedDevice = ConnectedDevice(device.asMidiDeviceInfo, device)

      if (connectedDevice.info.isInputDevice) {
        currentInputDevices += connectedDevice
      }
      if (connectedDevice.info.isOutputDevice) {
        currentOutputDevices += connectedDevice
      }
    }

    inputEndpoint.updateDevices(currentInputDevices)
    outputEndpoint.updateDevices(currentOutputDevices)

    inputEndpoint.purgeDisconnectedDevices()
    outputEndpoint.purgeDisconnectedDevices()
  }

  /**
   * Resolves the Java Sound device described by `javaInfo`, or `None` if it cannot be: a `MidiUnavailableException`
   * or an `IllegalArgumentException` drops the device silently; any other exception is logged and published as a
   * [[MidiDeviceFailedToConnectEvent]] before the device is dropped.
   */
  private def resolveDevice(javaInfo: MidiDevice.Info): Option[MidiDevice] = {
    try {
      Some(environment.deviceOf(javaInfo))
    } catch {
      case _: MidiUnavailableException => None
      case _: IllegalArgumentException => None
      case exception: Exception =>
        val id = javaInfo.asMidiDeviceId
        logger.error(s"Failed to connect to device $id!", exception)
        businessync.publish(MidiDeviceFailedToConnectEvent(id, exception))
        None
    }
  }

  override def close(): Unit = {
    logger.info(s"Closing MIDI connections...")
    inputEndpoint.close()
    outputEndpoint.close()
    logger.info(s"Finished closing MIDI connections.")

    environmentSubscription.close()
  }

  override def isInputAvailable(deviceId: MidiDeviceId): Boolean = inputEndpoint.isDeviceAvailable(deviceId)

  override def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = inputEndpoint.deviceInfoOf(deviceId)

  override def inputDeviceIds: Seq[MidiDeviceId] = inputEndpoint.deviceIds

  override def inputDevicesInfo: Seq[MidiDeviceInfo] = inputEndpoint.devicesInfo

  override def openInput(deviceId: MidiDeviceId): MidiDeviceHandle = inputEndpoint.openDevice(deviceId)

  override def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] =
    inputEndpoint.deviceHandleOf(deviceId)

  override def inputOpenedDevices: Seq[MidiDeviceHandle] = inputEndpoint.openedDevices

  override def closeInput(deviceId: MidiDeviceId): Unit = inputEndpoint.closeDevice(deviceId)


  override def isOutputAvailable(deviceId: MidiDeviceId): Boolean = outputEndpoint.isDeviceAvailable(deviceId)

  override def outputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] =
    outputEndpoint.deviceInfoOf(deviceId)

  override def outputDeviceIds: Seq[MidiDeviceId] = outputEndpoint.deviceIds

  override def outputDevicesInfo: Seq[MidiDeviceInfo] = outputEndpoint.devicesInfo

  override def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle = outputEndpoint.openDevice(deviceId)

  override def outputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] =
    outputEndpoint.deviceHandleOf(deviceId)

  override def outputOpenedDevices: Seq[MidiDeviceHandle] = outputEndpoint.openedDevices

  override def closeOutput(deviceId: MidiDeviceId): Unit = outputEndpoint.closeDevice(deviceId)
}

object JavaMidiManager {

  /** A device present in the environment: its API-level information and the resolved Java Sound device. */
  private case class ConnectedDevice(info: MidiDeviceInfo, device: MidiDevice) {
    def id: MidiDeviceId = info.id
  }

  /**
   * Helper class that manages either input or output MIDI devices. The reason for that is that the Java MIDI API
   * lists input and output devices separately, so the same physical device may appear twice but with the same
   * [[MidiDeviceId]].
   *
   * @param endpointType whether the devices managed are input or output devices.
   */
  private class MidiEndpoint(val endpointType: MidiEndpointType,
                             businessync: Businessync) extends AutoCloseable with StrictLogging {

    private val connectedDevices = ConcurrentHashMap[MidiDeviceId, ConnectedDevice]()
    private val openedDevicesMap = ConcurrentHashMap[MidiDeviceId, JavaMidiDeviceHandle]()

    def updateDevices(devices: Iterable[ConnectedDevice]): Unit = {
      // New devices
      for (connectedDevice <- devices) {
        val id = connectedDevice.id
        var wasConnected = false
        connectedDevices.computeIfAbsent(id, _ => {
          wasConnected = true
          connectedDevice
        })

        if (wasConnected) {
          logDebugConnectedDevice(connectedDevice.info)
          businessync.publish(MidiDeviceConnectedEvent(id))
          // TODO #288 A handle already requested to be opened for this device is not informed that the device got
          //   connected (onConnect), so it stays unconnected until openDevice is called again.
        }
      }

      // Removed devices
      val currentIds = devices.map(_.id).toSet

      for (previousId <- connectedDevices.keys.asScala if !currentIds.contains(previousId)) {
        connectedDevices.remove(previousId)
        logger.info(s"${endpointType.toString.capitalize} device $previousId was disconnected.")
        businessync.publish(MidiDeviceDisconnectedEvent(previousId))
      }
    }

    /** Remove devices that were previously opened, but now were disconnected. */
    def purgeDisconnectedDevices(): Unit = {
      val disconnectedDeviceIds = openedDevicesMap.keySet.asScala diff connectedDevices.keySet.asScala

      disconnectedDeviceIds.foreach { deviceId =>
        // TODO #288 This closes the Java device behind the handle's back and drops the handle: its state contradicts
        //   isOpen and a replugged device never reconnects to it; the map re-read below also races with closeDevice.
        val device = openedDevicesMap.get(deviceId).device

        device.foreach(_.close())

        openedDevicesMap.remove(deviceId)

        logger.info(s"${endpointType.toString.capitalize} device $deviceId was closed.")
        businessync.publish(MidiDeviceClosedEvent(deviceId))
      }
    }

    def isDeviceAvailable(deviceId: MidiDeviceId): Boolean = connectedDevices.containsKey(deviceId)

    def deviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] =
      Option(connectedDevices.get(deviceId)).map(_.info)

    def deviceIds: Seq[MidiDeviceId] = connectedDevices.keys.asScala.toSeq

    def devicesInfo: Seq[MidiDeviceInfo] = connectedDevices.values.asScala.map(_.info).toSeq

    def openDevice(deviceId: MidiDeviceId): JavaMidiDeviceHandle = {
      val deviceHandle = openedDevicesMap.computeIfAbsent(deviceId, _ => JavaMidiDeviceHandle(deviceId, businessync))

      Option(connectedDevices.get(deviceId)) match {
        case Some(connectedDevice) =>
          // TODO #288 onConnect is called even on a handle that is already open, e.g. a second track sharing the
          //   device: it closes the Java device while the handle keeps reporting State.Open, and the following open()
          //   only bumps the reference count, so the handle silently drops every message from then on.
          deviceHandle.onConnect(connectedDevice.info, connectedDevice.device)
          deviceHandle.open()
          logger.info(s"Successfully opened $endpointType device $deviceId.")
          businessync.publish(MidiDeviceOpenedEvent(deviceId))
        case None =>
          // TODO #288 The handle is not opened, so it does not wait to open and would not open once the device gets
          //   connected, contrary to MidiManager.openInput / openOutput.
          logger.warn(s"${endpointType.toString.capitalize} device $deviceId is not connected.")
      }

      deviceHandle
    }

    def deviceHandleOf(deviceId: MidiDeviceId): Option[JavaMidiDeviceHandle] = Option(openedDevicesMap.get(deviceId))

    def openedDevices: Seq[JavaMidiDeviceHandle] = openedDevicesMap.values.asScala.toSeq

    def closeDevice(deviceId: MidiDeviceId): Unit = {
      // TODO #288 This releases a single open of the handle and then drops it whatever its reference count, so a
      //   device another track still holds stays open but unmanaged, and close() never closes it; a handle that is not
      //   open, such as one requested while its device was disconnected, is neither released nor removed.
      deviceHandleOf(deviceId) match {
        case Some(openedDevice) if openedDevice.isOpen =>
          logger.info(s"Closing $endpointType device $deviceId...")
          openedDevice.close()
          openedDevicesMap.remove(deviceId)
          logger.info(s"Successfully closed $endpointType device $deviceId.")
        case _ => // Do nothing
      }
    }

    override def close(): Unit = {
      openedDevicesMap.keys.asScala.foreach { deviceId =>
        closeDevice(deviceId)
      }
    }

    @inline
    private def logDebugConnectedDevice(info: MidiDeviceInfo): Unit = {
      logger.whenDebugEnabled {
        val (handlerType, maxHandlers) = if (endpointType == MidiEndpointType.Input) {
          ("transmitters", info.transmittersLimit)
        } else {
          ("receivers", info.receiversLimit)
        }

        logger.debug(s"${endpointType.toString.capitalize} device ${info.id} with $maxHandlers $handlerType was " +
          s"connected.")
      }
    }
  }
}
