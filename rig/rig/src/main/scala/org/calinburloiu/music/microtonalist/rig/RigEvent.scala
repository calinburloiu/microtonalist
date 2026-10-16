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

package org.calinburloiu.music.microtonalist.rig

import org.calinburloiu.businessync.BusinessyncEvent

import java.net.URI

/**
 * Base class for all events published by [[RigSession]].
 */
sealed abstract class RigEvent extends BusinessyncEvent

/**
 * Event informing that tracks have been opened within a rig session.
 *
 * @param uri The URI from which the tracks were read.
 * @param tracks The specifications of the opened tracks.
 */
case class RigOpenedEvent(uri: URI, tracks: RigSpec) extends RigEvent

/**
 * Event informing that the rig session was closed and its tracks have been cleared.
 *
 * @param uri Optional URI if the tracks from the closed session were read or associated with one.
 */
case class RigClosedEvent(uri: Option[URI]) extends RigEvent

/**
 * Event informing that all tracks in a rig session have been replaced with others.
 *
 * @param tracks The collection of track specifications that replace the previous tracks.
 */
case class RigReplacedEvent(tracks: RigSpec) extends RigEvent

/**
 * Event informing the addition of a new track to a rig session.
 *
 * @param track The specification of the track that has been added.
 * @param beforeId Optional ID of the track before which the new track has been inserted. `None` means that the track
 *   was appended to the list of tracks.
 */
case class TrackAddedEvent(track: TrackSpec, beforeId: Option[TrackSpec.Id]) extends RigEvent

/**
 * Represents an event indicating that a track within a session has been updated.
 *
 * @param track The updated track specification that replaced the one with the same ID.
 */
case class TrackUpdatedEvent(track: TrackSpec) extends RigEvent

/**
 * Represents an event triggered when a track is moved within a session to a different position.
 *
 * @param movedId The unique identifier of the track being moved.
 * @param beforeId The unique identifier of the track before which the moved track is placed, if applicable. `None`
 *   indicates that the track was moved to the last position.
 */
case class TrackMovedEvent(movedId: TrackSpec.Id, beforeId: Option[TrackSpec.Id]) extends RigEvent

/**
 * Event indicating that a track has been removed.
 *
 * @param id Identifier of the track that was removed.
 */
case class TrackRemovedEvent(id: TrackSpec.Id) extends RigEvent
