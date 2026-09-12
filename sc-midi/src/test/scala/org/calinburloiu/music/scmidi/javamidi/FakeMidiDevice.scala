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

import org.calinburloiu.music.scmidi.MidiDeviceId

import java.util
import javax.sound.midi.{MidiDevice, MidiMessage, MidiUnavailableException, Receiver, Transmitter}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** `MidiDevice.Info` has a protected constructor; this is the subclass tests need to build one. */
class TestDeviceInfo(name: String, vendor: String, description: String, version: String)
  extends MidiDevice.Info(name, vendor, description, version)

/**
 * A Java Sound [[MidiDevice]] test double that records how it is used, so that the Java Sound boundary can be tested
 * without MIDI hardware.
 *
 * The directions the device works in follow from its connection limits, as for a real device: `0` transmitters makes
 * it no input, `0` receivers makes it no output, and `-1` stands for unlimited.
 *
 * Its receivers behave like those of CoreMIDI4J and of the JDK's own devices: each `getReceiver` call creates a new
 * receiver, which stays attached to the device until it is closed, a receiver rejects messages once it is closed or
 * while the device is not open, and closing the device closes all of its receivers.
 *
 * @param name            Name of the device.
 * @param vendor          Vendor of the device.
 * @param maxTransmitters What `getMaxTransmitters` reports.
 * @param maxReceivers    What `getMaxReceivers` reports; with `0`, `getReceiver` throws `MidiUnavailableException`.
 * @param openFailure     Thrown by `open` when defined, leaving the device closed.
 * @param closeFailure    Thrown by `close` when defined, leaving the device in its current state.
 * @param receiverFailure Thrown by `getReceiver` when defined, instead of creating a receiver; a test may set it at any
 *                        time, e.g. to make only a later `getReceiver` call fail.
 */
class FakeMidiDevice(name: String,
                     vendor: String = "Roland",
                     maxTransmitters: Int = -1,
                     maxReceivers: Int = -1,
                     openFailure: Option[Exception] = None,
                     closeFailure: Option[Exception] = None,
                     var receiverFailure: Option[Exception] = None) extends MidiDevice {

  private val info: MidiDevice.Info = TestDeviceInfo(name, vendor, "Fake MIDI device", "1.0")

  private var _isOpen: Boolean = false
  private var _openCount: Int = 0
  private var _closeCount: Int = 0
  private var _receiverCount: Int = 0
  private val openReceivers: mutable.Buffer[Receiver] = mutable.ArrayBuffer()
  private val _receivedMessages: mutable.Buffer[(MidiMessage, Long)] = mutable.ArrayBuffer()

  /** The messages sent to any receiver of the device so far, in order, each with its time stamp. */
  def receivedMessages: Seq[(MidiMessage, Long)] = _receivedMessages.toSeq

  /** How many receivers `getReceiver` created. */
  def receiverCount: Int = _receiverCount

  /** The transmitter of the device; a test sends to its receiver to simulate a message coming from the device. */
  val transmitter: FakeTransmitter = FakeTransmitter()

  /** The [[MidiDeviceId]] Microtonalist derives from the device's name and vendor. */
  def id: MidiDeviceId = MidiDeviceId(name, vendor)

  /** How many times the device was successfully opened. */
  def openCount: Int = _openCount

  /** How many times `close` was called on the device, including the calls that failed. */
  def closeCount: Int = _closeCount

  override def getDeviceInfo: MidiDevice.Info = info

  override def open(): Unit = {
    openFailure.foreach(failure => throw failure)
    _openCount += 1
    _isOpen = true
  }

  override def close(): Unit = {
    _closeCount += 1
    closeFailure.foreach(failure => throw failure)
    _isOpen = false
    openReceivers.toSeq.foreach(_.close())
  }

  override def isOpen: Boolean = _isOpen

  override def getMicrosecondPosition: Long = -1L

  override def getMaxReceivers: Int = maxReceivers

  override def getMaxTransmitters: Int = maxTransmitters

  override def getReceiver: Receiver = {
    if (maxReceivers == 0) {
      throw MidiUnavailableException("The device has no receivers")
    }
    receiverFailure.foreach(failure => throw failure)

    val receiver = FakeReceiver()
    openReceivers += receiver
    _receiverCount += 1
    receiver
  }

  override def getReceivers: util.List[Receiver] = util.List.copyOf(openReceivers.asJava)

  override def getTransmitter: Transmitter = transmitter

  override def getTransmitters: util.List[Transmitter] = util.List.of(transmitter)

  /** A receiver of the device, which records the messages sent to it on the device. */
  private class FakeReceiver extends Receiver {
    private var isClosed: Boolean = false

    override def send(message: MidiMessage, timeStamp: Long): Unit = {
      if (isClosed) {
        throw IllegalStateException("The receiver is closed")
      }
      if (!_isOpen) {
        throw IllegalStateException("The device of the receiver is not open")
      }
      _receivedMessages += (message -> timeStamp)
    }

    override def close(): Unit = {
      isClosed = true
      openReceivers -= this
    }
  }
}

/** A Java Sound [[Transmitter]] that only holds the receiver it is given, `null` until then. */
class FakeTransmitter extends Transmitter {
  private var _receiver: Receiver = null

  override def setReceiver(receiver: Receiver): Unit = {
    _receiver = receiver
  }

  override def getReceiver: Receiver = _receiver

  override def close(): Unit = {}
}
