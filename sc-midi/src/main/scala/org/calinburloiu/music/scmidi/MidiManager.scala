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

import com.typesafe.scalalogging.{Logger, StrictLogging}
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider

import javax.sound.midi.{MidiDevice, MidiSystem, Receiver, Transmitter}
import scala.collection.mutable
import scala.util.Try

class MidiManager extends AutoCloseable with StrictLogging {
  import MidiManager._

  private val inputDevicesInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedInputDevices = mutable.Map[MidiDeviceId, OpenedInputDevice]()

  private val outputDevicesInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedOutputDevices = mutable.Map[MidiDeviceId, OpenedOutputDevice]()

  refresh()

  def deviceInfoOf(deviceId: MidiDeviceId): MidiDevice.Info =
    inputDevicesInfo.getOrElse(deviceId, outputDevicesInfo(deviceId))

  // TODO #88 Improve to allow automatic refresh by using listeners
  def refresh(): Unit = {
    // Alternative to `javax.sound.midi.MidiSystem.getMidiDeviceInfo()` to make Java MIDI work on Mac.
    // This should also work on Windows.
    val deviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo
    val devices = deviceInfoArray.map(MidiSystem.getMidiDevice)

    devices.foreach { device =>
      val deviceInfo = device.getDeviceInfo
      val deviceId = MidiDeviceId(deviceInfo)

      if (isInputDevice(device)) {
        inputDevicesInfo.update(deviceId, deviceInfo)
        logDebugDetectedDevice(deviceId, DeviceEndpoint.Input, device.getMaxTransmitters)
      }

      if (isOutputDevice(device)) {
        outputDevicesInfo.update(deviceId, deviceInfo)
        logDebugDetectedDevice(deviceId, DeviceEndpoint.Output, device.getMaxReceivers)
      }
    }

    /** Remove opened devices that were previously opened, but now they don't appear anymore. */
    def removeDisappearedOpenedDevices[D <: OpenedDevice](openedDevices: mutable.Map[MidiDeviceId, D],
                                                          deviceIds: scala.collection.Set[MidiDeviceId]): Unit = {
      val disappearedOpenedDeviceIds = openedDevices.keySet diff deviceIds
      disappearedOpenedDeviceIds.foreach { deviceId =>
        val device = openedDevices(deviceId).device
        Try(device.close()).recover {
          case exception => logger.warn(s"Failed to close disappeared device \"$deviceId\"!", exception)
        }
        openedDevices.remove(deviceId)
        logger.info(s"Previously opened device \"$deviceId\" disappeared; it was probably disconnected.")
      }
    }

    removeDisappearedOpenedDevices(openedInputDevices, inputDevicesInfo.keySet)
    removeDisappearedOpenedDevices(openedOutputDevices, outputDevicesInfo.keySet)
  }

  def inputDeviceIds: Seq[MidiDeviceId] = inputDevicesInfo.keys.toSeq

  def openInput(deviceId: MidiDeviceId): Try[Transmitter] = {
    val result = Try {
      val deviceInfo = inputDevicesInfo(deviceId)
      val device = MidiSystem.getMidiDevice(deviceInfo)
      val transmitter = device.getTransmitter

      device.open()
      openedInputDevices.update(deviceId, OpenedInputDevice(device, transmitter))
      logger.info(s"Successfully opened input device \"$deviceId\".")

      transmitter
    }

    result.recover {
      case _: NoSuchElementException => logger.warn(s"Input device \"$deviceId\" is not available.")
      case exception => logErrorOpenDevice(deviceId, DeviceEndpoint.Input, exception)
    }
    result
  }

  // TODO #64 To be removed when support for tracks files is added
  @deprecated
  def openFirstAvailableInput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceId] =
    openFirstAvailableDevice(deviceIds, DeviceEndpoint.Input, openInput)

  def isInputOpened(deviceId: MidiDeviceId): Boolean = isDeviceOpened(deviceId, openedInputDevices)

  def inputTransmitter(deviceId: MidiDeviceId): Transmitter = openedInputDevices(deviceId).transmitter

  def inputDevice(deviceId: MidiDeviceId): MidiDevice = openedInputDevices(deviceId).device

  def closeInput(deviceId: MidiDeviceId): Try[Unit] = closeDevice(deviceId, openedInputDevices)

  def outputDeviceIds: Seq[MidiDeviceId] = outputDevicesInfo.keys.toSeq

  def openOutput(deviceId: MidiDeviceId): Try[Receiver] = {
    val result = Try {
      val deviceInfo = outputDevicesInfo(deviceId)
      val device = MidiSystem.getMidiDevice(deviceInfo)
      val receiver = device.getReceiver

      device.open()
      openedOutputDevices.update(deviceId, OpenedOutputDevice(device, receiver))
      logger.info(s"Successfully opened output device \"$deviceId\".")

      receiver
    }

    result.recover {
      case _: NoSuchElementException => logger.warn(s"Output device \"$deviceId\" not available.")
      case exception => logErrorOpenDevice(deviceId, DeviceEndpoint.Output, exception)
    }
    result
  }

  // TODO #64 To be removed when support for tracks files is added
  @deprecated
  def openFirstAvailableOutput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceId] =
    openFirstAvailableDevice(deviceIds, DeviceEndpoint.Output, openOutput)

  def isOutputOpened(deviceId: MidiDeviceId): Boolean = isDeviceOpened(deviceId, openedOutputDevices)

  def outputReceiver(deviceId: MidiDeviceId): Receiver = openedOutputDevices(deviceId).receiver

  def outputDevice(deviceId: MidiDeviceId): MidiDevice = openedOutputDevices(deviceId).device

  def closeOutput(deviceId: MidiDeviceId): Try[Unit] = closeDevice(deviceId, openedOutputDevices)

  override def close(): Unit = {
    logger.info(s"Closing ${getClass.getSimpleName}...")
    openedInputDevices.keys.foreach { deviceId =>
      closeDevice(deviceId, openedInputDevices).recover {
        case exception => logger.error(s"Failed to close input device \"$deviceId\"!", exception)
      }
    }
    openedOutputDevices.keys.foreach { deviceId =>
      closeDevice(deviceId, openedOutputDevices).recover {
        case exception => logger.error(s"Failed to close output device \"$deviceId\"!", exception)
      }
    }
    logger.info(s"Finished closing ${getClass.getSimpleName}.")
  }

  // TODO #64 To be removed when support for tracks files is added
  @deprecated
  private def openFirstAvailableDevice(deviceIds: Seq[MidiDeviceId], deviceEndpoint: DeviceEndpoint,
                                       openFunc: MidiDeviceId => Try[_]): Option[MidiDeviceId] = {
    deviceIds.to(LazyList)
      .map { deviceId =>
        logger.info(s"Attempting to open $deviceEndpoint device \"$deviceId\"...")
        (deviceId, openFunc(deviceId))
      }
      .find(_._2.isSuccess)
      .map(_._1)
  }

  private def isDeviceOpened[D <: OpenedDevice](deviceId: MidiDeviceId,
                                                openedDevices: mutable.Map[MidiDeviceId, D]): Boolean =
    openedDevices.get(deviceId).exists(_.device.isOpen)

  private def closeDevice[D <: OpenedDevice](deviceId: MidiDeviceId, openedDevices: mutable.Map[MidiDeviceId, D])
  : Try[Unit] = Try {
    val openedDevice = openedDevices(deviceId)
    logger.info(s"Closing ${openedDevice.deviceEndpoint} device \"$deviceId\"...")
    openedDevice.device.close()
    openedDevices.remove(deviceId)
    logger.info(s"Successfully closed ${openedDevice.deviceEndpoint} device \"$deviceId\".")
  }

  @inline
  private def logDebugDetectedDevice(deviceId: MidiDeviceId, deviceEndpoint: DeviceEndpoint, maxHandlers: Int): Unit = {
    logger.debug {
      val maxHandlersStr = if (maxHandlers == -1) "unlimited" else maxHandlers.toString
      val handlerType = if (deviceEndpoint == DeviceEndpoint.Input) "transmitters" else "receivers"
      s"Detected $deviceEndpoint device \"$deviceId\" with $maxHandlersStr $handlerType."
    }
  }

  @inline
  private def logErrorOpenDevice(deviceId: MidiDeviceId, deviceEndpoint: DeviceEndpoint, exception: Throwable): Unit = {
    logger.error(s"Failed to open $deviceEndpoint device \"$deviceId\".", exception)
  }
}

object MidiManager {
  def isInputDevice(device: MidiDevice): Boolean = {
    val maxTransmitters = device.getMaxTransmitters
    maxTransmitters == -1 /* unlimited */ || maxTransmitters > 0
  }

  def isOutputDevice(device: MidiDevice): Boolean = {
    val maxReceivers = device.getMaxReceivers
    maxReceivers == -1 /* unlimited */ || maxReceivers > 0
  }
}

sealed abstract class DeviceEndpoint(protected val name: String) {
  override def toString: String = name
}

object DeviceEndpoint {
  case object Input extends DeviceEndpoint("input")

  case object Output extends DeviceEndpoint("output")
}

sealed trait OpenedDevice {
  val device: MidiDevice

  def deviceEndpoint: DeviceEndpoint
}

case class OpenedInputDevice(override val device: MidiDevice, transmitter: Transmitter) extends OpenedDevice {
  override def deviceEndpoint: DeviceEndpoint = DeviceEndpoint.Input
}

case class OpenedOutputDevice(override val device: MidiDevice, receiver: Receiver) extends OpenedDevice {
  override def deviceEndpoint: DeviceEndpoint = DeviceEndpoint.Output
}
