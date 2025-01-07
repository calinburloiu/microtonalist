/*
 * Copyright 2021 Calin-Andrei Burloiu
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

import javax.sound.midi.MidiDevice

/**
 * Unique identifier of a device.
 *
 * Note that a physical device that works as both input and output will have a unique [[MidiDeviceId]] but
 * different instances for input and output for [[MidiDevice]] (or [[MidiDeviceHandle]]) and [[MidiDevice.Info]].
 *
 * @param name   Name of the MIDI device.
 * @param vendor The name of the company who supplies the device.
 */
case class MidiDeviceId(name: String,
                        vendor: String) {
  /**
   * The app does not use the Java MIDI implementation and instead uses CoreMidi4J, which causes all device names to
   * have a certain prefix. This method removes that prefix.
   *
   * Use this name instead of the actual name in user interfaces.
   *
   * @return a user-friendly name of the device.
   */
  def sanitizedName: String = {
    name.replaceFirst(s"^${MidiDeviceId.CoreMidi4JDeviceNamePrefix}", "").trim
  }

  override def toString: String = {
    val vendorSuffix = if (vendor.trim.nonEmpty) s" ($vendor)" else ""
    s""""$name"$vendorSuffix"""
  }
}

object MidiDeviceId {
  private val CoreMidi4JDeviceNamePrefix: String = "CoreMIDI4J - "

  def apply(midiDeviceInfo: MidiDevice.Info): MidiDeviceId =
    MidiDeviceId(midiDeviceInfo.getName, midiDeviceInfo.getVendor)
}
