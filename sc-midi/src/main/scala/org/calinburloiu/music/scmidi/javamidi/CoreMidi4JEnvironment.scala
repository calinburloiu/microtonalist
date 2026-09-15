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
 * The production [[JavaMidiEnvironment]]: delegates device discovery and change notifications to CoreMIDI4J, which
 * replaces the default Java Sound MIDI device provider to make Java MIDI work on macOS (this should also work on
 * Windows), and device resolution to `MidiSystem`. It is the only production code that calls these statics.
 *
 * It is excluded from coverage in `build.sbt`: it only delegates to those statics, which need MIDI hardware, and
 * [[JavaMidiEnvironment]] exists precisely so that the logic above it can be tested over a fake instead.
 */
object CoreMidi4JEnvironment extends JavaMidiEnvironment {

  override def deviceInfos: Seq[MidiDevice.Info] = ArraySeq.unsafeWrapArray(CoreMidiDeviceProvider.getMidiDeviceInfo)

  override def deviceOf(info: MidiDevice.Info): MidiDevice = MidiSystem.getMidiDevice(info)

  override def subscribeToEnvironmentChanged(handler: () => Unit): AutoCloseable = {
    // CoreMIDI4J matches listeners by identity on removal, so the same adapter instance must be used for both calls.
    val notification: CoreMidiNotification = () => handler()
    CoreMidiDeviceProvider.addNotificationListener(notification)
    () => CoreMidiDeviceProvider.removeNotificationListener(notification)
  }
}
