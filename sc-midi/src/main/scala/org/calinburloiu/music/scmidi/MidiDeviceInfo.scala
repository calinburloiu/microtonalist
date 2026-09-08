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

package org.calinburloiu.music.scmidi

/**
 * Information about a MIDI device, as reported by the platform.
 *
 * The [[id]] is derived from the name and vendor, and the directions the device can be used in derive from its
 * connection limits: a device that can open at least one transmitter is an input, one that can open at least one
 * receiver is an output. A physical device that works as both is listed by a [[MidiManager]] once per direction,
 * with the same [[id]].
 *
 * @param name            Name of the device.
 * @param vendor          Name of the company that supplies the device.
 * @param description     Description of the device.
 * @param version         Version of the device.
 * @param maxTransmitters How many transmitters the device can open, that is, how many consumers can subscribe to
 *                        the messages it sends.
 * @param maxReceivers    How many receivers the device can open, that is, how many producers can send messages to
 *                        it.
 */
case class MidiDeviceInfo(name: String,
                          vendor: String,
                          description: String,
                          version: String,
                          maxTransmitters: MidiConnectionLimit,
                          maxReceivers: MidiConnectionLimit) {

  /** Unique identifier of the device, derived from its name and vendor. */
  val id: MidiDeviceId = MidiDeviceId(name, vendor)

  /** Whether the device can be used as an input, that is, whether it can open at least one transmitter. */
  def isInputDevice: Boolean = maxTransmitters.allowsConnections

  /** Whether the device can be used as an output, that is, whether it can open at least one receiver. */
  def isOutputDevice: Boolean = maxReceivers.allowsConnections

  /** The directions in which the device can be used. */
  def endpointType: MidiEndpointType = MidiEndpointType(isInputDevice, isOutputDevice)
}
