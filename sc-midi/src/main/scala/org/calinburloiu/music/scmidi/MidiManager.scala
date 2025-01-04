/*
 * Copyright 2021 Calin-Andrei Burloiu
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
import org.calinburloiu.businessync.{Businessync, BusinessyncEvent}
import uk.co.xfactorylibrarians.coremidi4j.{CoreMidiDeviceProvider, CoreMidiNotification}

import javax.sound.midi.{MidiDevice, MidiSystem, Receiver, Transmitter}
import scala.collection.mutable
import scala.util.Try

class MidiManager(businessync: Businessync) extends AutoCloseable with StrictLogging {

  import MidiManager._

  private val inputEndpoint: MidiEndpoint = new MidiEndpoint(MidiEndpointType.Input, businessync)
  private val outputEndpoint: MidiEndpoint = new MidiEndpoint(MidiEndpointType.Output, businessync)

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

  def refresh(): Unit = {
    // Alternative to `javax.sound.midi.MidiSystem.getMidiDeviceInfo()` to make Java MIDI work on Mac.
    // This should also work on Windows.
    val deviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo
    val devices = deviceInfoArray.map(MidiSystem.getMidiDevice)

    val currentInputDevices: mutable.Buffer[MidiDeviceHandle] = mutable.Buffer()
    val currentOutputDevices: mutable.Buffer[MidiDeviceHandle] = mutable.Buffer()

    devices.foreach { midiDevice =>
      val deviceHandle = MidiDeviceHandle(midiDevice)

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
    logger.info(s"Closing ${getClass.getSimpleName}...")
    inputEndpoint.close()
    outputEndpoint.close()
    CoreMidiDeviceProvider.removeNotificationListener(onMidiNotification)
    logger.info(s"Finished closing ${getClass.getSimpleName}.")
  }


  def inputDeviceInfoOf(deviceId: MidiDeviceId): MidiDevice.Info = inputEndpoint.deviceInfoOf(deviceId)

  def inputDeviceIds: Seq[MidiDeviceId] = inputEndpoint.deviceIds

  def openInput(deviceId: MidiDeviceId): MidiDeviceHandle = inputEndpoint.openDevice(deviceId)

  def openFirstAvailableInput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] =
    inputEndpoint.openFirstAvailableDevice(deviceIds)

  def isInputOpened(deviceId: MidiDeviceId): Boolean = inputEndpoint.isDeviceOpened(deviceId)

  def inputTransmitterOf(deviceId: MidiDeviceId): Transmitter = inputEndpoint.transmitterOf(deviceId)

  def inputDeviceHandleOf(deviceId: MidiDeviceId): MidiDeviceHandle = inputEndpoint.deviceHandleOf(deviceId)

  def closeInput(deviceId: MidiDeviceId): Unit = inputEndpoint.closeDevice(deviceId)


  def outputDeviceInfoOf(deviceId: MidiDeviceId): MidiDevice.Info = outputEndpoint.deviceInfoOf(deviceId)

  def outputDeviceIds: Seq[MidiDeviceId] = outputEndpoint.deviceIds

  def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle = outputEndpoint.openDevice(deviceId)

  def openFirstAvailableOutput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] =
    outputEndpoint.openFirstAvailableDevice(deviceIds)

  def isOutputOpened(deviceId: MidiDeviceId): Boolean = outputEndpoint.isDeviceOpened(deviceId)

  def outputReceiverOf(deviceId: MidiDeviceId): Receiver = outputEndpoint.receiverOf(deviceId)

  def outputDeviceHandleOf(deviceId: MidiDeviceId): MidiDeviceHandle = outputEndpoint.deviceHandleOf(deviceId)

  def closeOutput(deviceId: MidiDeviceId): Unit = outputEndpoint.closeDevice(deviceId)
}

object MidiManager {

  abstract class MidiEvent extends BusinessyncEvent

  case object MidiEnvironmentChangedEvent extends MidiEvent

  case class MidiDeviceAddedEvent(deviceId: MidiDeviceId) extends MidiEvent

  case class MidiDeviceRemovedEvent(deviceId: MidiDeviceId) extends MidiEvent

  case class MidiDeviceDisconnectedEvent(deviceId: MidiDeviceId) extends MidiEvent


  private sealed abstract class MidiEndpointType(name: String) {
    override def toString: String = name
  }

  private object MidiEndpointType {
    case object Input extends MidiEndpointType("input")

    case object Output extends MidiEndpointType("output")
  }

  /**
   * Helper class that manages either input or output MIDI devices. The reason for that is that the Java MIDI API
   * lists input and output devices separately, so the same device may appear twice (with the same [[MidiDeviceId]].
   *
   * @param endpointType whether the devices managed are input or output devices.
   */
  private class MidiEndpoint(val endpointType: MidiEndpointType,
                             businessync: Businessync) extends AutoCloseable with StrictLogging {

    private val devicesIdToInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
    private val openedDevices = mutable.Map[MidiDeviceId, MidiDeviceHandle]()

    def updateDevices(deviceHandles: Iterable[MidiDeviceHandle]): Unit = {
      val currentIds = deviceHandles.map(_.id).toSet

      // New devices
      for (currentDeviceHandle <- deviceHandles; id = currentDeviceHandle.id if !devicesIdToInfo.contains(id)) {
        devicesIdToInfo.update(id, currentDeviceHandle.info)
        logDebugDetectedDevice(currentDeviceHandle)
        businessync.publish(MidiDeviceAddedEvent(id))
      }

      // Removed devices
      for (previousId <- devicesIdToInfo.keys if !currentIds.contains(previousId)) {
        devicesIdToInfo.remove(previousId)
        logger.info(s"${endpointType.toString.capitalize} device $previousId was removed.")
        businessync.publish(MidiDeviceRemovedEvent(previousId))
      }
    }

    /** Remove devices that were previously opened, but now were disconnected. */
    def purgeDisconnectedDevices(): Unit = {
      val deviceIds = devicesIdToInfo.keySet
      val disconnectedDeviceIds = openedDevices.keySet diff deviceIds

      disconnectedDeviceIds.foreach { deviceId =>
        val device = openedDevices(deviceId).midiDevice

        Try(device.close()).recover {
          case exception => logger.info(s"${endpointType.toString.capitalize} device $deviceId is already closed.", exception)
        }

        openedDevices.remove(deviceId)

        logger.info(s"${endpointType.toString.capitalize} device $deviceId was disconnected.")
        businessync.publish(MidiDeviceDisconnectedEvent(deviceId))
      }
    }

    def deviceInfoOf(deviceId: MidiDeviceId): MidiDevice.Info = devicesIdToInfo(deviceId)

    def deviceIds: Seq[MidiDeviceId] = devicesIdToInfo.keys.toSeq

    def openDevice(deviceId: MidiDeviceId): MidiDeviceHandle = {
      val result = Try {
        val deviceInfo = devicesIdToInfo(deviceId)
        val midiDevice = MidiSystem.getMidiDevice(deviceInfo)

        val deviceHandle = MidiDeviceHandle(midiDevice)
        deviceHandle.open()
        openedDevices.update(deviceId, deviceHandle)
        logger.info(s"Successfully opened $endpointType device $deviceId.")

        deviceHandle
      }

      result.recover {
        case _: NoSuchElementException => logger.warn(s"${endpointType.toString.capitalize} device $deviceId is not available.")
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

    def isDeviceOpened(deviceId: MidiDeviceId): Boolean = openedDevices.get(deviceId).exists(_.isOpen)

    def transmitterOf(deviceId: MidiDeviceId): Transmitter = openedDevices(deviceId).transmitter

    def receiverOf(deviceId: MidiDeviceId): Receiver = openedDevices(deviceId).receiver

    def deviceHandleOf(deviceId: MidiDeviceId): MidiDeviceHandle = openedDevices(deviceId)

    def closeDevice(deviceId: MidiDeviceId): Unit = {
      val openedDevice = openedDevices(deviceId)

      logger.info(s"Closing $endpointType device $deviceId...")
      openedDevice.midiDevice.close()
      openedDevices.remove(deviceId)
      logger.info(s"Successfully $endpointType closed device $deviceId.")
    }

    override def close(): Unit = {
      openedDevices.keys.foreach { deviceId =>
        Try {
          closeDevice(deviceId)
        }.recover {
          case exception => logger.error(s"Failed to close $endpointType device $deviceId!", exception)
        }
      }
    }

    @inline
    private def logDebugDetectedDevice(deviceHandle: MidiDeviceHandle): Unit = {
      logger.whenDebugEnabled {
        val deviceId = deviceHandle.id
        val midiDevice = deviceHandle.midiDevice
        val (handlerType, maxHandlers) = if (endpointType == MidiEndpointType.Input) {
          ("transmitters", midiDevice.getMaxTransmitters)
        } else {
          ("receivers", midiDevice.getMaxReceivers)
        }
        val maxHandlersStr = if (maxHandlers == -1) "unlimited" else maxHandlers.toString

        logger.debug(s"Detected $endpointType device $deviceId with $maxHandlersStr $handlerType.")
      }
    }
  }
}
