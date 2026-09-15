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

package org.calinburloiu.music.scmidi.javamidi

import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{Midi1Msg, Midi2Msg, MidiMsg}

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Lock, ReentrantLock}
import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.{MidiDevice, MidiMessage, Receiver}
import scala.util.Try

/**
 * [[MidiDeviceHandle]] over a Java Sound [[MidiDevice]], for one direction of it.
 *
 * [[JavaMidiManager]] creates the instances, one per device per direction, and is the only one to change their state.
 * It does so through five commands named after the transitions of [[MidiDeviceHandle.State]]:
 *
 *   - [[connect]], with the device it resolved, when the physical device gets connected;
 *   - [[disconnect]] when the device gets disconnected;
 *   - [[open]] and [[close]], which take and release one reference to the device;
 *   - [[closeAll]], which releases every reference.
 *
 * Only while the device is connected are the [[MidiDevice]], via the [[device]] accessor, and the [[MidiDeviceInfo]],
 * via the [[info]] accessor, defined on the instance.
 *
 * A command publishes nothing: it returns the [[MidiEvent]]s of the transitions it made, in order, for the manager to
 * publish once it released its lock. A transition that fails still completes, setting to false the property it
 * concerns as [[MidiDeviceHandle.State]] describes, and reports the failure event instead of the success event.
 *
 * The handle is the only place where messages cross between the Scala model and Java Sound:
 *
 *   - [[receiver]] converts each [[Midi1Msg]] with `asJava` and sends it to the open device. A [[Midi2Msg]] is
 *     dropped with a warning, since a Java Sound device speaks MIDI 1.0 only.
 *   - The Java `Receiver` registered on the device's transmitter converts with `asScala` and fans out to the
 *     receivers of [[transmitter]].
 *
 * Sending never takes the lock of the handle. A message that reaches a receiver Java Sound already closed, because
 * the device vanished before the manager learned of it, is dropped: the first one after each open is logged at warn
 * level, and the following ones at debug level.
 *
 * @param id        Unique identifier of the MIDI device.
 * @param direction The direction of the endpoint of the manager that owns the handle, [[MidiEndpointType.Input]] or
 *                  [[MidiEndpointType.Output]], which the events of the handle carry as their `endpointType`. It is
 *                  not [[endpointType]], which tells the directions the device works in.
 */
@ThreadSafe
class JavaMidiDeviceHandle private[javamidi](override val id: MidiDeviceId,
                                             private[javamidi] val direction: MidiEndpointType)
  extends MidiDeviceHandle, Locking, LazyLogging {
  require(direction == MidiEndpointType.Input || direction == MidiEndpointType.Output,
    s"The direction of a JavaMidiDeviceHandle must be input or output; got $direction!")

  private implicit val lock: Lock = ReentrantLock()

  @volatile private var _info: Option[MidiDeviceInfo] = None
  @volatile private var _device: Option[MidiDevice] = None
  /**
   * The receiver obtained from the device when it was last opened, if it is an output. A Java Sound device creates a
   * new receiver on each `getReceiver` call and keeps it until it is closed, so the handle obtains a single one per
   * open; closing the device closes it. It is defined only while the handle is open, which is what [[receiver]]
   * relies on to send without taking the lock.
   */
  @volatile private var deviceReceiver: Option[Receiver] = None

  /** Whether a message dropped since the device last opened was already reported at warn level. */
  private val hasWarnedOfDroppedMessage: AtomicBoolean = AtomicBoolean(false)

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
        for (javaReceiver <- deviceReceiver) {
          try {
            javaReceiver.send(midi1Message.asJava, timeStamp)
          } catch {
            // TODO #302 Inform the manager that the device is gone, instead of only dropping the message until
            //  CoreMIDI4J reports the change.
            case _: IllegalStateException => logDroppedMessage(midi1Message)
          }
        }
      case midi2Message: Midi2Msg =>
        logger.warn(s"Dropping $midi2Message sent to device $id: Java Sound devices speak MIDI 1.0 only.")
    }
  }

  override def info: Option[MidiDeviceInfo] = _info

  /**
   * Retrieves the Java Sound device behind this handle. It is `private[javamidi]`, a member of the implementation
   * only and never part of the [[MidiDeviceHandle]] API, so that no `javax.sound.midi` type escapes through it.
   *
   * @return The MIDI device while it is connected; otherwise, None.
   */
  private[javamidi] def device: Option[MidiDevice] = _device

  override def state: State = withLock {
    _state
  }

  override def receiver: MidiReceiver = _receiver

  override def transmitter: ConcurrentMidiTransmitter = _transmitter

  /**
   * Informs the handle that its device is connected to the system, as the latest environment scan resolved it.
   *
   * On a handle that is not connected, this is the `connect` transition: a [[State.Closed]] handle moves to
   * [[State.Connected]], and a [[State.WaitingToOpen]] handle opens the device. On a connected handle, the same
   * `device` instance only updates the info. Another instance means the device was replugged or swapped between two
   * scans: a [[State.Connected]] handle swaps it silently, and a [[State.Open]] handle closes the device it held and
   * opens the new one.
   *
   * @param info   Information about the connected MIDI device.
   * @param device The resolved Java Sound device, which [[JavaMidiManager]] obtains once per environment scan.
   * @return the events of the transitions made, in order.
   * @throws IllegalArgumentException if `info` does not correspond to the [[id]] of the handle, which is then left
   *                                  unchanged.
   */
  private[javamidi] def connect(info: MidiDeviceInfo, device: MidiDevice): Seq[MidiEvent] = withLock {
    require(id.correspondsToInfo(info), s"The given MidiDeviceInfo $info does not correspond to the " +
      s"JavaMidiDeviceHandle $id!")

    val previousDevice = _device
    _info = Some(info)
    _device = Some(device)

    (_state, previousDevice) match {
      case (State.Closed, _) =>
        _state = State.Connected
        logConnected(info)
        Seq(MidiDeviceConnectedEvent(id, direction))
      case (State.WaitingToOpen, _) =>
        logConnected(info)
        MidiDeviceConnectedEvent(id, direction) +: doOpen(device)
      case (State.Open, Some(openDevice)) if openDevice ne device =>
        closeOpenDevice(openDevice) ++ doOpen(device)
      case _ =>
        Seq.empty
    }
  }

  /**
   * Informs the handle that its device got disconnected from the system.
   *
   * The handle closes the device, whether or not it opened it, since closing a Java Sound device that is not open, or
   * that CoreMIDI4J already closed, is harmless. It then forgets the device and its info:
   *
   *   - an [[State.Open]] handle moves to [[State.WaitingToOpen]], keeping its references;
   *   - a [[State.Connected]] handle moves to [[State.Closed]];
   *   - a handle that is not connected is left unchanged.
   *
   * @return the events of the transitions made, in order: [[MidiDeviceClosedEvent]] for a handle that was open, then
   *         [[MidiDeviceDisconnectedEvent]]; or only [[MidiDeviceFailedToDisconnectEvent]] if closing the device fails,
   *         in which case the handle still ends up disconnected.
   */
  private[javamidi] def disconnect(): Seq[MidiEvent] = withLock {
    _device match {
      case Some(device) =>
        val wasOpen = _state == State.Open
        _state = if (wasOpen) State.WaitingToOpen else State.Closed
        deviceReceiver = None
        _device = None
        _info = None

        try {
          device.close()
          logger.warn(s"${direction.toString.capitalize} device $id was disconnected.")
          val closedEvents = if (wasOpen) Seq(MidiDeviceClosedEvent(id, direction)) else Seq.empty
          closedEvents :+ MidiDeviceDisconnectedEvent(id, direction)
        } catch {
          case exception: Exception =>
            logger.error(s"Failed to disconnect from $direction device $id!", exception)
            Seq(MidiDeviceFailedToDisconnectEvent(id, direction, exception))
        }
      case None =>
        Seq.empty
    }
  }

  /**
   * Takes one reference to the device. Only the first reference makes a transition: a [[State.Connected]] handle
   * opens the device, and a [[State.Closed]] handle moves to [[State.WaitingToOpen]], to open the device once it gets
   * connected.
   *
   * @return the event of the transition made: [[MidiDeviceOpenedEvent]], or [[MidiDeviceFailedToOpenEvent]] if the
   *         device fails to open; nothing if the device is not connected or if a reference was already held.
   * @see `MidiDevice.open()` from the Java MIDI API, which is called by this method to open the device.
   */
  private[javamidi] def open(): Seq[MidiEvent] = withLock {
    openRefCount += 1
    if (openRefCount > 1) {
      Seq.empty
    } else {
      _device match {
        case Some(device) =>
          doOpen(device)
        case None =>
          _state = State.WaitingToOpen
          Seq.empty
      }
    }
  }

  /**
   * Releases one reference to the device. Only releasing the last reference makes a transition, as [[closeAll]]
   * describes. With no reference held, it does nothing.
   *
   * @return the events of the transition made, as for [[closeAll]].
   * @see `MidiDevice.close()` from the Java MIDI API, which is called by this method to close the device.
   */
  private[javamidi] def close(): Seq[MidiEvent] = withLock {
    if (openRefCount > 1) {
      openRefCount -= 1
      Seq.empty
    } else {
      closeAll()
    }
  }

  /**
   * Releases every reference held to the device: an [[State.Open]] handle closes the device and moves to
   * [[State.Connected]], and a [[State.WaitingToOpen]] handle moves to [[State.Closed]]. With no reference held, it
   * does nothing.
   *
   * @return the event of the transition made: [[MidiDeviceClosedEvent]], or [[MidiDeviceFailedToCloseEvent]] if the
   *         device fails to close, in which case the handle still moves to [[State.Connected]]; nothing otherwise.
   */
  private[javamidi] def closeAll(): Seq[MidiEvent] = withLock {
    if (openRefCount == 0) {
      Seq.empty
    } else {
      openRefCount = 0
      _device match {
        case Some(device) =>
          _state = State.Connected
          closeOpenDevice(device)
        case None =>
          _state = State.Closed
          Seq.empty
      }
    }
  }

  /**
   * Opens `device`, obtaining the receiver of an output and subscribing to the transmitter of an input, and only then
   * moves to [[State.Open]]. On any failure, it closes the device as far as it can and rolls back to
   * [[State.Connected]] with no reference held, so that the handle is no longer requested to open.
   */
  private def doOpen(device: MidiDevice): Seq[MidiEvent] = {
    try {
      device.open()
      if (isOutputDevice) {
        deviceReceiver = Some(device.getReceiver)
      }
      if (isInputDevice) {
        device.getTransmitter.setReceiver(inboundReceiver)
      }

      hasWarnedOfDroppedMessage.set(false)
      _state = State.Open
      logger.info(s"Successfully opened $direction device $id.")
      Seq(MidiDeviceOpenedEvent(id, direction))
    } catch {
      case exception: Exception =>
        deviceReceiver = None
        // The failure to report is the one to open: a failure to close the device on the way back is attached to it
        // as a suppressed exception instead, so the error log's stack trace and the event's cause still carry it.
        Try(device.close()).failed.foreach(exception.addSuppressed)
        _state = State.Connected
        openRefCount = 0

        logger.error(s"Failed to open $direction device $id.", exception)
        Seq(MidiDeviceFailedToOpenEvent(id, direction, exception))
    }
  }

  /** Closes the open `device` as the handle leaves [[State.Open]], leaving the state to the caller. */
  private def closeOpenDevice(device: MidiDevice): Seq[MidiEvent] = {
    deviceReceiver = None
    try {
      device.close()
      logger.info(s"Successfully closed $direction device $id.")
      Seq(MidiDeviceClosedEvent(id, direction))
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to close $direction device $id!", exception)
        Seq(MidiDeviceFailedToCloseEvent(id, direction, exception))
    }
  }

  private def logDroppedMessage(message: Midi1Msg): Unit = {
    if (hasWarnedOfDroppedMessage.compareAndSet(false, true)) {
      logger.warn(s"Dropping the messages sent to $direction device $id, which Java Sound already closed, until it " +
        "opens again.")
    } else {
      logger.debug(s"Dropping $message sent to $direction device $id, which Java Sound already closed.")
    }
  }

  private def logConnected(info: MidiDeviceInfo): Unit = {
    logger.whenDebugEnabled {
      val (handlerType, connectionLimit) = if (direction == MidiEndpointType.Input) {
        ("transmitters", info.transmittersLimit)
      } else {
        ("receivers", info.receiversLimit)
      }

      logger.debug(s"${direction.toString.capitalize} device $id with $connectionLimit $handlerType was connected.")
    }
  }
}
