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

// TODO #88 Only use name and vendor. Remove version.
case class MidiDeviceId(name: String,
                        vendor: String,
                        version: String) {
  def sanitizedName: String = name.replaceFirst("^CoreMIDI4J - ", "").trim

  override def toString: String = {
    val vendorSuffix = if (vendor.trim.nonEmpty) s" ($vendor)" else ""
    sanitizedName + vendorSuffix
  }
}

object MidiDeviceId {
  def apply(midiDeviceInfo: MidiDevice.Info): MidiDeviceId =
    MidiDeviceId(midiDeviceInfo.getName, midiDeviceInfo.getVendor, midiDeviceInfo.getVersion)
}
