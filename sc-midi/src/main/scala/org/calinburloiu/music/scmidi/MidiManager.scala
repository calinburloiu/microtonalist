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
import uk.co.xfactorylibrarians.coremidi4j.{CoreMidiDeviceProvider, CoreMidiNotification}

import java.util.concurrent.ConcurrentHashMap
import javax.sound.midi.MidiDevice
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Class that manages connections and gives information about MIDI devices.
 *
 * The class has different sets of methods for inputs and outputs, because the Java MIDI API and CoreMIDI4J may
 * expose two [[MidiDevice]] ([[MidiDeviceHandle]]) instances for the same physical devices, one for input and the
 * other for output. Note that in this case, there is a single [[MidiDeviceId]].
 */
class MidiManager(businessync: Businessync) extends AutoCloseable with StrictLogging {

  import MidiManager.*

  private val inputEndpoint: MidiEndpoint = new MidiEndpoint(MidiEndpointType.Input, businessync)
  private val outputEndpoint: MidiEndpoint = new MidiEndpoint(MidiEndpointType.Output, businessync)

  private val onMidiNotification: CoreMidiNotification = () => {
    logger.info("The MIDI environment has changed.")
    businessync.publish(MidiEnvironmentChangedEvent)
    refresh()
  }

  init()

  /**
   * Rescans the environment for MIDI device information and updates the class internal state.
   */
  def refresh(): Unit = {
    // Alternative to `javax.sound.midi.MidiSystem.getMidiDeviceInfo()` to make Java MIDI work on Mac.
    // This should also work on Windows.
    val deviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo

    val currentInputDevices: mutable.Buffer[MidiDeviceHandle] = mutable.Buffer()
    val currentOutputDevices: mutable.Buffer[MidiDeviceHandle] = mutable.Buffer()
    deviceInfoArray.foreach { deviceInfo =>
      val deviceHandle = MidiDeviceHandle(deviceInfo)

      if (deviceHandle.isInputDevice) {
        currentInputDevices += deviceHandle
      }
      if (deviceHandle.isOutputDevice) {
        currentOutputDevices += deviceHandle
      }
    }

    inputEndpoint.updateDevices(currentInputDevices)
    outputEndpoint.updateDevices(currentOutputDevices)

    inputEndpoint.purgeDisconnectedDevices()
    outputEndpoint.purgeDisconnectedDevices()
  }

  override def close(): Unit = {
    logger.info(s"Closing MIDI connections...")
    inputEndpoint.close()
    outputEndpoint.close()
    logger.info(s"Finished closing MIDI connections.")

    CoreMidiDeviceProvider.removeNotificationListener(onMidiNotification)
  }

  def isInputAvailable(deviceId: MidiDeviceId): Boolean = inputEndpoint.isDeviceAvailable(deviceId)

  def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDevice.Info] = inputEndpoint.deviceInfoOf(deviceId)

  def inputDeviceIds: Seq[MidiDeviceId] = inputEndpoint.deviceIds

  def inputDevicesInfo: Seq[MidiDevice.Info] = inputEndpoint.devicesInfo

  /**
   * Opens an input connection to a MIDI device based on its unique identifiers.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openInput(deviceId: MidiDeviceId): MidiDeviceHandle = inputEndpoint.openDevice(deviceId)

  /**
   * Tries to sequentially open a connection with the first output device available from the provided sequence (in
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

  def outputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDevice.Info] = outputEndpoint.deviceInfoOf(deviceId)

  def outputDeviceIds: Seq[MidiDeviceId] = outputEndpoint.deviceIds

  def outputDevicesInfo: Seq[MidiDevice.Info] = outputEndpoint.devicesInfo

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

  private def init(): Unit = {
    refresh()

    // Automatically refresh when the MIDI environment has changed
    CoreMidiDeviceProvider.addNotificationListener(onMidiNotification)
  }
}

object MidiManager {

  private sealed abstract class MidiEndpointType(name: String) {
    override def toString: String = name
  }

  private object MidiEndpointType {
    case object Input extends MidiEndpointType("input")

    case object Output extends MidiEndpointType("output")
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

    // TODO #131 Use a java.util.concurrent.ConcurrentMap instead with compute* methods to allow thread-safe
    //  computation of the value
    private val devicesIdToInfo = ConcurrentHashMap[MidiDeviceId, MidiDevice.Info]()
    private val openedDevicesMap = ConcurrentHashMap[MidiDeviceId, MidiDeviceHandle]()

    def updateDevices(deviceHandles: Iterable[MidiDeviceHandle]): Unit = {
      // New devices
      for (
        currentDeviceHandle <- deviceHandles;
        id = currentDeviceHandle.id;
        deviceInfo <- currentDeviceHandle.info
      ) {
        var wasAdded = false
        devicesIdToInfo.computeIfAbsent(id, _ => {
          wasAdded = true
          deviceInfo
        })

        if (wasAdded) {
          logDebugConnectedDevice(currentDeviceHandle)
          businessync.publish(MidiDeviceConnectedEvent(id))
        }
      }

      // Removed devices
      val currentIds = deviceHandles.map(_.id).toSet

      for (previousId <- devicesIdToInfo.keys.asScala if !currentIds.contains(previousId)) {
        devicesIdToInfo.remove(previousId)
        logger.info(s"${endpointType.toString.capitalize} device $previousId was disconnected.")
        businessync.publish(MidiDeviceDisconnectedEvent(previousId))
      }
    }

    /** Remove devices that were previously opened, but now were disconnected. */
    def purgeDisconnectedDevices(): Unit = {
      val disconnectedDeviceIds = openedDevicesMap.keySet.asScala diff devicesIdToInfo.keySet.asScala

      disconnectedDeviceIds.foreach { deviceId =>
        val device = openedDevicesMap.get(deviceId).device

        try {
          device.foreach(_.close())
        } catch {
          case throwable: Throwable =>
            logger.info(s"Failed to close $endpointType device $deviceId! Was it already closed?", throwable)
        }

        openedDevicesMap.remove(deviceId)

        logger.info(s"${endpointType.toString.capitalize} device $deviceId was closed.")
        businessync.publish(MidiDeviceClosedEvent(deviceId))
      }
    }

    def isDeviceAvailable(deviceId: MidiDeviceId): Boolean = devicesIdToInfo.containsKey(deviceId)

    def deviceInfoOf(deviceId: MidiDeviceId): Option[MidiDevice.Info] = Option(devicesIdToInfo.get(deviceId))

    def deviceIds: Seq[MidiDeviceId] = devicesIdToInfo.keys.asScala.toSeq

    def devicesInfo: Seq[MidiDevice.Info] = devicesIdToInfo.values.asScala.toSeq

    def openDevice(deviceId: MidiDeviceId): MidiDeviceHandle = {
      val result = Try {
        val deviceInfo = deviceInfoOf(deviceId).get
        val deviceHandle = openedDevicesMap.computeIfAbsent(deviceId, _ => MidiDeviceHandle(deviceInfo))

        deviceHandle.open()
        logger.info(s"Successfully opened $endpointType device $deviceId.")
        businessync.publish(MidiDeviceOpenedEvent(deviceId))

        deviceHandle
      }

      result.recover {
        case _: NoSuchElementException => logger.warn(s"${endpointType.toString.capitalize} device $deviceId is not " +
          s"connected.")
        case exception => logger.error(s"Failed to open $endpointType device $deviceId.", exception)
      }

      result.get
    }

    def openFirstAvailableDevice(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] = {
      deviceIds.to(LazyList)
        .map { deviceId =>
          logger.info(s"Attempting to open $endpointType device $deviceId...")
          Try {
            openDevice(deviceId)
          }
        }
        .find(_.isSuccess)
        .map(_.get)
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
        Try {
          closeDevice(deviceId)
        }.recover {
          case exception => logger.error(s"Failed to close $endpointType device $deviceId!", exception)
        }
      }
    }

    @inline
    private def logDebugConnectedDevice(deviceHandle: MidiDeviceHandle): Unit = {
      logger.whenDebugEnabled {
        val deviceId = deviceHandle.id
        val midiDevice = deviceHandle.device
        val (handlerType, maxHandlers) = if (endpointType == MidiEndpointType.Input) {
          ("transmitters", midiDevice.map(_.getMaxTransmitters).getOrElse(0))
        } else {
          ("receivers", midiDevice.map(_.getMaxReceivers).getOrElse(0))
        }
        val maxHandlersStr = if (maxHandlers == -1) "unlimited" else maxHandlers.toString

        logger.debug(s"${endpointType.toString.capitalize} device $deviceId with $maxHandlersStr $handlerType was " +
          s"connected.")
      }
    }
  }
}
