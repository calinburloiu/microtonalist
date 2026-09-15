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
 * A device event identifies its device by [[MidiDeviceId]] and, except for [[MidiDeviceFailedToConnectEvent]], tells
 * in its `endpointType` the direction of the endpoint it concerns, always [[MidiEndpointType.Input]] or
 * [[MidiEndpointType.Output]]. A [[MidiManager]] keeps the two directions apart, because the platform may expose one
 * physical device once per direction (see [[MidiManager]]), so a device that works in both directions is reported
 * once for each of them, by two events that differ in their `endpointType`.
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
 * Event emitted when a new MIDI device is connected (added) to the system.
 *
 * Note that this event does not tell that the device was also opened by the application.
 *
 * @param deviceId     Unique identifier of the newly added MIDI device.
 * @param endpointType The direction of the endpoint whose handle made the transition: [[MidiEndpointType.Input]] or
 *                      [[MidiEndpointType.Output]], never another value.
 * @see [[MidiDeviceFailedToConnectEvent]], the failing pair of this event.
 */
case class MidiDeviceConnectedEvent(deviceId: MidiDeviceId, endpointType: MidiEndpointType) extends MidiEvent

/**
 * Event emitted when a MIDI device fails to connect.
 *
 * It carries no direction: it is published when resolving the device fails, before its direction is known.
 *
 * @param deviceId Unique identifier of the MIDI device that failed to connect.
 * @param cause    Exception that describes the cause of the failure.
 * @see [[MidiDeviceConnectedEvent]], the successful pair of this event.
 */
case class MidiDeviceFailedToConnectEvent(deviceId: MidiDeviceId, cause: Exception) extends MidiEvent

/**
 * Event emitted when an existing MIDI device is disconnected (removed) from the system.
 *
 * @param deviceId     Identifier of the MIDI device that was removed.
 * @param endpointType The direction of the endpoint whose handle made the transition: [[MidiEndpointType.Input]] or
 *                      [[MidiEndpointType.Output]], never another value.
 * @see [[MidiDeviceFailedToDisconnectEvent]], the failing pair of this event.
 */
case class MidiDeviceDisconnectedEvent(deviceId: MidiDeviceId, endpointType: MidiEndpointType) extends MidiEvent

/**
 * Event emitted when a MIDI device fails to disconnect.
 *
 * @param deviceId     Unique identifier of the MIDI device that failed to disconnect.
 * @param endpointType The direction of the endpoint whose handle made the transition: [[MidiEndpointType.Input]] or
 *                      [[MidiEndpointType.Output]], never another value.
 * @param cause        Exception that caused the failure to disconnect the device.
 * @see [[MidiDeviceDisconnectedEvent]], the successful pair of this event.
 */
case class MidiDeviceFailedToDisconnectEvent(deviceId: MidiDeviceId, endpointType: MidiEndpointType,
                                             cause: Exception) extends MidiEvent

/**
 * Event emitted when a MIDI device is opened.
 *
 * @param deviceId     The unique identifier of the opened MIDI device.
 * @param endpointType The direction of the endpoint whose handle made the transition: [[MidiEndpointType.Input]] or
 *                      [[MidiEndpointType.Output]], never another value.
 * @see [[MidiDeviceFailedToOpenEvent]], the failing pair of this event.
 */
case class MidiDeviceOpenedEvent(deviceId: MidiDeviceId, endpointType: MidiEndpointType) extends MidiEvent

/**
 * Event triggered when a MIDI device fails to open.
 *
 * @param deviceId     Unique identifier of the MIDI device that failed to open.
 * @param endpointType The direction of the endpoint whose handle made the transition: [[MidiEndpointType.Input]] or
 *                      [[MidiEndpointType.Output]], never another value.
 * @param cause        Exception representing the reason for the failure.
 * @see [[MidiDeviceOpenedEvent]], the successful pair of this event.
 */
case class MidiDeviceFailedToOpenEvent(deviceId: MidiDeviceId, endpointType: MidiEndpointType,
                                       cause: Exception) extends MidiEvent

/**
 * Event emitted when a MIDI device is closed.
 *
 * @param deviceId     Identifier of the MIDI device that has been closed.
 * @param endpointType The direction of the endpoint whose handle made the transition: [[MidiEndpointType.Input]] or
 *                      [[MidiEndpointType.Output]], never another value.
 * @see [[MidiDeviceFailedToCloseEvent]], the failing pair of this event.
 */
case class MidiDeviceClosedEvent(deviceId: MidiDeviceId, endpointType: MidiEndpointType) extends MidiEvent

/**
 * Event emitted when a MIDI device fails to close.
 *
 * @param deviceId     The unique identifier of the MIDI device that failed to close.
 * @param endpointType The direction of the endpoint whose handle made the transition: [[MidiEndpointType.Input]] or
 *                      [[MidiEndpointType.Output]], never another value.
 * @param cause        The exception that caused the failure.
 * @see [[MidiDeviceClosedEvent]], the successful pair of this event.
 */
case class MidiDeviceFailedToCloseEvent(deviceId: MidiDeviceId, endpointType: MidiEndpointType,
                                        cause: Exception) extends MidiEvent
