package org.calinburloiu.music.microtuner.midi

import com.typesafe.scalalogging.{Logger, StrictLogging}
import javax.sound.midi.{MidiDevice, MidiSystem, Receiver, Transmitter}
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider

import scala.collection.{mutable, GenSet}
import scala.util.Try

class MidiManager extends AutoCloseable with StrictLogging {
  import MidiManager._

  private val inputDevicesInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedInputDevices = mutable.Map[MidiDeviceId, OpenedInputDevice]()

  private val outputDevicesInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedOutputDevices = mutable.Map[MidiDeviceId, OpenedOutputDevice]()

  refresh()

  def deviceInfo(deviceId: MidiDeviceId): MidiDevice.Info =
    inputDevicesInfo.getOrElse(deviceId, outputDevicesInfo(deviceId))

  def refresh(): Unit = {
    // Alternative to `javax.sound.midi.MidiSystem.getMidiDeviceInfo()` to make Java MIDI work on Mac.
    // This should also work on Windows.
    val deviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo
    val devices = deviceInfoArray.map(MidiSystem.getMidiDevice)

    devices.foreach { device =>
      val deviceInfo = device.getDeviceInfo
      val deviceId = MidiDeviceId(deviceInfo)
      val maxTransmitters = device.getMaxTransmitters
      val maxReceivers = device.getMaxReceivers

      if (maxTransmitters == -1 /* unlimited */ || maxTransmitters > 0) {
        inputDevicesInfo.update(deviceId, deviceInfo)
        logDebugDetectedDevice(deviceId, "input", "transmitters", maxTransmitters)
      }

      if (maxReceivers == -1 /* unlimited */ || maxReceivers > 0) {
        outputDevicesInfo.update(deviceId, deviceInfo)
        logDebugDetectedDevice(deviceId, "output", "receivers", maxReceivers)
      }
    }

    // TODO #1 Try to manually test this scenarios and see what happens
    // Remove opened devices that were previously opened but now they don't appear anymore
    def removeDisappearedOpenedDevices[D <: OpenedDevice](
        openedDevices: mutable.Map[MidiDeviceId, D], deviceIds: GenSet[MidiDeviceId]): Unit = {
      val disappearedOpenedDeviceIds = openedDevices.keySet diff deviceIds
      disappearedOpenedDeviceIds.foreach { deviceId =>
        val device = openedDevices(deviceId).device
        Try(device.close()).recover {
          case exception => logger.error(s"Failed to close disappeared device $deviceId", exception)
        }
        openedDevices.remove(deviceId)
        logger.info(s"Previously opened device $deviceId disappeared; it was probably disconnected")
      }
    }
    removeDisappearedOpenedDevices(openedInputDevices, inputDevicesInfo.keySet)
    removeDisappearedOpenedDevices(openedOutputDevices, outputDevicesInfo.keySet)
  }

  def inputDeviceIds: Seq[MidiDeviceId] = inputDevicesInfo.keys.toSeq

  def openInput(deviceId: MidiDeviceId): Try[Transmitter] = {
    val deviceInfo = inputDevicesInfo(deviceId)
    val result = Try {
      val device = MidiSystem.getMidiDevice(deviceInfo)
      val transmitter = device.getTransmitter

      device.open()
      openedInputDevices.update(deviceId, OpenedInputDevice(device, transmitter))
      logger.info(s"Successfully opened input device $deviceId")

      transmitter
    }

    if (result.isFailure) logErrorOpenDevice(deviceId, "input")
    result
  }

  def openFirstAvailableInput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceId] =
    openFirstAvailableDevice(deviceIds, openInput)

  def isInputOpened(deviceId: MidiDeviceId): Boolean = isDeviceOpened(deviceId, openedInputDevices)

  def inputTransmitter(deviceId: MidiDeviceId): Transmitter = openedInputDevices(deviceId).transmitter

  def inputDevice(deviceId: MidiDeviceId): MidiDevice = openedInputDevices(deviceId).device

  def closeInput(deviceId: MidiDeviceId): Try[Unit] = closeDevice(deviceId, openedInputDevices, logger)

  def outputDeviceIds: Seq[MidiDeviceId] = outputDevicesInfo.keys.toSeq

  def openOutput(deviceId: MidiDeviceId): Try[Receiver] = {
    val deviceInfo = outputDevicesInfo(deviceId)
    val result = Try {
      val device = MidiSystem.getMidiDevice(deviceInfo)
      val receiver = device.getReceiver

      device.open()
      openedOutputDevices.update(deviceId, OpenedOutputDevice(device, receiver))
      logger.info(s"Successfully opened output device $deviceId")

      receiver
    }

    if (result.isFailure) logErrorOpenDevice(deviceId, "output")
    result
  }

  def openFirstAvailableOutput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceId] =
    openFirstAvailableDevice(deviceIds, openOutput)

  def isOutputOpened(deviceId: MidiDeviceId): Boolean = isDeviceOpened(deviceId, openedOutputDevices)

  def outputReceiver(deviceId: MidiDeviceId): Receiver = openedOutputDevices(deviceId).receiver

  def outputDevice(deviceId: MidiDeviceId): MidiDevice = openedOutputDevices(deviceId).device

  def closeOutput(deviceId: MidiDeviceId): Try[Unit] = closeDevice(deviceId, openedOutputDevices, logger)

  override def close(): Unit = {
    logger.info(s"Closing ${getClass.getSimpleName}...")
    openedInputDevices.keys.foreach { deviceId =>
      closeDevice(deviceId, openedInputDevices, logger).recover {
        case exception => logger.error(s"Failed to close input device $deviceId", exception)
      }
    }
    openedOutputDevices.keys.foreach { deviceId =>
      closeDevice(deviceId, openedOutputDevices, logger).recover {
        case exception => logger.error(s"Failed to close output device $deviceId", exception)
      }
    }
    logger.info(s"Finished closing ${getClass.getSimpleName}")
  }

  private def openFirstAvailableDevice(
    deviceIds: Seq[MidiDeviceId], openFunc: MidiDeviceId => Try[_]): Option[MidiDeviceId] = {
    deviceIds.toStream
      .map { deviceId =>
        (deviceId, openFunc(deviceId))
      }
      .find(_._2.isSuccess)
      .map(_._1)
  }

  @inline
  private def logDebugDetectedDevice(
      deviceId: MidiDeviceId, deviceType: String, handlerType: String, maxHandlers: Int): Unit = {
    logger.debug {
      val maxHandlersStr = if (maxHandlers == -1) "unlimited" else maxHandlers.toString
      s"Detected $deviceType device $deviceId with $maxHandlersStr $handlerType"
    }
  }

  @inline
  def logErrorOpenDevice(deviceId: MidiDeviceId, deviceType: String): Unit = {
    logger.error(s"Failed to open $deviceType device $deviceId")
  }
}

object MidiManager {

  private def isDeviceOpened[D <: OpenedDevice](
      deviceId: MidiDeviceId, openedDevices: mutable.Map[MidiDeviceId, D]): Boolean =
    openedDevices.get(deviceId).exists(_.device.isOpen)

  private def closeDevice[D <: OpenedDevice](
      deviceId: MidiDeviceId, openedDevices: mutable.Map[MidiDeviceId, D], logger: Logger): Try[Unit] = Try {
    val openedDevice = openedDevices(deviceId)
    logger.info(s"Closing ${openedDevice.deviceType} device $deviceId...")
    openedDevice.device.close()
    openedDevices.remove(deviceId)
    logger.info(s"Successfully closed ${openedDevice.deviceType} device $deviceId")
  }
}


sealed trait OpenedDevice {
  val device: MidiDevice
  def deviceType: String
}

case class OpenedInputDevice(override val device: MidiDevice, transmitter: Transmitter) extends OpenedDevice {
  override def deviceType: String = "input"
}

case class OpenedOutputDevice(override val device: MidiDevice, receiver: Receiver) extends OpenedDevice {
  override def deviceType: String = "output"
}
