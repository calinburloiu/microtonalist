/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import uk.co.xfactorylibrarians.coremidi4j.{CoreMidiDeviceProvider, CoreMidiNotification}

import java.util.concurrent.ConcurrentHashMap
import javax.sound.midi.{MidiDevice, MidiSystem, MidiUnavailableException}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Class that manages connections and gives information about MIDI devices.
 *
 * The class has different sets of methods for inputs and outputs, because the Java MIDI API and CoreMIDI4J may
 * expose two [[MidiDevice]] ([[MidiDeviceHandle]]) instances for the same physical devices, one for input and the
 * other for output. Note that in this case, there is a single [[MidiDeviceId]].
 *
 * Each [[refresh]] resolves every device once, builds its [[MidiDeviceInfo]] and keeps the resolved device so that
 * it can be handed to the [[MidiDeviceHandle]] when the device is opened.
 */
class MidiManager(businessync: Businessync) extends AutoCloseable with StrictLogging {

  import MidiManager.*

  private val inputEndpoint: MidiEndpoint = MidiEndpoint(MidiEndpointType.Input, businessync)
  private val outputEndpoint: MidiEndpoint = MidiEndpoint(MidiEndpointType.Output, businessync)

  private val onMidiNotification: CoreMidiNotification = () => {
    logger.info("The MIDI environment has changed.")
    businessync.publish(MidiEnvironmentChangedEvent)
    refresh()
  }

  init()

  private def init(): Unit = {
    refresh()

    // Automatically refresh when the MIDI environment has changed
    CoreMidiDeviceProvider.addNotificationListener(onMidiNotification)
  }

  /**
   * Rescans the environment for MIDI device information and updates the class internal state.
   */
  def refresh(): Unit = {
    // Alternative to `javax.sound.midi.MidiSystem.getMidiDeviceInfo()` to make Java MIDI work on Mac.
    // This should also work on Windows.
    val deviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo

    val currentInputDevices: mutable.Buffer[ConnectedDevice] = mutable.Buffer()
    val currentOutputDevices: mutable.Buffer[ConnectedDevice] = mutable.Buffer()
    for (javaInfo <- deviceInfoArray; device <- resolveDevice(javaInfo)) {
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
      Some(MidiSystem.getMidiDevice(javaInfo))
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

    CoreMidiDeviceProvider.removeNotificationListener(onMidiNotification)
  }

  def isInputAvailable(deviceId: MidiDeviceId): Boolean = inputEndpoint.isDeviceAvailable(deviceId)

  def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = inputEndpoint.deviceInfoOf(deviceId)

  def inputDeviceIds: Seq[MidiDeviceId] = inputEndpoint.deviceIds

  def inputDevicesInfo: Seq[MidiDeviceInfo] = inputEndpoint.devicesInfo

  /**
   * Opens an input connection to a MIDI device based on its unique identifiers.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openInput(deviceId: MidiDeviceId): MidiDeviceHandle = inputEndpoint.openDevice(deviceId)

  /**
   * Tries to sequentially open a connection with the first input device available from the provided sequence (in
   * that order).
   *
   * @param deviceIds A sequence of unique identifiers of the devices.
   * @return a handle object for the device that succeeded.
   */
  def openFirstAvailableInput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] =
    inputEndpoint.openFirstAvailableDevice(deviceIds)

  def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = inputEndpoint.deviceHandleOf(deviceId)

  def inputOpenedDevices: Seq[MidiDeviceHandle] = inputEndpoint.openedDevices

  def closeInput(deviceId: MidiDeviceId): Unit = inputEndpoint.closeDevice(deviceId)


  def isOutputAvailable(deviceId: MidiDeviceId): Boolean = outputEndpoint.isDeviceAvailable(deviceId)

  def outputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = outputEndpoint.deviceInfoOf(deviceId)

  def outputDeviceIds: Seq[MidiDeviceId] = outputEndpoint.deviceIds

  def outputDevicesInfo: Seq[MidiDeviceInfo] = outputEndpoint.devicesInfo

  /**
   * Opens an output connection to a MIDI device based on its unique identifiers.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle = outputEndpoint.openDevice(deviceId)

  /**
   * Tries to sequentially open a connection with the first output device available from the provided sequence (in
   * that order).
   *
   * @param deviceIds A sequence of unique identifiers of the devices.
   * @return a handle object for the device that succeeded.
   */
  def openFirstAvailableOutput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] =
    outputEndpoint.openFirstAvailableDevice(deviceIds)

  def outputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = outputEndpoint.deviceHandleOf(deviceId)

  def outputOpenedDevices: Seq[MidiDeviceHandle] = outputEndpoint.openedDevices

  def closeOutput(deviceId: MidiDeviceId): Unit = outputEndpoint.closeDevice(deviceId)
}

object MidiManager {

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
    private val openedDevicesMap = ConcurrentHashMap[MidiDeviceId, MidiDeviceHandle]()

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

    def openDevice(deviceId: MidiDeviceId): MidiDeviceHandle = {
      val deviceHandle = openedDevicesMap.computeIfAbsent(deviceId, _ => MidiDeviceHandle(deviceId, businessync))

      Option(connectedDevices.get(deviceId)) match {
        case Some(connectedDevice) =>
          deviceHandle.onConnect(connectedDevice.info, connectedDevice.device)
          deviceHandle.open()
          logger.info(s"Successfully opened $endpointType device $deviceId.")
          businessync.publish(MidiDeviceOpenedEvent(deviceId))
        case None => logger.warn(s"${endpointType.toString.capitalize} device $deviceId is not connected.")
      }

      deviceHandle
    }

    def openFirstAvailableDevice(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] = {
      deviceIds.to(LazyList)
        .map { deviceId =>
          logger.info(s"Attempting to open $endpointType device $deviceId...")
          openDevice(deviceId)
        }
        .find(_.isOpen)
    }

    def deviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = Option(openedDevicesMap.get(deviceId))

    def openedDevices: Seq[MidiDeviceHandle] = openedDevicesMap.values.asScala.toSeq

    def closeDevice(deviceId: MidiDeviceId): Unit = {
      deviceHandleOf(deviceId) match {
        case Some(openedDevice) if openedDevice.isOpen =>
          logger.info(s"Closing $endpointType device $deviceId...")
          openedDevice.close()
          openedDevicesMap.remove(deviceId)
          logger.info(s"Successfully $endpointType closed device $deviceId.")
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
          ("transmitters", info.maxTransmitters)
        } else {
          ("receivers", info.maxReceivers)
        }

        logger.debug(s"${endpointType.toString.capitalize} device ${info.id} with $maxHandlers $handlerType was " +
          s"connected.")
      }
    }
  }
}
