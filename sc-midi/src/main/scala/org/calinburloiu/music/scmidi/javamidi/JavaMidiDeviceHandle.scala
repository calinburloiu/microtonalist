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

package org.calinburloiu.music.scmidi.javamidi

import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{Midi1Msg, Midi2Msg, MidiMsg}

import java.util.concurrent.locks.{Lock, ReentrantLock}
import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.{MidiDevice, MidiMessage, Receiver}

/**
 * [[MidiDeviceHandle]] over a Java Sound [[MidiDevice]].
 *
 * [[JavaMidiManager]] creates the instances and keeps them up to date: it calls [[onConnect]] with the device it
 * resolved when the device is connected or opened, and [[onDisconnect]] when the device goes away. Only while the
 * device is connected are the [[MidiDevice]], via the [[device]] accessor, and the [[MidiDeviceInfo]], via the
 * [[info]] accessor, defined on the instance.
 *
 * The handle is the only place where messages cross between the Scala model and Java Sound: [[receiver]] converts
 * each [[Midi1Msg]] with `asJava` and sends it to the open device (a [[Midi2Msg]] is dropped with a warning, since a
 * Java Sound device speaks MIDI 1.0 only), and the Java `Receiver` registered on the device's transmitter converts
 * with `asScala` and fans out to the receivers of [[transmitter]].
 *
 * @param id          Unique identifier of the MIDI device.
 * @param businessync Used for publishing MIDI events about the device state.
 */
@ThreadSafe
class JavaMidiDeviceHandle private[javamidi](override val id: MidiDeviceId,
                                             businessync: Businessync)
  extends MidiDeviceHandle, Locking, LazyLogging {

  private implicit val lock: Lock = ReentrantLock()

  @volatile private var _info: Option[MidiDeviceInfo] = None
  @volatile private var _device: Option[MidiDevice] = None

  private var _state: State = State.Closed

  private var openRefCount: Int = 0

  private lazy val _receiver: HandleReceiver = HandleReceiver()
  private lazy val _transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
  private lazy val splitter: MidiSplitter = MidiSplitter(_transmitter)

  /** Inbound boundary: the Java receiver handed to the device's transmitter converts and fans out. */
  private lazy val inboundReceiver: Receiver = new Receiver {
    override def send(message: MidiMessage, timeStamp: Long): Unit = splitter.send(message.asScala, timeStamp)

    override def close(): Unit = {}
  }

  /** Outbound boundary: converts to Java Sound and sends to the open device. */
  private class HandleReceiver extends MidiReceiver {
    override def send(message: MidiMsg, timeStamp: Long): Unit = message match {
      case midi1Message: Midi1Msg =>
        for (midiDevice <- _device if midiDevice.isOpen; deviceReceiver <- Option(midiDevice.getReceiver)) {
          deviceReceiver.send(midi1Message.asJava, timeStamp)
        }
      case midi2Message: Midi2Msg =>
        logger.warn(s"Dropping $midi2Message sent to device $id: Java Sound devices speak MIDI 1.0 only.")
    }

    override def close(): Unit = {}
  }

  override def info: Option[MidiDeviceInfo] = _info

  /**
   * Retrieves the Java Sound device behind this handle. It is a member of the implementation only, not of the
   * [[MidiDeviceHandle]] API.
   *
   * @return The MIDI device while it is connected; otherwise, None.
   */
  def device: Option[MidiDevice] = _device

  override def state: State = withLock {
    _state
  }

  /**
   * Informs the instance that the device got connected to the system.
   *
   * @param info   Information about the connected MIDI device.
   * @param device The resolved Java Sound device, which [[JavaMidiManager]] obtains once per environment scan.
   */
  private[javamidi] def onConnect(info: MidiDeviceInfo, device: MidiDevice): Unit = withLock {
    require(id.correspondsToInfo(info), s"The given MidiDeviceInfo $info does not correspond to the " +
      s"JavaMidiDeviceHandle $id!")

    onDisconnect()

    _device = Some(device)
    _info = Some(info)

    if (_state == State.Closed) {
      _state = State.Connected
    } else if (_state == State.WaitingToOpen) {
      doOpen()
    }
  }

  /**
   * Informs the instance that the device got disconnected from the system.
   */
  private[javamidi] def onDisconnect(): Unit = withLock {
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
   * @see `MidiDevice.open()` from the Java MIDI API, which is called by this method to open the device.
   */
  override def open(): Unit = withLock {
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
   * @see `MidiDevice.close()` from the Java MIDI API, which is called by this method to close the device.
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

  override def isConnected: Boolean = _device.isDefined

  override def isOpen: Boolean = _device.exists(_.isOpen)

  override def receiver: MidiReceiver = _receiver

  override def transmitter: ConcurrentMidiTransmitter = _transmitter

  private def doOpen(): Unit = withLock {
    _state = State.Open

    try {
      _device.foreach { dev =>
        dev.open()

        if (isInputDevice) {
          dev.getTransmitter.setReceiver(inboundReceiver)
        }
      }
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to open $endpointType device $id.", exception)
        businessync.publish(MidiDeviceFailedToOpenEvent(id, exception))
    }
  }
}
