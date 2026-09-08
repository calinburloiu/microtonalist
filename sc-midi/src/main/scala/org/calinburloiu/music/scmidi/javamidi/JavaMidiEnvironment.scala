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

import uk.co.xfactorylibrarians.coremidi4j.{CoreMidiDeviceProvider, CoreMidiNotification}

import javax.sound.midi.{MidiDevice, MidiSystem}
import scala.collection.immutable.ArraySeq

/**
 * The Java Sound environment a [[JavaMidiManager]] runs in: which devices are present, how a device is resolved, and
 * how changes of the environment are reported.
 *
 * It is the seam that keeps the manager's device bookkeeping testable without MIDI hardware: a test can pass a fake
 * environment over fake devices. [[CoreMidi4JEnvironment]] is the production implementation.
 */
trait JavaMidiEnvironment {

  /** The information of every MIDI device currently present, as Java Sound reports it. */
  def deviceInfos: Seq[MidiDevice.Info]

  /**
   * Resolves the device described by `info`.
   *
   * A device's id is derived from `info` on the failure path (`info.asMidiDeviceId`, used when this method throws)
   * and from the resolved device's own info (`device.asMidiDeviceInfo`) on the success path. The two agree for every
   * real Java Sound provider, because `MidiSystem.getMidiDevice(info).getDeviceInfo` is that same `info`. A fake
   * implementation of this trait must keep the two consistent too, or a device's id will diverge between the
   * failure event and the connected set.
   *
   * @throws javax.sound.midi.MidiUnavailableException if the device cannot be resolved because of a resource
   *                                                    restriction.
   * @throws IllegalArgumentException                   if `info` does not describe a device of this environment.
   */
  def deviceOf(info: MidiDevice.Info): MidiDevice

  /**
   * Subscribes to changes of the MIDI environment, such as a device being plugged in or unplugged.
   *
   * @param listener called on every change.
   * @return a subscription; closing it unsubscribes the listener.
   */
  def onEnvironmentChanged(listener: () => Unit): AutoCloseable
}

/**
 * The production [[JavaMidiEnvironment]]: delegates device discovery and change notifications to CoreMIDI4J, which
 * replaces the default Java Sound MIDI device provider to make Java MIDI work on macOS (this should also work on
 * Windows), and device resolution to `MidiSystem`. It is the only production code that calls these statics.
 */
object CoreMidi4JEnvironment extends JavaMidiEnvironment {

  override def deviceInfos: Seq[MidiDevice.Info] = ArraySeq.unsafeWrapArray(CoreMidiDeviceProvider.getMidiDeviceInfo)

  override def deviceOf(info: MidiDevice.Info): MidiDevice = MidiSystem.getMidiDevice(info)

  override def onEnvironmentChanged(listener: () => Unit): AutoCloseable = {
    // CoreMIDI4J matches listeners by identity on removal, so the same adapter instance must be used for both calls.
    val notification: CoreMidiNotification = () => listener()
    CoreMidiDeviceProvider.addNotificationListener(notification)
    () => CoreMidiDeviceProvider.removeNotificationListener(notification)
  }
}
