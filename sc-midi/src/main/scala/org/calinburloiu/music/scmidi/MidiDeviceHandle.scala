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
import org.calinburloiu.music.scmidi

import java.util.concurrent.locks.ReentrantLock
import javax.sound.midi.*

// TODO #122 Re-document
class MidiDeviceHandle(val id: MidiDeviceId,
                       businessync: Businessync) extends AutoCloseable, LazyLogging {

  import MidiDeviceHandle.*

  @volatile private var _info: Option[MidiDevice.Info] = None
  @volatile private var _device: Option[MidiDevice] = None

  private var _state: State = State.Closed

  private var openRefCount: Int = 0

  private lazy val _receiver: HandleReceiver = new HandleReceiver
  private lazy val splitter: MidiSplitter = new MidiSplitter

  private val lock: ReentrantLock = new ReentrantLock()

  private class HandleReceiver extends Receiver {
    override def send(message: MidiMessage, timeStamp: Long): Unit = {
      for (midiDevice <- _device if midiDevice.isOpen; deviceReceiver <- Option(midiDevice.getReceiver)) {
        deviceReceiver.send(message, timeStamp)
      }
    }

    override def close(): Unit = {}
  }

  def this(info: MidiDevice.Info, businessync: Businessync) = {
    this(MidiDeviceId(info), businessync)

    onConnect(info)
  }

  def info: Option[MidiDevice.Info] = _info

  def device: Option[MidiDevice] = _device

  def isInputDevice: Boolean = _device.exists(scmidi.isInputDevice)

  def isOutputDevice: Boolean = _device.exists(scmidi.isOutputDevice)

  def endpointType: MidiEndpointType = MidiEndpointType(isInputDevice, isOutputDevice)

  def state = withLock {
    _state
  }

  /**
   * Attempts to connect the device corresponding to the given [[MidiDevice.Info]].
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

  def isConnected: Boolean = _device.isDefined

  def isOpen: Boolean = _device.exists(_.isOpen)

  def receiver: Receiver = _receiver

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

  @inline
  private def withLock[R](block: => R): R = {
    lock.lock()
    try {
      block
    } finally {
      lock.unlock()
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
