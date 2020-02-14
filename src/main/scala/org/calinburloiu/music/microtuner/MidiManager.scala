package org.calinburloiu.music.microtuner

import javax.sound.midi.{MidiDevice, Receiver, Transmitter}

import scala.collection.mutable
import scala.util.Try

class MidiManager extends AutoCloseable {

  private val inputDevicesInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedInputDevices = mutable.Map[MidiDeviceId, (MidiDevice, Transmitter)]()
  private val outputDevicesInfo = mutable.Map[MidiDeviceId, MidiDevice.Info]()
  private val openedOutputDevices = mutable.Map[MidiDeviceId, (MidiDevice, Receiver)]()

  refresh()

  def midiDeviceInfo(midiDeviceId: MidiDeviceId): MidiDevice.Info = ???

  // TODO #1 Remember to remove opened devices that don't exist anymore
  def refresh(): Unit = ???

  def inputMidiDeviceIds: Seq[MidiDeviceId] = inputDevicesInfo.keys.toSeq

  def openInput(midiDeviceId: MidiDeviceId): Try[Transmitter] = ???

  def openFirstAvailableInput(midiDeviceIds: Seq[MidiDeviceId]): Option[MidiDeviceId] = ???

  def isInputOpened(midiDeviceId: MidiDeviceId): Boolean = openedInputDevices.contains(midiDeviceId)

  def inputTransmitter(midiDeviceId: MidiDeviceId): Transmitter = openedInputDevices(midiDeviceId)._2

  def inputDevice(midiDeviceId: MidiDeviceId): MidiDevice = openedInputDevices(midiDeviceId)._1

  def closeInput(midiDeviceId: MidiDeviceId): Try[Unit] = ???

  def outputMidiDeviceIds: Seq[MidiDeviceId] = outputDevicesInfo.keys.toSeq

  def openOutput(midiDeviceId: MidiDeviceId): Try[Receiver] = ???

  def openFirstAvailableOutput(midiDeviceIds: Seq[MidiDeviceId]): Option[MidiDeviceId] = ???

  def isOutputOpened(midiDeviceId: MidiDeviceId): Boolean = openedOutputDevices.contains(midiDeviceId)

  def outputReceiver(midiDeviceId: MidiDeviceId): Receiver = openedOutputDevices(midiDeviceId)._2

  def outputDevice(midiDeviceId: MidiDeviceId): MidiDevice = openedOutputDevices(midiDeviceId)._1

  def closeOutput(midiDeviceId: MidiDeviceId): Try[Unit] = ???

  override def close(): Unit = ???
}
