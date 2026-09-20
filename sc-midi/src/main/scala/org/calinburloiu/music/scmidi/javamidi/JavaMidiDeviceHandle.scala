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
 * Only while the device is connected are the [[MidiDevice]], via the [[javaDevice]] accessor, and the
 * [[MidiDeviceInfo]], via the [[info]] accessor, defined on the instance.
 *
 * A command publishes nothing: it returns the [[MidiEvent]]s of the transitions it made, in order, for the manager to
 * publish once it released its lock. A transition that fails still completes, setting to false the property it
 * concerns as [[MidiDeviceHandle.State]] describes, and reports the failure event instead of the success event.
 *
 * The handle is the only place where messages cross between the Scala model and Java Sound:
 *
 *   - [[receiver]] converts each [[Midi1Msg]] with `asJava` and sends it to the open device. A [[Midi2Msg]] is
 *     dropped, since a Java Sound device speaks MIDI 1.0 only.
 *   - The Java `Receiver` registered on the device's transmitter converts with `asScala` and fans out to the
 *     receivers of [[transmitter]].
 *
 * Sending never takes the lock of the handle. A dropped message is reported once at warn level, naming the reason,
 * and at debug level from then on, so that a stream of them does not flood the log: for a [[Midi2Msg]] once per
 * handle, the device never gaining the ability to speak MIDI 2.0, and for a message that reaches a receiver Java
 * Sound already closed, because the device vanished before the manager learned of it, once per open.
 *
 * @param id                 Unique identifier of the MIDI device.
 * @param requestedDirection The direction the handle is requested for, [[MidiDirection.Input]] or
 *                           [[MidiDirection.Output]]: that of the endpoint of the manager that owns it, which the
 *                           events of the handle carry as their `direction`. It is not [[direction]], which tells the
 *                           directions the device itself works in and so takes any of the four values,
 *                           [[MidiDirection.InputOutput]] and [[MidiDirection.None]] included.
 */
@ThreadSafe
class JavaMidiDeviceHandle private[javamidi](override val id: MidiDeviceId,
                                             private[javamidi] val requestedDirection: MidiDirection)
  extends MidiDeviceHandle, Locking, LazyLogging {
  require(requestedDirection == MidiDirection.Input || requestedDirection == MidiDirection.Output,
    s"The requested direction of a JavaMidiDeviceHandle must be input or output; got $requestedDirection!")

  private implicit val lock: Lock = ReentrantLock()

  @volatile private var _info: Option[MidiDeviceInfo] = None
  @volatile private var _javaDevice: Option[MidiDevice] = None
  /**
   * The receiver obtained from the device when it was last opened, if it is an output. A Java Sound device creates a
   * new receiver on each `getReceiver` call and keeps it until it is closed, so the handle obtains a single one per
   * open; closing the device closes it. It is defined only while the handle is open, which is what [[receiver]]
   * relies on to send without taking the lock.
   */
  @volatile private var deviceReceiver: Option[Receiver] = None

  /** Whether a message dropped since the device last opened was already reported at warn level. */
  private val hasWarnedOfDroppedMessage: AtomicBoolean = AtomicBoolean(false)

  /**
   * Whether a MIDI 2.0 message dropped was already reported at warn level. Unlike [[hasWarnedOfDroppedMessage]], it
   * is never reset: a Java Sound device speaks MIDI 1.0 for as long as the handle lives.
   */
  private val hasWarnedOfDroppedMidi2Message: AtomicBoolean = AtomicBoolean(false)

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
      case midi2Message: Midi2Msg => logDroppedMidi2Message(midi2Message)
    }
  }

  override def info: Option[MidiDeviceInfo] = _info

  /**
   * Retrieves the Java Sound device behind this handle. It is `private[javamidi]`, a member of the implementation
   * only and never part of the [[MidiDeviceHandle]] API, so that no `javax.sound.midi` type escapes through it.
   *
   * @return The MIDI device while it is connected; otherwise, None.
   */
  private[javamidi] def javaDevice: Option[MidiDevice] = _javaDevice

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
   * `javaDevice` instance only updates the info. Another instance means the device was replugged or swapped between
   * two scans, and a [[State.Connected]] handle swaps it silently.
   *
   * A [[State.Open]] handle takes another instance for a swap only when the one it holds is no longer open: it then
   * closes the device it held and opens the new one. CoreMIDI4J closes the instance of an endpoint that vanished before
   * it reports the change, so a replugged or swapped device always passes that check. A JDK `Sequencer` or
   * `Synthesizer`, which CoreMIDI4J passes through, resolves to a new instance on every lookup instead, while the one
   * the handle opened stays open: the handle then keeps the device it holds and its info, and reports nothing.
   *
   * @param info       Information about the connected MIDI device.
   * @param javaDevice The resolved Java Sound device, which [[JavaMidiManager]] obtains once per environment scan.
   * @return the events of the transitions made, in order.
   * @throws IllegalArgumentException if `info` does not correspond to the [[id]] of the handle, which is then left
   *                                  unchanged.
   */
  private[javamidi] def connect(info: MidiDeviceInfo, javaDevice: MidiDevice): Seq[MidiEvent] = withLock {
    require(id.correspondsToInfo(info), s"The given MidiDeviceInfo $info does not correspond to the " +
      s"JavaMidiDeviceHandle $id!")

    (_state, _javaDevice) match {
      case (State.Closed, _) =>
        hold(info, javaDevice)
        _state = State.Connected
        logConnected(info)
        Seq(MidiDeviceConnectedEvent(id, requestedDirection))
      case (State.WaitingToOpen, _) =>
        hold(info, javaDevice)
        logConnected(info)
        MidiDeviceConnectedEvent(id, requestedDirection) +: doOpen(javaDevice)
      case (State.Open, Some(heldJavaDevice)) if (heldJavaDevice ne javaDevice) && !heldJavaDevice.isOpen =>
        hold(info, javaDevice)
        closeOpenDevice(heldJavaDevice) ++ doOpen(javaDevice)
      case (State.Open, Some(heldJavaDevice)) if heldJavaDevice ne javaDevice =>
        // Not a swap: a device resolving to a new instance on every lookup, while the one held stays open and in use
        Seq.empty
      case (State.Connected | State.Open, Some(_)) =>
        // Replacing what the handle holds neither opens nor closes anything here: a State.Connected handle swaps a new
        // instance silently, and a State.Open handle, left by the cases above with the very instance it holds, only
        // refreshes the info.
        hold(info, javaDevice)
        Seq.empty
      case (_, None) =>
        // Cannot occur: State.Connected and State.Open, the states left, both imply a held device.
        logger.error(s"Ignoring the connection of $requestedDirection device $id: its handle is ${_state} while " +
          "holding no device, which should never happen!")
        Seq.empty
    }
  }

  /** Makes `javaDevice`, described by `info`, the connected device of the handle. */
  private def hold(info: MidiDeviceInfo, javaDevice: MidiDevice): Unit = {
    _info = Some(info)
    _javaDevice = Some(javaDevice)
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
    _javaDevice match {
      case Some(javaDevice) =>
        val wasOpen = _state == State.Open
        _state = if (wasOpen) State.WaitingToOpen else State.Closed
        deviceReceiver = None
        _javaDevice = None
        _info = None

        try {
          javaDevice.close()
          if (wasOpen) {
            logger.info(s"Successfully closed $requestedDirection device $id.")
          }
          logDisconnected(wasOpen)
          val closedEvents = if (wasOpen) Seq(MidiDeviceClosedEvent(id, requestedDirection)) else Seq.empty
          closedEvents :+ MidiDeviceDisconnectedEvent(id, requestedDirection)
        } catch {
          case exception: Exception =>
            logger.error(s"Failed to disconnect from $requestedDirection device $id!", exception)
            Seq(MidiDeviceFailedToDisconnectEvent(id, requestedDirection, exception))
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
      _javaDevice match {
        case Some(javaDevice) =>
          doOpen(javaDevice)
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
      _javaDevice match {
        case Some(javaDevice) =>
          _state = State.Connected
          closeOpenDevice(javaDevice)
        case None =>
          _state = State.Closed
          Seq.empty
      }
    }
  }

  /**
   * Opens `javaDevice`, obtaining its receiver when the handle is requested for output and subscribing to its
   * transmitter when it is requested for input, and only then moves to [[State.Open]]. On any failure, it closes the
   * device as far as it can and rolls back to [[State.Connected]] with no reference held, so that the handle is no
   * longer requested to open.
   */
  private def doOpen(javaDevice: MidiDevice): Seq[MidiEvent] = {
    try {
      // TODO #315 A device working in both directions has one handle per direction over the same MidiDevice
      //  instance, and each opens and closes it on its own. Java Sound closes it outright on the first close, so
      //  either handle can close it under the other, which goes on reporting State.Open over a dead device.
      javaDevice.open()
      // Keyed on what the handle is for, not on what the device can do: a device that works in both directions has
      // one handle per direction, and an input handle taking a receiver would spend one of the device's, which are
      // limited on some of them.
      if (requestedDirection.isOutput) {
        deviceReceiver = Some(javaDevice.getReceiver)
      }
      if (requestedDirection.isInput) {
        javaDevice.getTransmitter.setReceiver(inboundReceiver)
      }

      hasWarnedOfDroppedMessage.set(false)
      _state = State.Open
      logger.info(s"Successfully opened $requestedDirection device $id.")
      Seq(MidiDeviceOpenedEvent(id, requestedDirection))
    } catch {
      case exception: Exception =>
        deviceReceiver = None
        // The failure to report is the one to open: a failure to close the device on the way back is attached to it
        // as a suppressed exception instead, so the error log's stack trace and the event's cause still carry it.
        Try(javaDevice.close()).failed.foreach(exception.addSuppressed)
        _state = State.Connected
        openRefCount = 0

        logger.error(s"Failed to open $requestedDirection device $id.", exception)
        Seq(MidiDeviceFailedToOpenEvent(id, requestedDirection, exception))
    }
  }

  /** Closes the open `javaDevice` as the handle leaves [[State.Open]], leaving the state to the caller. */
  private def closeOpenDevice(javaDevice: MidiDevice): Seq[MidiEvent] = {
    deviceReceiver = None
    try {
      javaDevice.close()
      logger.info(s"Successfully closed $requestedDirection device $id.")
      Seq(MidiDeviceClosedEvent(id, requestedDirection))
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to close $requestedDirection device $id!", exception)
        Seq(MidiDeviceFailedToCloseEvent(id, requestedDirection, exception))
    }
  }

  private def logDroppedMessage(message: Midi1Msg): Unit = logDropped(hasWarnedOfDroppedMessage,
    s"Dropping the messages sent to $requestedDirection device $id, which Java Sound already closed, until it opens " +
      "again.",
    s"Dropping $message sent to $requestedDirection device $id, which Java Sound already closed.")

  private def logDroppedMidi2Message(message: Midi2Msg): Unit = logDropped(hasWarnedOfDroppedMidi2Message,
    s"Dropping the MIDI 2.0 messages sent to $requestedDirection device $id: Java Sound devices speak MIDI 1.0 only.",
    s"Dropping $message sent to $requestedDirection device $id: Java Sound devices speak MIDI 1.0 only.")

  /**
   * Reports a dropped message: the first one for its reason at warn level, naming the reason, and the following ones
   * at debug level, naming the message too.
   */
  private def logDropped(hasWarned: AtomicBoolean, warnMessage: => String, debugMessage: => String): Unit = {
    if (hasWarned.compareAndSet(false, true)) {
      logger.warn(warnMessage)
    } else {
      logger.debug(debugMessage)
    }
  }

  /**
   * Reports that the device got disconnected: at warn level if the handle had it open, at debug level otherwise.
   *
   * Losing a device that was open interrupts what it was playing, which the user needs to know about. A device nobody
   * opened is merely one that stopped being available, so it is reported at the level of the connection that made it
   * so.
   */
  private def logDisconnected(wasOpen: Boolean): Unit = {
    def message: String = s"${requestedDirection.toString.capitalize} device $id was disconnected."

    if (wasOpen) {
      logger.warn(message)
    } else {
      logger.debug(message)
    }
  }

  private def logConnected(info: MidiDeviceInfo): Unit = {
    logger.whenDebugEnabled {
      val (handlerType, connectionLimit) = if (requestedDirection == MidiDirection.Input) {
        ("transmitters", info.transmittersLimit)
      } else {
        ("receivers", info.receiversLimit)
      }

      logger.debug(s"${requestedDirection.toString.capitalize} device $id with $connectionLimit $handlerType was " +
        "connected.")
    }
  }
}
