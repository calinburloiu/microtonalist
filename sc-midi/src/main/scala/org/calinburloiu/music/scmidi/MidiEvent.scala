/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.calinburloiu.businessync.BusinessyncEvent

/**
 * Base class for all MIDI events emitted by [[MidiManager]].
 */
abstract sealed class MidiEvent extends BusinessyncEvent

/**
 * Event that indicates a change in the MIDI environment.
 *
 * This event is emitted when there are updates in the configuration of MIDI devices,
 * such as devices being added, removed, or reconfigured.
 *
 * This event is associated with a [[uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification]].
 */
case object MidiEnvironmentChangedEvent extends MidiEvent

/**
 * Event representing the addition of a new MIDI device.
 *
 * Note that this event does not tell that the device was also connected in the application.
 *
 * @param deviceId Unique identifier of the newly added MIDI device.
 */
case class MidiDeviceAddedEvent(deviceId: MidiDeviceId) extends MidiEvent

/**
 * Represents an event indicating that a MIDI device has been removed.
 *
 * @param deviceId Identifier of the MIDI device that was removed.
 */
case class MidiDeviceRemovedEvent(deviceId: MidiDeviceId) extends MidiEvent

/**
 * Event representing the connection of a MIDI device.
 *
 * @param deviceId The unique identifier of the connected MIDI device.
 */
case class MidiDeviceConnectedEvent(deviceId: MidiDeviceId) extends MidiEvent

/**
 * Event emitted when a MIDI device is disconnected.
 *
 * @param deviceId Identifier of the MIDI device that has been disconnected.
 */
case class MidiDeviceDisconnectedEvent(deviceId: MidiDeviceId) extends MidiEvent
