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

case class MidiDeviceHandle(midiDevice: MidiDevice) {
  def midiDeviceId: MidiDeviceId = MidiDeviceId(midiDevice.getDeviceInfo)

  def isInputDevice: Boolean = scmidi.isInputDevice(midiDevice)

  def isOutputDevice: Boolean = scmidi.isOutputDevice(midiDevice)

  def isOpen: Boolean = midiDevice.isOpen

  def receiverOption: Option[Receiver] = Option(midiDevice.getReceiver)

  def receiver: Receiver = receiverOption.get

  def transmitterOption: Option[Transmitter] = Option(midiDevice.getTransmitter)

  def transmitter: Transmitter = transmitterOption.get
}
