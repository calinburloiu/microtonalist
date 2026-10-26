/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.perf.midilatency.midi

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfSetupException
import org.calinburloiu.music.scmidi.{MidiDeviceHandle, MidiDeviceId, MidiDirection, MidiManager, MidiReceiver}

/** A [[MidiPath]] from one MIDI device, which the suite sends to, to another, which it listens to. */
final class DeviceMidiPath private (manager: MidiManager,
                                    sendHandle: MidiDeviceHandle,
                                    receiveHandle: MidiDeviceHandle) extends MidiPath with AutoCloseable {

  override def sender: MidiReceiver = sendHandle.receiver

  override def attach(receiver: MidiReceiver): Unit = receiveHandle.transmitter.addReceiver(receiver)

  override def detach(receiver: MidiReceiver): Unit = receiveHandle.transmitter.removeReceiver(receiver)

  /** Releases both devices. */
  override def close(): Unit = {
    manager.closeDevice(sendHandle.id, MidiDirection.Output)
    manager.closeDevice(receiveHandle.id, MidiDirection.Input)
  }
}

object DeviceMidiPath {

  /**
   * Opens `sendDevice` as an output and `receiveDevice` as an input; they may be the same device, e.g. a loopback.
   *
   * @throws PerfSetupException if a device isn't available in that direction or fails to open; the message lists the
   *   available ones.
   */
  def open(manager: MidiManager, sendDevice: MidiDeviceId, receiveDevice: MidiDeviceId): DeviceMidiPath = {
    requireAvailable(manager, sendDevice, MidiDirection.Output)
    requireAvailable(manager, receiveDevice, MidiDirection.Input)

    val sendHandle = openOrFail(manager, sendDevice, MidiDirection.Output)
    val receiveHandle = try {
      openOrFail(manager, receiveDevice, MidiDirection.Input)
    } catch {
      case exception: PerfSetupException =>
        manager.closeDevice(sendDevice, MidiDirection.Output)
        throw exception
    }
    new DeviceMidiPath(manager, sendHandle, receiveHandle)
  }

  private def requireAvailable(manager: MidiManager, deviceId: MidiDeviceId, direction: MidiDirection): Unit = {
    if (!manager.isDeviceAvailable(deviceId, direction)) {
      val available = manager.deviceIdsFor(direction).map(describe).mkString(", ")
      throw PerfSetupException(
        s"The MIDI device ${describe(deviceId)} is not available as $direction. Available as $direction: $available")
    }
  }

  private def openOrFail(manager: MidiManager, deviceId: MidiDeviceId, direction: MidiDirection): MidiDeviceHandle = {
    val handle = manager.openDevice(deviceId, direction)
    if (!handle.isOpen) {
      manager.closeDevice(deviceId, direction)
      throw PerfSetupException(s"The MIDI device ${describe(deviceId)} failed to open as $direction")
    }
    handle
  }

  private def describe(deviceId: MidiDeviceId): String = s"'${deviceId.name}' by '${deviceId.vendor}'"
}
