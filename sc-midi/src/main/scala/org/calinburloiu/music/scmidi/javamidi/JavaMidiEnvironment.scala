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

import javax.sound.midi.MidiDevice

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
   * and from the resolved device's own info (`device.asMidiDeviceInfo`) on the success path. The two agree as long
   * as the device keeps its name and vendor between the listing and the resolution: `MidiSystem.getMidiDevice(info)`
   * returns the device `info` describes, but that device reports its current info, which CoreMIDI4J replaces when
   * the device is renamed. A fake implementation of this trait must keep the two consistent, or a device's id will
   * diverge between the failure event and the connected set.
   *
   * @throws javax.sound.midi.MidiUnavailableException if the device cannot be resolved because of a resource
   *                                                    restriction.
   * @throws IllegalArgumentException                   if `info` does not describe a device of this environment.
   */
  def deviceOf(info: MidiDevice.Info): MidiDevice

  /**
   * Subscribes to changes of the MIDI environment, such as a device being plugged in or unplugged.
   *
   * @param handler called on every change.
   * @return a subscription; closing it unsubscribes the handler.
   */
  def subscribeToEnvironmentChanged(handler: () => Unit): AutoCloseable
}
