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
 * The `direction` a method takes tells how the caller wants to use a device: as an input ([[MidiDirection.Input]]),
 * as an output ([[MidiDirection.Output]]) or as both ([[MidiDirection.InputOutput]]). It is a request: the `direction`
 * of a [[MidiDeviceHandle]] or of a [[MidiDeviceInfo]] tells instead what the device itself is capable of, and a caller
 * may request less than that. A MIDI 2.0 implementation, for instance, would let it request only the input of a device
 * that works in both directions, and so a projection of that device.
 *
 * [[MidiDirection.None]] requests no use of a device and always throws an `IllegalArgumentException`. Of the other
 * three values, an implementation accepts only those it can serve, throws an `IllegalArgumentException` for the rest,
 * and documents which values it accepts. Every implementation today, including
 * [[org.calinburloiu.music.scmidi.javamidi.JavaMidiManager]], accepts [[MidiDirection.Input]] and
 * [[MidiDirection.Output]] and rejects [[MidiDirection.InputOutput]].
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
   * @param deviceId  Unique identifier of the device.
   * @param direction How the caller wants to use the device (see [[MidiManager]]).
   * @return whether the device with the given identifier is currently connected.
   */
  def isDeviceAvailable(deviceId: MidiDeviceId, direction: MidiDirection): Boolean

  /**
   * @param deviceId  Unique identifier of the device.
   * @param direction How the caller wants to use the device (see [[MidiManager]]).
   * @return the information of the device with the given identifier, if it is currently connected.
   */
  def deviceInfoOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceInfo]

  /**
   * @param direction How the caller wants to use the devices (see [[MidiManager]]).
   * @return the identifiers of the devices currently connected.
   */
  def deviceIdsFor(direction: MidiDirection): Seq[MidiDeviceId]

  /**
   * @param direction How the caller wants to use the devices (see [[MidiManager]]).
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
   * @param direction How the caller wants to use the device (see [[MidiManager]]).
   * @return the live handle of the device.
   */
  def openDevice(deviceId: MidiDeviceId, direction: MidiDirection): MidiDeviceHandle

  /**
   * @param deviceId  Unique identifier of the device.
   * @param direction How the caller wants to use the device (see [[MidiManager]]).
   * @return the live handle of the device with the given identifier: requested to open, connected, or both. A
   *         connected device nobody opened has one, in [[MidiDeviceHandle.State.Connected]].
   */
  def deviceOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceHandle]

  /**
   * @param direction How the caller wants to use the devices (see [[MidiManager]]).
   * @return the live handles of the devices that are open, i.e. connected and requested to open.
   */
  def openDevicesFor(direction: MidiDirection): Seq[MidiDeviceHandle]

  /**
   * @param direction How the caller wants to use the devices (see [[MidiManager]]).
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
   * @param deviceId  Unique identifier of the device.
   * @param direction The direction the device was opened for with [[openDevice]] (see [[MidiManager]]).
   */
  def closeDevice(deviceId: MidiDeviceId, direction: MidiDirection): Unit

  /**
   * Releases every reference held through this manager, so that every device it opened ends up closed, and stops
   * watching the environment.
   */
  override def close(): Unit
}
