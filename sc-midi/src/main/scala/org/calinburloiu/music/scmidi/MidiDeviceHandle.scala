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

import org.calinburloiu.music.scmidi

import javax.sound.midi.{MidiDevice, Receiver, Transmitter}

/**
 * Idiomatic Scala wrapper around [[MidiDevice]] which provides extra convenience methods like [[isInputDevice]] and
 * [[isOutputDevice]].
 */
case class MidiDeviceHandle(midiDevice: MidiDevice) {
  def id: MidiDeviceId = MidiDeviceId(midiDevice.getDeviceInfo)

  def info: MidiDevice.Info = midiDevice.getDeviceInfo

  def isInputDevice: Boolean = scmidi.isInputDevice(midiDevice)

  def isOutputDevice: Boolean = scmidi.isOutputDevice(midiDevice)

  def open(): Unit = midiDevice.open()

  def close(): Unit = midiDevice.close()

  def isOpen: Boolean = midiDevice.isOpen

  /**
   * @return [[Some]] [[Receiver]] if there is one, or [[None]] otherwise.
   */
  def receiverOption: Option[Receiver] = Option(midiDevice.getReceiver)

  /**
   * @return the [[Receiver]] if there is one, or throws [[NoSuchElementException]] otherwise.
   * @throws NoSuchElementException if there is no receiver.
   */
  def receiver: Receiver = receiverOption.get

  /**
   * @return [[Some]] [[Transmitter]] if there is one, or [[None]] otherwise.
   */
  def transmitterOption: Option[Transmitter] = Option(midiDevice.getTransmitter)

  /**
   * @return the [[Transmitter]] if there is one, or throws [[NoSuchElementException]] otherwise.
   * @throws NoSuchElementException if there is no transmitter.
   */
  def transmitter: Transmitter = transmitterOption.get
}
