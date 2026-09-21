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
 * Base class for all MIDI events emitted by a [[MidiManager]] implementation.
 *
 * A device event identifies its device by [[MidiDeviceId]] and, except for
 * [[MidiDeviceFailedToBecomeAvailableEvent]], tells in its `direction` the use of the device it concerns — the same
 * value a caller passes to [[MidiManager]]'s methods to request that use, and so one of those the publishing
 * implementation accepts. An implementation that accepts only [[MidiDirection.Input]] and [[MidiDirection.Output]]
 * publishes only those, so a device that works in both directions is reported once for each of them, by two events
 * that differ in their `direction`.
 *
 * Each event reports one transition of one handle, and a failure event replaces its success event.
 */
abstract sealed class MidiEvent extends BusinessyncEvent

/**
 * Event that indicates a change in the MIDI environment.
 *
 * This event is emitted when there are updates in the configuration of MIDI devices,
 * such as devices being added, removed, or reconfigured.
 *
 * An implementation publishes it when the platform reports such a change and then rescans the environment (the
 * Java Sound implementation reacts to a CoreMIDI4J notification).
 */
case object MidiEnvironmentChangedEvent extends MidiEvent

/**
 * Event emitted when a MIDI device becomes available (added) to the system.
 *
 * Note that this event does not tell that the device was also opened by the application.
 *
 * @param deviceId  Unique identifier of the newly added MIDI device.
 * @param direction The use of the device by the handle that made the transition (see [[MidiEvent]]).
 * @see [[MidiDeviceFailedToBecomeAvailableEvent]], the failing pair of this event.
 */
case class MidiDeviceAvailableEvent(deviceId: MidiDeviceId, direction: MidiDirection) extends MidiEvent

/**
 * Event emitted when a MIDI device fails to become available.
 *
 * It carries no direction: it is published when resolving the device fails, before its direction is known.
 *
 * @param deviceId Unique identifier of the MIDI device that failed to become available.
 * @param cause    Exception that describes the cause of the failure.
 * @see [[MidiDeviceAvailableEvent]], the successful pair of this event.
 */
case class MidiDeviceFailedToBecomeAvailableEvent(deviceId: MidiDeviceId, cause: Exception) extends MidiEvent

/**
 * Event emitted when an existing MIDI device becomes unavailable (removed) from the system.
 *
 * @param deviceId  Identifier of the MIDI device that was removed.
 * @param direction The use of the device by the handle that made the transition (see [[MidiEvent]]).
 * @see [[MidiDeviceFailedToBecomeUnavailableEvent]], the failing pair of this event.
 */
case class MidiDeviceUnavailableEvent(deviceId: MidiDeviceId, direction: MidiDirection) extends MidiEvent

/**
 * Event emitted when a MIDI device fails to become unavailable.
 *
 * @param deviceId  Unique identifier of the MIDI device that failed to become unavailable.
 * @param direction The use of the device by the handle that made the transition (see [[MidiEvent]]).
 * @param cause     Exception that caused the failure to make the device unavailable.
 * @see [[MidiDeviceUnavailableEvent]], the successful pair of this event.
 */
case class MidiDeviceFailedToBecomeUnavailableEvent(deviceId: MidiDeviceId, direction: MidiDirection,
                                                    cause: Exception) extends MidiEvent

/**
 * Event emitted when a MIDI device is opened.
 *
 * @param deviceId  The unique identifier of the opened MIDI device.
 * @param direction The use of the device by the handle that made the transition (see [[MidiEvent]]).
 * @see [[MidiDeviceFailedToOpenEvent]], the failing pair of this event.
 */
case class MidiDeviceOpenedEvent(deviceId: MidiDeviceId, direction: MidiDirection) extends MidiEvent

/**
 * Event triggered when a MIDI device fails to open.
 *
 * @param deviceId  Unique identifier of the MIDI device that failed to open.
 * @param direction The use of the device by the handle that made the transition (see [[MidiEvent]]).
 * @param cause     Exception representing the reason for the failure.
 * @see [[MidiDeviceOpenedEvent]], the successful pair of this event.
 */
case class MidiDeviceFailedToOpenEvent(deviceId: MidiDeviceId, direction: MidiDirection,
                                       cause: Exception) extends MidiEvent

/**
 * Event emitted when a MIDI device is closed.
 *
 * @param deviceId  Identifier of the MIDI device that has been closed.
 * @param direction The use of the device by the handle that made the transition (see [[MidiEvent]]).
 * @see [[MidiDeviceFailedToCloseEvent]], the failing pair of this event.
 */
case class MidiDeviceClosedEvent(deviceId: MidiDeviceId, direction: MidiDirection) extends MidiEvent

/**
 * Event emitted when a MIDI device fails to close.
 *
 * @param deviceId  The unique identifier of the MIDI device that failed to close.
 * @param direction The use of the device by the handle that made the transition (see [[MidiEvent]]).
 * @param cause     The exception that caused the failure.
 * @see [[MidiDeviceClosedEvent]], the successful pair of this event.
 */
case class MidiDeviceFailedToCloseEvent(deviceId: MidiDeviceId, direction: MidiDirection,
                                        cause: Exception) extends MidiEvent
