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

import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi

import java.util.concurrent.locks.{Lock, ReentrantLock}
import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.*

/**
 * Class used for objects that handle MIDI devices.
 *
 * [[MidiManager]] is responsible to create instances of this class and to keep it up to date behind the scenes.
 *
 * A [[MidiDeviceHandle]] can be instantiated for a MIDI device based on its unique identifier, [[MidiDeviceId]].
 * The device is not required to be connected to the system when the object is instantiated. However, [[MidiManager]]
 * will automatically inform the object when the physical device gets connected or disconnected. Only when the device
 * is connected the [[MidiDevice]], via [[device]] accessor, and the [[MidiDevice.Info]], via [[info]] accessor,
 * become defined on the instance.
 *
 * Similar to [[MidiDevice]], a device can only be used after it's opened via the [[open]] method. When it's no
 * longer needed, [[close]] must be called. The device can be requested to be opened even if it's not connected and
 * once it will become connected it will also be opened.
 *
 * An instance exposes a [[Receiver]] and a [[MultiTransmitter]] via [[receiver]] and [[multiTransmitter]] accessors,
 * respectively. If the device is not connected, those instances will do nothing. But you can wire them, and when the
 * device becomes connected they can be used without doing something.
 *
 * The class also exposes a [[state]] accessor with the current state of the instance and its associated device.
 *
 * @param id          Unique identifier of the MIDI device.
 * @param businessync Used for publishing MIDI events about the device state.
 */
@ThreadSafe
class MidiDeviceHandle private[scmidi](val id: MidiDeviceId,
                                       businessync: Businessync) extends AutoCloseable, Locking, LazyLogging {

  import MidiDeviceHandle.*

  private implicit val lock: Lock = new ReentrantLock()

  @volatile private var _info: Option[MidiDevice.Info] = None
  @volatile private var _device: Option[MidiDevice] = None

  private var _state: State = State.Closed

  private var openRefCount: Int = 0

  private lazy val _receiver: HandleReceiver = new HandleReceiver
  private lazy val splitter: MidiSplitter = new MidiSplitter

  private class HandleReceiver extends Receiver {
    override def send(message: MidiMessage, timeStamp: Long): Unit = {
      for (midiDevice <- _device if midiDevice.isOpen; deviceReceiver <- Option(midiDevice.getReceiver)) {
        deviceReceiver.send(message, timeStamp)
      }
    }

    override def close(): Unit = {}
  }

  /**
   * Convenience constructor for creating an instance from an already connected device..
   *
   * @param info        Information about the MIDI device used to generate the MidiDeviceId and establish the device
   *                    connection.
   * @param businessync Used for publishing MIDI events about the device state.
   */
  private[scmidi] def this(info: MidiDevice.Info, businessync: Businessync) = {
    this(MidiDeviceId(info), businessync)

    onConnect(info)
  }

  /**
   * Retrieves the information about the MIDI device.
   *
   * @return An optional containing the MIDI device information if available; otherwise, None.
   */
  def info: Option[MidiDevice.Info] = _info

  /**
   * Retrieves the associated Java MIDI API device.
   *
   * @return An optional containing the MIDI device if available; otherwise, None.
   */
  def device: Option[MidiDevice] = _device

  /**
   * Determines if the associated MIDI device is an input device. If it is, then its [[receiver]] can be used,
   * otherwise that will do nothing.
   *
   * @return True if the MIDI device supports input, false otherwise.
   */
  def isInputDevice: Boolean = _device.exists(scmidi.isInputDevice)

  /**
   * Determines if the associated MIDI device is an output device. If it is, then its [[transmitter]] can be used,
   * otherwise that will do nothing.
   *
   * @return True if the MIDI device supports output, false otherwise.
   */
  def isOutputDevice: Boolean = _device.exists(scmidi.isOutputDevice)

  /**
   * Tells whether a MIDI endpoint (like a device) support input and/or output.
   *
   * @return A MidiEndpointType indicating the input/output capabilities of the endpoint.
   */
  def endpointType: MidiEndpointType = MidiEndpointType(isInputDevice, isOutputDevice)

  /**
   * Retrieves the current state of the MIDI device.
   */
  def state = withLock {
    _state
  }

  /**
   * Informs the instance that the device got connected to the system.
   *
   * @param info Information about the MIDI device to connect to.
   * @return true if the connection was successful, or false otherwise.
   */
  private[scmidi] def onConnect(info: MidiDevice.Info): Boolean = withLock {
    require(id.correspondsToInfo(info), s"The given MidiDevice.Info $info does not correspond to the MidiDeviceHandle" +
      s" $id!")

    onDisconnect()

    _device = try {
      Some(MidiSystem.getMidiDevice(info))
    } catch {
      case exception: MidiUnavailableException => None
      case exception: IllegalArgumentException => None
      case exception: Exception =>
        logger.error(s"Failed to connect to $endpointType device $id!", exception)
        businessync.publish(MidiDeviceFailedToConnectEvent(id, exception))
        None
    }

    if (_device.isDefined) {
      _info = Some(info)

      if (_state == State.Closed) {
        _state = State.Connected
      } else if (_state == State.WaitingToOpen) {
        doOpen()
      }

      true
    } else {
      false
    }
  }

  /**
   * Informs the instance that the device got disconnected from the system.
   */
  private[scmidi] def onDisconnect(): Unit = withLock {
    try {
      _device.foreach(_.close())
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to disconnect from $endpointType device $id!", exception)
        businessync.publish(MidiDeviceFailedToDisconnectEvent(id, exception))
    }

    _device = None
    _info = None
  }

  /**
   * Attempts to open the MIDI device associated with this handle.
   *
   *   - If the device is not yet connected, the instance transitions to [[State.WaitingToOpen]] and once it becomes
   *     connected it will then open.
   *   - If the device is already connected, the device will attempt to open immediately.
   *
   * This is a reference-counted operation; the device will only transition to an opened state if this is the first
   * call to the method, and it is in a state that allows opening.
   *
   * @see `MidiDevice.open()` from the Java MIDI API which is called by this method to open the device.
   */
  def open(): Unit = withLock {
    openRefCount += 1
    if (openRefCount == 1) {
      if (_state == State.Closed) {
        _state = State.WaitingToOpen
      } else if (_state == State.Connected) {
        doOpen()
      }
    }
  }

  /**
   * Closes the MIDI device handle, updating its internal state.
   *
   * If this is the last reference to the device, it is properly closed or its state is adjusted depending on
   * the current state and connection status.
   *
   * @see `MidiDevice.close()` from the Java MIDI API which is called by this method to close the device.
   */
  override def close(): Unit = withLock {
    openRefCount -= 1
    if (openRefCount == 0) {
      if (_state == State.WaitingToOpen) {
        _state = State.Closed
      } else if (_state == State.Open) {
        try {
          _device.foreach(_.close())
        } catch {
          case exception: Exception =>
            logger.error(s"Failed to close $endpointType device $id!", exception)
            businessync.publish(MidiDeviceFailedToCloseEvent(id, exception))
        }

        _state = State.Connected
      }
    }
  }

  /**
   * Checks whether the MIDI device is currently connected.
   *
   * @return True if the device is connected, false otherwise.
   */
  def isConnected: Boolean = _device.isDefined

  /**
   * Determines if the MIDI device is currently open.
   *
   * @return True if the device is open, false otherwise.
   * @see [[open]] and [[close]], the method that update this state.
   */
  def isOpen: Boolean = _device.exists(_.isOpen)

  /**
   * Retrieves the MIDI receiver associated with the device which can be used to send MIDI messages to the device.
   *
   * @return The MIDI receiver instance.
   */
  def receiver: Receiver = _receiver

  /**
   * Retrieves the [[MultiTransmitter]] instance associated with the MIDI device, which can be used to subscribe to
   * MIDI messages sent from the device.
   *
   * @return The `MultiTransmitter` instance.
   */
  def multiTransmitter: MultiTransmitter = splitter.multiTransmitter

  private def doOpen(): Unit = withLock {
    _state = State.Open

    try {
      _device.foreach { dev =>
        dev.open()

        if (scmidi.isInputDevice(dev)) {
          dev.getTransmitter.setReceiver(splitter.receiver)
        }
      }
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to open $endpointType device $id.", exception)
        businessync.publish(MidiDeviceFailedToOpenEvent(id, exception))
    }
  }
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
