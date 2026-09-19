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
 * The `direction` a method takes selects which endpoint of the manager the device is kept in. An implementation may
 * expose separate input and output endpoints for the same physical device — two [[MidiDeviceHandle]]s under a single
 * [[MidiDeviceId]], as the Java Sound one does — or a single bidirectional endpoint, as a MIDI 2.0 one would. The
 * parameter therefore tells where the manager keeps a device, whereas the `direction` of a [[MidiDeviceHandle]] or of
 * a [[MidiDeviceInfo]] tells what the device itself is capable of.
 *
 * [[MidiDirection.None]] names no endpoint and always throws an `IllegalArgumentException`. The other three values
 * name one in principle, but an implementation supports only those that match how it keeps its devices, and throws an
 * `IllegalArgumentException` for the rest: one with separate endpoints — every implementation today, including
 * [[org.calinburloiu.music.scmidi.javamidi.JavaMidiManager]] — accepts [[MidiDirection.Input]] and
 * [[MidiDirection.Output]] and rejects [[MidiDirection.InputOutput]]. Each implementation documents which values it
 * accepts.
 *
 * An implementation scans the environment on [[refresh]] and typically also when the platform reports a change, and
 * publishes [[MidiEvent]]s about what it finds. [[org.calinburloiu.music.scmidi.javamidi.JavaMidiManager]] is the
 * Java Sound implementation; consumers receive a [[MidiManager]] and the composition root picks the implementation.
 *
 * A handle is live while the manager holds it, which is exactly while its state is not
 * [[MidiDeviceHandle.State.Closed]] (see [[MidiDeviceHandle]]). The manager holds a handle for every device that is
 * connected, requested to open, or both, and it forgets a handle once it reaches `Closed`.
 */
trait MidiManager extends AutoCloseable {

  /**
   * Rescans the environment for MIDI device information and updates the internal state, publishing the
   * [[MidiEvent]]s that describe what changed.
   */
  def refresh(): Unit

  /**
   * @param direction The endpoint to look in (see [[MidiManager]]).
   * @return whether the device with the given identifier is currently connected.
   */
  def isDeviceAvailable(deviceId: MidiDeviceId, direction: MidiDirection): Boolean

  /**
   * @param direction The endpoint to look in (see [[MidiManager]]).
   * @return the information of the device with the given identifier, if it is currently connected.
   */
  def deviceInfoOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceInfo]

  /**
   * @param direction The endpoint to list (see [[MidiManager]]).
   * @return the identifiers of the devices currently connected.
   */
  def deviceIdsFor(direction: MidiDirection): Seq[MidiDeviceId]

  /**
   * @param direction The endpoint to list (see [[MidiManager]]).
   * @return the information of the devices currently connected.
   */
  def devicesInfoFor(direction: MidiDirection): Seq[MidiDeviceInfo]

  /**
   * Takes one reference to the device with the given identifier and returns its live handle, creating one if there is
   * none.
   *
   * The device is not required to be connected. The handle is [[MidiDeviceHandle.State.Open]] if the device is
   * connected and opens, [[MidiDeviceHandle.State.WaitingToOpen]] if it is not connected, in which case it opens once
   * the device gets connected, and [[MidiDeviceHandle.State.Connected]] if the device fails to open.
   *
   * @param deviceId  Unique identifier of the device.
   * @param direction The endpoint to open the device in (see [[MidiManager]]).
   * @return the live handle of the device.
   */
  def openDevice(deviceId: MidiDeviceId, direction: MidiDirection): MidiDeviceHandle

  /**
   * @param direction The endpoint to look in (see [[MidiManager]]).
   * @return the live handle of the device with the given identifier: requested to open, connected, or both. A
   *         connected device nobody opened has one, in [[MidiDeviceHandle.State.Connected]].
   */
  def deviceOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceHandle]

  /**
   * @param direction The endpoint to list (see [[MidiManager]]).
   * @return the live handles of the devices that are open, i.e. connected and requested to open.
   */
  def openDevicesFor(direction: MidiDirection): Seq[MidiDeviceHandle]

  /**
   * @param direction The endpoint to list (see [[MidiManager]]).
   * @return the live handles of the devices requested to open, whether or not their device is connected: the open
   *         ones, and those waiting to open once their device gets connected.
   */
  def devicesRequestedToOpenFor(direction: MidiDirection): Seq[MidiDeviceHandle]

  /**
   * Releases one reference to the device with the given identifier. It does nothing when the device has no live handle
   * requested to open. When the last reference is released, the handle moves to [[MidiDeviceHandle.State.Connected]],
   * where it stays live, or, if its device is not connected, to [[MidiDeviceHandle.State.Closed]], where it is
   * forgotten.
   *
   * @param direction The endpoint the device was opened in (see [[MidiManager]]).
   */
  def closeDevice(deviceId: MidiDeviceId, direction: MidiDirection): Unit

  /** @return whether the input device with the given identifier is currently connected. */
  def isInputAvailable(deviceId: MidiDeviceId): Boolean

  /** @return the information of the input device with the given identifier, if it is currently connected. */
  def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo]

  /** @return the identifiers of the input devices currently connected. */
  def inputDeviceIds: Seq[MidiDeviceId]

  /** @return the information of the input devices currently connected. */
  def inputDevicesInfo: Seq[MidiDeviceInfo]

  /**
   * Takes one reference to the input device with the given identifier and returns its live handle, creating one if
   * there is none.
   *
   * The device is not required to be connected. The handle is [[MidiDeviceHandle.State.Open]] if the device is
   * connected and opens, [[MidiDeviceHandle.State.WaitingToOpen]] if it is not connected, in which case it opens once
   * the device gets connected, and [[MidiDeviceHandle.State.Connected]] if the device fails to open.
   *
   * @param deviceId Unique identifier of the device.
   * @return the live handle of the device.
   */
  def openInput(deviceId: MidiDeviceId): MidiDeviceHandle

  /**
   * @return the live handle of the input device with the given identifier: requested to open, connected, or both. A
   *         connected device nobody opened has one, in [[MidiDeviceHandle.State.Connected]].
   */
  def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle]

  /** @return the live handles of the input devices that are open, i.e. connected and requested to open. */
  def inputOpenDevices: Seq[MidiDeviceHandle]

  /**
   * @return the live handles of the input devices requested to open, whether or not their device is connected: the
   *         open ones, and those waiting to open once their device gets connected.
   */
  def inputDevicesRequestedToOpen: Seq[MidiDeviceHandle]

  /**
   * Releases one reference to the input device with the given identifier. It does nothing when the device has no live
   * handle requested to open. When the last reference is released, the handle moves to
   * [[MidiDeviceHandle.State.Connected]], where it stays live, or, if its device is not connected, to
   * [[MidiDeviceHandle.State.Closed]], where it is forgotten.
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
   * Takes one reference to the output device with the given identifier and returns its live handle, creating one if
   * there is none.
   *
   * The device is not required to be connected. The handle is [[MidiDeviceHandle.State.Open]] if the device is
   * connected and opens, [[MidiDeviceHandle.State.WaitingToOpen]] if it is not connected, in which case it opens once
   * the device gets connected, and [[MidiDeviceHandle.State.Connected]] if the device fails to open.
   *
   * @param deviceId Unique identifier of the device.
   * @return the live handle of the device.
   */
  def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle

  /**
   * @return the live handle of the output device with the given identifier: requested to open, connected, or both. A
   *         connected device nobody opened has one, in [[MidiDeviceHandle.State.Connected]].
   */
  def outputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle]

  /** @return the live handles of the output devices that are open, i.e. connected and requested to open. */
  def outputOpenDevices: Seq[MidiDeviceHandle]

  /**
   * @return the live handles of the output devices requested to open, whether or not their device is connected: the
   *         open ones, and those waiting to open once their device gets connected.
   */
  def outputDevicesRequestedToOpen: Seq[MidiDeviceHandle]

  /**
   * Releases one reference to the output device with the given identifier. It does nothing when the device has no
   * live handle requested to open. When the last reference is released, the handle moves to
   * [[MidiDeviceHandle.State.Connected]], where it stays live, or, if its device is not connected, to
   * [[MidiDeviceHandle.State.Closed]], where it is forgotten.
   */
  def closeOutput(deviceId: MidiDeviceId): Unit

  /**
   * Releases every reference held through this manager, so that every device it opened ends up closed, and stops
   * watching the environment.
   */
  override def close(): Unit
}
