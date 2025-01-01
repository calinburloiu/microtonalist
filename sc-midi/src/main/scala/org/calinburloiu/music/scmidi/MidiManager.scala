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
import org.calinburloiu.music.scmidi
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider

import javax.sound.midi.{MidiDevice, MidiSystem, Receiver, Transmitter}
import scala.collection.mutable
import scala.util.Try

class MidiManager extends AutoCloseable with StrictLogging {

  private val devicesIdToInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedDevices = mutable.Map[MidiDeviceId, MidiDeviceHandle]()

  refresh()

  def deviceInfoOf(deviceId: MidiDeviceId): MidiDevice.Info = devicesIdToInfo(deviceId)

  // TODO #88 Improve to allow automatic refresh by using listeners
  def refresh(): Unit = {
    // Alternative to `javax.sound.midi.MidiSystem.getMidiDeviceInfo()` to make Java MIDI work on Mac.
    // This should also work on Windows.
    val deviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo
    val devices = deviceInfoArray.map(MidiSystem.getMidiDevice)

    devices.foreach { device =>
      val deviceInfo = device.getDeviceInfo
      val deviceId = MidiDeviceId(deviceInfo)
      devicesIdToInfo.update(deviceId, deviceInfo)

      logDebugDetectedDevice(deviceId, device)
    }

    removeDisappearedOpenedDevices()
  }

  def deviceIds: Seq[MidiDeviceId] = devicesIdToInfo.keys.toSeq

  def inputDeviceIds: Seq[MidiDeviceId] = deviceIds.filter { deviceId =>
    devicesIdToInfo.get(deviceId).exists { deviceInfo =>
      val midiDevice = MidiSystem.getMidiDevice(deviceInfo)
      scmidi.isInputDevice(midiDevice)
    }
  }

  def outputDeviceIds: Seq[MidiDeviceId] = deviceIds.filter { deviceId =>
    devicesIdToInfo.get(deviceId).exists { deviceInfo =>
      val midiDevice = MidiSystem.getMidiDevice(deviceInfo)
      scmidi.isOutputDevice(midiDevice)
    }
  }

  def openDevice(deviceId: MidiDeviceId): MidiDeviceHandle = {
    val result = Try {
      val deviceInfo = devicesIdToInfo(deviceId)
      val midiDevice = MidiSystem.getMidiDevice(deviceInfo)

      midiDevice.open()
      val openedMidiDevice = MidiDeviceHandle(midiDevice)
      openedDevices.update(deviceId, openedMidiDevice)
      logger.info(s"Successfully opened device $deviceId.")

      openedMidiDevice
    }

    result.recover {
      case _: NoSuchElementException => logger.warn(s"Device $deviceId is not available.")
      case exception => logger.error(s"Failed to open device $deviceId.", exception)
    }

    result.get
  }

  def openFirstAvailableDevice(deviceIds: Seq[MidiDeviceId],
                               filter: MidiDeviceHandle => Boolean = _ => true): Option[MidiDeviceHandle] = {
    deviceIds.to(LazyList)
      .map { deviceId =>
        logger.info(s"Attempting to open device $deviceId...")
        Try {
          openDevice(deviceId)
        }.toOption.filter(filter)
      }
      .find(_.isDefined)
      .flatten
  }

  def isDeviceOpened(deviceId: MidiDeviceId): Boolean = openedDevices.get(deviceId).exists(_.isOpen)

  def transmitterOf(deviceId: MidiDeviceId): Transmitter = openedDevices(deviceId).transmitter

  def receiverOf(deviceId: MidiDeviceId): Receiver = openedDevices(deviceId).receiver

  def openedDeviceOf(deviceId: MidiDeviceId): MidiDeviceHandle = openedDevices(deviceId)

  def closeDevice(deviceId: MidiDeviceId): Unit = {
    val openedDevice = openedDevices(deviceId)

    logger.info(s"Closing device $deviceId...")
    openedDevice.midiDevice.close()
    openedDevices.remove(deviceId)
    logger.info(s"Successfully closed device $deviceId.")
  }

  override def close(): Unit = {
    logger.info(s"Closing ${getClass.getSimpleName}...")
    openedDevices.keys.foreach { deviceId =>
      Try {
        closeDevice(deviceId)
      }.recover {
        case exception => logger.error(s"Failed to close device $deviceId!", exception)
      }
    }
    logger.info(s"Finished closing ${getClass.getSimpleName}.")
  }

  /** Remove opened devices that were previously opened, but now they don't appear anymore. */
  private def removeDisappearedOpenedDevices(): Unit = {
    val deviceIds = devicesIdToInfo.keySet
    val disappearedOpenedDeviceIds = openedDevices.keySet diff deviceIds

    disappearedOpenedDeviceIds.foreach { deviceId =>
      val device = openedDevices(deviceId).midiDevice

      Try(device.close()).recover {
        case exception => logger.info(s"Disappeared device $deviceId is already closed.", exception)
      }

      openedDevices.remove(deviceId)

      logger.info(s"Previously opened device $deviceId disappeared; it was probably disconnected.")
    }
  }

  @inline
  private def logDebugDetectedDevice(deviceId: MidiDeviceId, device: MidiDevice): Unit = {
    logger.whenDebugEnabled {
      val (deviceEndpoint, handlerType, maxHandlers) = if (scmidi.isInputDevice(device)) {
        ("input", "transmitters", device.getMaxTransmitters)
      } else {
        ("output", "receivers", device.getMaxReceivers)
      }
      val maxHandlersStr = if (maxHandlers == -1) "unlimited" else maxHandlers.toString

      logger.debug(s"Detected $deviceEndpoint device $deviceId with $maxHandlersStr $handlerType.")
    }
  }
}
