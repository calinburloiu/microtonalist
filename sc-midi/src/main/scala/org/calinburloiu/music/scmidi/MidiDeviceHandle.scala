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
 * A [[MidiManager]] creates the handles and hands one its device when [[MidiManager.openInput]] or
 * [[MidiManager.openOutput]] is called while that device is connected. A handle can therefore exist for a device that
 * is not connected to the system: [[info]] stays empty until the manager first hands the handle a connected device.
 * The manager does not notify the handle on its own when the device later appears or goes away (#288).
 *
 * A device can only be used after it is opened via [[open]]; when it is no longer needed, [[close]] must be called.
 * The operation is reference-counted. [[open]] may be called while the device is not connected, which only moves the
 * handle to [[MidiDeviceHandle.State.WaitingToOpen]]; it opens when the manager next hands it a connected device.
 *
 * A handle exposes a [[MidiReceiver]] and a [[ConcurrentMidiTransmitter]] via [[receiver]] and [[transmitter]]. They
 * can be wired while the device is disconnected or closed, in which case they do nothing; once the device becomes
 * usable, the wiring works without any change.
 *
 * [[state]] tells the current state of the handle and of its device; see [[MidiDeviceHandle.State]] for the
 * transitions. [[org.calinburloiu.music.scmidi.javamidi.JavaMidiDeviceHandle]] is the Java Sound implementation.
 */
trait MidiDeviceHandle extends AutoCloseable {

  /** Unique identifier of the MIDI device. */
  def id: MidiDeviceId

  /**
   * Retrieves the information about the MIDI device.
   *
   * @return The MIDI device information while the device is connected; otherwise, None.
   */
  def info: Option[MidiDeviceInfo]

  /**
   * Determines if the associated MIDI device is an input device. If it is, then its [[transmitter]] can be used to
   * subscribe to the messages the device sends, otherwise that will do nothing.
   *
   * @return True if the MIDI device supports input, false otherwise — including while it is disconnected, when its
   *         capabilities are not known.
   */
  def isInputDevice: Boolean = info.exists(_.isInputDevice)

  /**
   * Determines if the associated MIDI device is an output device. If it is, then its [[receiver]] can be used to send
   * messages to the device, otherwise that will do nothing.
   *
   * @return True if the MIDI device supports output, false otherwise — including while it is disconnected, when its
   *         capabilities are not known.
   */
  def isOutputDevice: Boolean = info.exists(_.isOutputDevice)

  /**
   * Tells whether the device supports input and/or output.
   *
   * @return A [[MidiEndpointType]] indicating the input/output capabilities of the device; [[MidiEndpointType.None]]
   *         while it is disconnected.
   */
  def endpointType: MidiEndpointType = MidiEndpointType(isInputDevice, isOutputDevice)

  /**
   * Retrieves the current state of the handle and its device.
   */
  def state: MidiDeviceHandle.State

  /**
   * Checks whether the MIDI device is currently connected to the system.
   *
   * @return True if the device is connected, false otherwise.
   */
  def isConnected: Boolean

  /**
   * Determines if the MIDI device is currently open.
   *
   * @return True if the device is open, false otherwise.
   * @see [[open]] and [[close]], the methods that update this state.
   */
  def isOpen: Boolean

  /**
   * Attempts to open the MIDI device associated with this handle.
   *
   *   - If the device is not yet connected, the handle transitions to [[MidiDeviceHandle.State.WaitingToOpen]] and
   *     opens when the manager next hands it a connected device; see the class documentation for when that happens.
   *   - If the device is already connected, the device will attempt to open immediately.
   *
   * This is a reference-counted operation; the device will only transition to an opened state if this is the first
   * call to the method, and it is in a state that allows opening.
   */
  def open(): Unit

  /**
   * Closes the MIDI device handle, updating its internal state.
   *
   * If this is the last reference to the device, it is properly closed or its state is adjusted depending on
   * the current state and connection status.
   */
  override def close(): Unit

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
   *    ┌─────────────┐     onConnect    ┌────┐
   *    │             ├──────────────────►    │
   *    │WaitingToOpen│                  │Open│
   *    │             │            ┌─────►    │
   *    └▲────────────┘            │     └────┘
   *     │   │                   open       │
   *     │   │                     │      close
   *     │   │            ┌────────┴┐       │
   *     │   │            │Connected◄───────┘
   *     │   │close       └─▲────┬──┘
   * open│   │              │    │
   *     │   │     onConnect│    │onDisconnect
   *     │   │              │    │
   *     │   │             ┌┴────▼┐
   *     │   └─────────────►      │
   *     │                 │Closed│
   *     └─────────────────┤      │
   *                       └──────┘
   * }}}
   *
   * @param isConnected Indicates whether the device is connected.
   * @param isOpen      Indicates whether the device is open for use.
   */
  //@formatter:off
  enum State(val isConnected: Boolean, val isOpen: Boolean) {
    case Closed         extends State(isConnected = false,  isOpen = false)
    case Connected      extends State(isConnected = true,   isOpen = false)
    case WaitingToOpen  extends State(isConnected = false,  isOpen = false)
    case Open           extends State(isConnected = true,   isOpen = true)
  }
  //@formatter:on
}
