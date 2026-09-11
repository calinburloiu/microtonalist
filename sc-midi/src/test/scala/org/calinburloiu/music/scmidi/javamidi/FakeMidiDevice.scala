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
import javax.sound.midi.{MidiDevice, MidiMessage, Receiver, Transmitter}
import scala.collection.mutable

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
 * @param name             Name of the device.
 * @param vendor           Vendor of the device.
 * @param maxTransmitters  What `getMaxTransmitters` reports.
 * @param maxReceivers     What `getMaxReceivers` reports.
 * @param openFailure      Thrown by `open` when defined, leaving the device closed.
 * @param closeFailure     Thrown by `close` when defined, leaving the device in its current state.
 * @param providesReceiver Whether `getReceiver` returns [[receiver]] or `null`.
 */
class FakeMidiDevice(name: String,
                     vendor: String = "Roland",
                     maxTransmitters: Int = -1,
                     maxReceivers: Int = -1,
                     openFailure: Option[Exception] = None,
                     closeFailure: Option[Exception] = None,
                     providesReceiver: Boolean = true) extends MidiDevice {

  private val info: MidiDevice.Info = TestDeviceInfo(name, vendor, "Fake MIDI device", "1.0")

  private var _isOpen: Boolean = false
  private var _openCount: Int = 0
  private var _closeCount: Int = 0

  /** The receiver of the device, which records every message sent to it. */
  val receiver: RecordingJavaReceiver = RecordingJavaReceiver()

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
  }

  override def isOpen: Boolean = _isOpen

  override def getMicrosecondPosition: Long = -1L

  override def getMaxReceivers: Int = maxReceivers

  override def getMaxTransmitters: Int = maxTransmitters

  override def getReceiver: Receiver = if (providesReceiver) receiver else null

  override def getReceivers: util.List[Receiver] = util.List.of(receiver)

  override def getTransmitter: Transmitter = transmitter

  override def getTransmitters: util.List[Transmitter] = util.List.of(transmitter)
}

/** A Java Sound [[Receiver]] that records every message sent to it, with its time stamp. */
class RecordingJavaReceiver extends Receiver {
  private val _messages: mutable.Buffer[(MidiMessage, Long)] = mutable.ArrayBuffer()

  /** The messages received so far, in order, each with its time stamp. */
  def messages: Seq[(MidiMessage, Long)] = _messages.toSeq

  override def send(message: MidiMessage, timeStamp: Long): Unit = _messages += (message -> timeStamp)

  override def close(): Unit = {}
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
