package org.calinburloiu.music.microtuner.midi

import com.typesafe.scalalogging.StrictLogging
import javax.sound.midi.{MidiDevice, MidiSystem, Receiver, Transmitter}
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider

import scala.collection.{mutable, GenSet}
import scala.util.Try

// TODO #1 Add logging!
class MidiManager extends AutoCloseable with StrictLogging {
  import MidiManager._

  private val inputDevicesInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedInputDevices = mutable.Map[MidiDeviceId, OpenedInputDevice]()

  private val outputDevicesInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedOutputDevices = mutable.Map[MidiDeviceId, OpenedOutputDevice]()

  refresh()

  def midiDeviceInfo(deviceId: MidiDeviceId): MidiDevice.Info =
    inputDevicesInfo.getOrElse(deviceId, outputDevicesInfo(deviceId))

  def refresh(): Unit = {
    // Alternative to `javax.sound.midi.MidiSystem.getMidiDeviceInfo()` to make Java MIDI work on Mac.
    // This should also work on Windows.
    val midiDeviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo
    val devices = midiDeviceInfoArray.map(MidiSystem.getMidiDevice)

    devices.foreach { device =>
      val deviceInfo = device.getDeviceInfo
      val maxTransmitters = device.getMaxTransmitters
      val maxReceivers = device.getMaxReceivers

      if (maxTransmitters == -1 /* unlimited */ || maxTransmitters > 0) {
        inputDevicesInfo.update(MidiDeviceId(deviceInfo), deviceInfo)
      }

      if (maxReceivers == -1 /* unlimited */ || maxReceivers > 0) {
        outputDevicesInfo.update(MidiDeviceId(deviceInfo), deviceInfo)
      }
    }

    // TODO #1 Try to manually test this scenarios and see what happens
    // Remove opened devices that were previously opened but now they don't appear anymore
    def removeDisappearedOpenedDevices[D <: OpenedDevice](
        openedDevices: mutable.Map[MidiDeviceId, D], deviceIds: GenSet[MidiDeviceId]): Unit = {
      val disappearedOpenedDeviceIds = openedDevices.keySet diff deviceIds
      disappearedOpenedDeviceIds.foreach { deviceId =>
        val device = openedDevices(deviceId).midiDevice
        Try(device.close()).recover {
          case exception => logger.error(s"Failed to close disappeared device $deviceId", exception)
        }
        openedDevices.remove(deviceId)
      }
    }
    removeDisappearedOpenedDevices(openedInputDevices, inputDevicesInfo.keySet)
    removeDisappearedOpenedDevices(openedOutputDevices, outputDevicesInfo.keySet)
  }

  def inputMidiDeviceIds: Seq[MidiDeviceId] = inputDevicesInfo.keys.toSeq

  def openInput(deviceId: MidiDeviceId): Try[Transmitter] = {
    val deviceInfo = inputDevicesInfo(deviceId)
    Try {
      val device = MidiSystem.getMidiDevice(deviceInfo)
      val transmitter = device.getTransmitter

      device.open()
      openedInputDevices.update(deviceId, OpenedInputDevice(device, transmitter))

      transmitter
    }
  }

  def openFirstAvailableInput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceId] =
    openFirstAvailableDevice(deviceIds, openInput)

  def isInputOpened(deviceId: MidiDeviceId): Boolean = isDeviceOpened(deviceId, openedInputDevices)

  def inputTransmitter(deviceId: MidiDeviceId): Transmitter = openedInputDevices(deviceId).transmitter

  def inputDevice(deviceId: MidiDeviceId): MidiDevice = openedInputDevices(deviceId).midiDevice

  def closeInput(deviceId: MidiDeviceId): Try[Unit] = closeDevice(deviceId, openedInputDevices)

  def outputMidiDeviceIds: Seq[MidiDeviceId] = outputDevicesInfo.keys.toSeq

  def openOutput(deviceId: MidiDeviceId): Try[Receiver] = {
    val deviceInfo = outputDevicesInfo(deviceId)
    Try {
      val device = MidiSystem.getMidiDevice(deviceInfo)
      val receiver = device.getReceiver

      device.open()
      openedOutputDevices.update(deviceId, OpenedOutputDevice(device, receiver))

      receiver
    }
  }

  def openFirstAvailableOutput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceId] =
    openFirstAvailableDevice(deviceIds, openOutput)

  def isOutputOpened(deviceId: MidiDeviceId): Boolean = isDeviceOpened(deviceId, openedOutputDevices)

  def outputReceiver(deviceId: MidiDeviceId): Receiver = openedOutputDevices(deviceId).receiver

  def outputDevice(deviceId: MidiDeviceId): MidiDevice = openedOutputDevices(deviceId).midiDevice

  def closeOutput(deviceId: MidiDeviceId): Try[Unit] = closeDevice(deviceId, openedOutputDevices)

  override def close(): Unit = {
    openedInputDevices.keys.foreach { deviceId =>
      closeDevice(deviceId, openedInputDevices).recover {
        case exception => logger.error(s"Failed to close input device $deviceId", exception)
      }
    }
    openedOutputDevices.keys.foreach { deviceId =>
      closeDevice(deviceId, openedOutputDevices).recover {
        case exception => logger.error(s"Failed to close output device $deviceId", exception)
      }
    }
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
}

object MidiManager {

  private def isDeviceOpened[D <: OpenedDevice](
      deviceId: MidiDeviceId, openedDevices: mutable.Map[MidiDeviceId, D]): Boolean =
    openedDevices.get(deviceId).exists(_.midiDevice.isOpen)

  private def closeDevice[D <: OpenedDevice](
      deviceId: MidiDeviceId, openedDevices: mutable.Map[MidiDeviceId, D]): Try[Unit] = Try {
    // TODO #1 Do we need to close the Transmitter as well
    openedDevices(deviceId).midiDevice.close()
    openedDevices.remove(deviceId)
  }

  private def closeAllDevices[D <: OpenedDevice](
      openedDevices: mutable.Map[MidiDeviceId, D], deviceIds: GenSet[MidiDeviceId]): Unit = {

  }
}

sealed trait OpenedDevice {
  val midiDevice: MidiDevice
}
case class OpenedInputDevice(override val midiDevice: MidiDevice, transmitter: Transmitter) extends OpenedDevice
case class OpenedOutputDevice(override val midiDevice: MidiDevice, receiver: Receiver) extends OpenedDevice
