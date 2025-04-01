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

import java.util.concurrent.locks.ReentrantLock
import javax.sound.midi.*

// TODO #122 Re-document
class MidiDeviceHandle(val id: MidiDeviceId) extends AutoCloseable {

  import MidiDeviceHandle.*

  @volatile private var _info: Option[MidiDevice.Info] = None
  @volatile private var _device: Option[MidiDevice] = None

  private var _state: State = MidiDeviceHandle.State.Closed

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

  def this(info: MidiDevice.Info) = {
    this(MidiDeviceId(info))

    onConnect(info)
  }

  def info: Option[MidiDevice.Info] = _info

  def device: Option[MidiDevice] = _device

  def isInputDevice: Boolean = _device.exists(scmidi.isInputDevice)

  def isOutputDevice: Boolean = _device.exists(scmidi.isOutputDevice)

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
    onDisconnect()

    _device = try {
      Some(MidiSystem.getMidiDevice(info))
    } catch {
      case e: MidiUnavailableException => None
      case e: IllegalArgumentException => None
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
    _device.foreach(_.close())
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
        _device.foreach(_.close())

        _state = State.Connected
      }
    }
  }

  def isOpen: Boolean = _device.exists(_.isOpen)

  def receiver: Receiver = _receiver

  def multiTransmitter: MultiTransmitter = splitter.multiTransmitter

  private def doOpen(): Unit = withLock {
    _state = State.Open

    _device.foreach { dev =>
      dev.open()

      val maxTransmitters = dev.getMaxTransmitters
      if (maxTransmitters == -1 || maxTransmitters > 0) {
        dev.getTransmitter.setReceiver(splitter.receiver)
      }
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
