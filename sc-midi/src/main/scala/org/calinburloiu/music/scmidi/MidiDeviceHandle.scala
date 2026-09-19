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
 * Handle to a single MIDI device, identified by a [[MidiDeviceId]].
 *
 * A [[MidiManager]] creates the handles and is the only one to change their state. A consumer requests a device with
 * [[MidiManager.openInput]] or [[MidiManager.openOutput]], which return its handle, and releases it with
 * [[MidiManager.closeInput]] or [[MidiManager.closeOutput]]; both are reference-counted. The consumer only inspects the
 * handle and uses it for MIDI I/O.
 *
 * The device is not required to be connected to the system when it is requested: the manager informs the handle when
 * the device gets connected or disconnected, and [[info]] is defined only while the device is connected. A handle
 * requested while its device is not connected waits in [[MidiDeviceHandle.State.WaitingToOpen]] and opens once the
 * device gets connected.
 *
 * A handle is live while its manager holds it, which is exactly while its [[state]] is not
 * [[MidiDeviceHandle.State.Closed]]. A handle that reaches `Closed` is forgotten by its manager and stays `Closed` for
 * good: requesting the same device again returns a new handle, so a consumer must not keep a handle after it released
 * its references to it.
 *
 * A handle exposes a [[MidiReceiver]] and a [[ConcurrentMidiTransmitter]] via [[receiver]] and [[transmitter]]. They
 * can be wired while the device is disconnected or closed, in which case they do nothing; once the device becomes
 * usable, the wiring works without any change.
 *
 * [[state]] tells the current state of the handle and of its device; see [[MidiDeviceHandle.State]] for the
 * transitions. [[org.calinburloiu.music.scmidi.javamidi.JavaMidiDeviceHandle]] is the Java Sound implementation.
 */
trait MidiDeviceHandle {

  /** Unique identifier of the MIDI device. */
  def id: MidiDeviceId

  /**
   * Retrieves the information about the MIDI device.
   *
   * @return The MIDI device information while the device is connected; otherwise, None.
   */
  def info: Option[MidiDeviceInfo]

  /**
   * Determines if the associated MIDI device is an input device. If it is, this handle's [[transmitter]] can be used
   * to subscribe to the messages the device sends; otherwise it never emits anything.
   *
   * @return True if the MIDI device supports input, false otherwise — including while it is disconnected, when its
   *         capabilities are not known.
   */
  def isInputDevice: Boolean = info.exists(_.isInputDevice)

  /**
   * Determines if the associated MIDI device is an output device. If it is, this handle's [[receiver]] can be used to
   * send messages to the device.
   *
   * @return True if the MIDI device supports output, false otherwise — including while it is disconnected, when its
   *         capabilities are not known.
   */
  def isOutputDevice: Boolean = info.exists(_.isOutputDevice)

  /**
   * Tells whether the device supports input and/or output.
   *
   * @return A [[MidiDirection]] indicating the input/output capabilities of the device; [[MidiDirection.None]]
   *         while it is disconnected.
   */
  def direction: MidiDirection = MidiDirection(isInputDevice, isOutputDevice)

  /**
   * Retrieves the current state of the handle and its device.
   */
  def state: MidiDeviceHandle.State

  /**
   * Checks whether the MIDI device is currently connected to the system.
   *
   * @return True if the device is connected, i.e. the [[state]] is [[MidiDeviceHandle.State.Connected]] or
   *         [[MidiDeviceHandle.State.Open]]; false otherwise.
   */
  def isConnected: Boolean = state.isConnected

  /**
   * Determines if the MIDI device is currently open for use.
   *
   * @return True if the [[state]] is [[MidiDeviceHandle.State.Open]], false otherwise.
   */
  def isOpen: Boolean = state == MidiDeviceHandle.State.Open

  /**
   * Determines if the MIDI device has been requested to open, i.e. an `open` transition succeeded and no `close`
   * transition has happened since, whether or not the device is connected. Unlike [[isOpen]], it is also true while
   * the handle waits for the device to get connected in order to open it.
   *
   * @return True if the device has been requested to open, false otherwise.
   * @see [[MidiDeviceHandle.State.isOpenRequested]], which this mirrors for the current [[state]].
   */
  def isOpenRequested: Boolean = state.isOpenRequested

  /**
   * Retrieves the receiver of the device, which can be used to send MIDI messages to it. A message sent while the
   * device is not open is dropped.
   *
   * @return The MIDI receiver instance.
   */
  def receiver: MidiReceiver

  /**
   * Retrieves the transmitter of the device, which can be used to subscribe to the MIDI messages it sends. Receivers
   * may be added before the device is connected or open; they start getting messages when it is.
   *
   * @return The transmitter instance.
   */
  def transmitter: ConcurrentMidiTransmitter
}

object MidiDeviceHandle {

  /**
   * Represents the state of a MIDI device's connection and openness.
   *
   * {{{
   *    ┌─────────────┐     connect      ┌─────────────┐
   *    │             ├──────────────────►             │
   *    │WaitingToOpen│                  │    Open     │
   *    │             ◄──────────────────┤             │
   *    └───▲─────┬───┘    disconnect    └───▲─────┬───┘
   *        │     │                          │     │
   *    open│     │close                 open│     │close
   *        │     │                          │     │
   *    ┌───┴─────▼───┐     connect      ┌───┴─────▼───┐
   *    │             ├──────────────────►             │
   *    │   Closed    │                  │  Connected  │
   *    │             ◄──────────────────┤             │
   *    └─────────────┘    disconnect    └─────────────┘
   * }}}
   *
   * The diagram is a square over the two properties of a state: [[isConnected]] is false on the left and true on the
   * right, and [[isOpenRequested]] is false at the bottom and true at the top. Hence, `connect` and `disconnect` move
   * horizontally, while `open` and `close` move vertically. The four states cover every combination of the two, so the
   * device is open for use only in [[Open]], where it is both connected and requested to open.
   *
   * Each transition either succeeds or fails, and a failure sets to false the property it concerns, so that the handle
   * never relies on a device that failed: a failure of the connection leaves the handle not connected, and a failure to
   * open or close the device leaves it not requested to open. Hence, a [[Connected]] handle whose device fails to open
   * stays [[Connected]], an [[Open]] handle whose device fails to close moves to [[Connected]] anyway, and a
   * [[Connected]] handle whose device fails to close as it gets disconnected moves to [[Closed]] anyway, where a later
   * request to open the device waits for it to get connected again.
   *
   * @param isConnected     Indicates whether the device is connected.
   * @param isOpenRequested Indicates whether the device has been requested to open, i.e. an `open` transition
   *                        succeeded and no `close` transition has happened since, whether or not the device is
   *                        connected.
   */
  //@formatter:off
  enum State(val isConnected: Boolean, val isOpenRequested: Boolean) {
    case Closed        extends State(isConnected = false, isOpenRequested = false)
    case Connected     extends State(isConnected = true,  isOpenRequested = false)
    case WaitingToOpen extends State(isConnected = false, isOpenRequested = true)
    case Open          extends State(isConnected = true,  isOpenRequested = true)
  }
  //@formatter:on
}
