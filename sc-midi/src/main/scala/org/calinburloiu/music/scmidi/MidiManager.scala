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

package org.calinburloiu.music.scmidi

/**
 * Manages connections to MIDI devices and gives information about them.
 *
 * The trait has separate sets of methods for inputs and outputs, because a platform may expose two endpoints (two
 * [[MidiDeviceHandle]]s) for the same physical device, one for input and the other for output. Note that in this
 * case, there is a single [[MidiDeviceId]].
 *
 * An implementation scans the environment on [[refresh]] and typically also when the platform reports a change, and
 * publishes [[MidiEvent]]s about what it finds. [[org.calinburloiu.music.scmidi.javamidi.JavaMidiManager]] is the
 * Java Sound implementation; consumers receive a [[MidiManager]] and the composition root picks the implementation.
 */
trait MidiManager extends AutoCloseable {

  /**
   * Rescans the environment for MIDI device information and updates the internal state, publishing the
   * [[MidiEvent]]s that describe what changed.
   */
  def refresh(): Unit

  /** @return whether the input device with the given identifier is currently connected. */
  def isInputAvailable(deviceId: MidiDeviceId): Boolean

  /** @return the information of the input device with the given identifier, if it is currently connected. */
  def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo]

  /** @return the identifiers of the input devices currently connected. */
  def inputDeviceIds: Seq[MidiDeviceId]

  /** @return the information of the input devices currently connected. */
  def inputDevicesInfo: Seq[MidiDeviceInfo]

  /**
   * Opens an input connection to a MIDI device based on its unique identifier.
   *
   * The device is not required to be connected: a handle is returned either way, and a device that is not connected
   * yet is opened once it gets connected.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openInput(deviceId: MidiDeviceId): MidiDeviceHandle

  /**
   * @return the handle of the input device with the given identifier, if it was requested to be opened through this
   *         manager.
   */
  def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle]

  /** @return the handles of the input devices requested to be opened through this manager. */
  def inputOpenedDevices: Seq[MidiDeviceHandle]

  /**
   * Closes the input device with the given identifier, if it was requested to be opened through this manager. The
   * operation is reference-counted at the handle level.
   */
  def closeInput(deviceId: MidiDeviceId): Unit

  /** @return whether the output device with the given identifier is currently connected. */
  def isOutputAvailable(deviceId: MidiDeviceId): Boolean

  /** @return the information of the output device with the given identifier, if it is currently connected. */
  def outputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo]

  /** @return the identifiers of the output devices currently connected. */
  def outputDeviceIds: Seq[MidiDeviceId]

  /** @return the information of the output devices currently connected. */
  def outputDevicesInfo: Seq[MidiDeviceInfo]

  /**
   * Opens an output connection to a MIDI device based on its unique identifier.
   *
   * The device is not required to be connected: a handle is returned either way, and a device that is not connected
   * yet is opened once it gets connected.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle

  /**
   * @return the handle of the output device with the given identifier, if an open was requested through this
   *         manager.
   */
  def outputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle]

  /** @return the handles of the output devices requested to be opened through this manager. */
  def outputOpenedDevices: Seq[MidiDeviceHandle]

  /**
   * Closes the output device with the given identifier, if it was requested to be opened through this manager. The
   * operation is reference-counted at the handle level.
   */
  def closeOutput(deviceId: MidiDeviceId): Unit

  /** Closes every device opened through this manager and stops watching the environment. */
  override def close(): Unit
}
