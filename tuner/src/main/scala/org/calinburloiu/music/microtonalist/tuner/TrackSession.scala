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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.OpenableSession

import java.net.URI
import javax.annotation.concurrent.NotThreadSafe

/**
 * Manages a sequence of tracks by providing loading/unloading (I/O) and editing operations.
 *
 * This class should only be accessed from the business thread.
 *
 * @param trackManager Manages the MIDI configuration and handling of tracks.
 * @param businessync  An instance of `Businessync` responsible for handling event publication.
 */
@NotThreadSafe
class TrackSession(trackManager: TrackManager,
                   businessync: Businessync) extends OpenableSession {
  private var _uri: Option[URI] = None
  private var _tracks: TrackSpecs = TrackSpecs.Default

  /**
   * Opens a session with the tracks loaded from the given URI.
   *
   * Marks the session as opened and publishes the [[TracksOpenedEvent]] containing the resource information.
   *
   * @param uri The URI where the tracks are loaded from.
   * @throws java.io.IOException if the tracks could not be loaded from the given URI.
   */
  override def open(uri: URI): Unit = {
    _uri = Some(uri)

    // TODO #134 Read tracks files

    businessync.publish(TracksOpenedEvent(uri, tracks))
  }

  /**
   * Closes the current track session and clears the list of tracks.
   *
   * Publishes a [[TracksClosedEvent]] containing the URI of the session
   * that was previously active, if any. Note that the operation is idempotent, if the session was already closed no
   * URI will be passed to the event.
   */
  override def close(): Unit = {
    val uriBefore = _uri
    _uri = None

    businessync.publish(TracksClosedEvent(uriBefore))
  }

  /**
   * Checks whether the current track session is open.
   *
   * @return True if the session is open, false otherwise.
   */
  override def isOpened: Boolean = _uri.isDefined

  /**
   * Retrieves the URI associated with the current track session, if any.
   *
   * @return An optional URI representing the resource of the track session.
   */
  override def uri: Option[URI] = _uri

  /**
   * Retrieves the current specification for the track list associated with the session.
   *
   * @return The track specifications.
   */
  def tracks: TrackSpecs = _tracks

  /**
   * Updates the current track specifications and performs their MIDI configuration.
   *
   * Publishes a [[TracksReplacedEvent]] with track specifications.
   *
   * @param newTracks The new track specifications to be assigned.
   */
  def tracks_=(newTracks: TrackSpecs): Unit = {
    _tracks = newTracks

    trackManager.replaceAllTracks(newTracks)

    businessync.publish(TracksReplacedEvent(newTracks))
  }

  /**
   * Finds the index of a track based on its unique identifier.
   *
   * @param id The unique identifier of the track whose index is to be determined.
   * @return The 0-based index of the track if it exists, or -1 if it does not exist.
   */
  def indexOf(id: TrackSpec.Id): Int = _tracks.indexOf(id)

  /**
   * Retrieves the name of the track corresponding to the specified ID, substituting `"#"` with the track number
   * (`index + 1`). If the user wants to keep the `"#"` as it is, they can escape it as `"\\#"`.
   *
   * @param id The unique identifier of the track whose name is to be retrieved.
   * @return An option containing the track name with indexed placeholders replaced, or None if the ID does not exist.
   */
  def nameOf(id: TrackSpec.Id): Option[String] = _tracks.nameOf(id)

  /**
   * Checks whether the collection of tracks contains a track with the specified unique identifier.
   *
   * @param id The unique identifier of the track to check for.
   * @return True if a track with the specified identifier exists in the collection, otherwise false.
   */
  def contains(id: TrackSpec.Id): Boolean = _tracks.contains(id)

  /**
   * Retrieves the total number of tracks currently in the session.
   *
   * @return The number of tracks in the session.
   */
  def trackCount: Int = _tracks.size

  /**
   * Retrieves the track specification associated with the given unique identifier.
   *
   * @param id The unique identifier of the track to retrieve.
   * @return An option containing the track specification if found, or None otherwise.
   */
  def getTrack(id: TrackSpec.Id): Option[TrackSpec] = _tracks.get(id)

  /**
   * Retrieves the track specification at the given index in the track collection.
   *
   * @param index The 0-based index of the track to retrieve.
   * @return An option containing the track specification if the index is valid, or None otherwise.
   */
  def getTrack(index: Int): Option[TrackSpec] = _tracks.get(index)

  /**
   * Appends a new track to the session.
   *
   * If a track with the same ID already exists, no changes are made.
   *
   * A [[TrackAddedEvent]] will be published to notify subscribers if the track is successfully added.
   *
   * @param track The track specification to be added.
   */
  def addTrack(track: TrackSpec): Unit = addTrackBefore(track, None)

  /**
   * Adds a new track to the session before the specified track ID.
   *
   * If the specified `beforeId` does not exist, the track will be added at the end of the session.
   * If a track with the same ID already exists, no changes are made.
   *
   * A [[TrackAddedEvent]] will be published to notify subscribers if the track is successfully added.
   *
   * @param track    The track specification to be added.
   * @param beforeId The ID of the track before which the specified track should be added.
   */
  def addTrackBefore(track: TrackSpec, beforeId: TrackSpec.Id): Unit = addTrackBefore(track, Some(beforeId))

  /**
   * Adds a new track to the session before the specified track ID, if provided.
   *
   * If the specified `beforeId` is undefined or  does not exist, the track will be added at the end of the session.
   * If a track with the same ID already exists, no changes are made.
   *
   * A [[TrackAddedEvent]] will be published to notify subscribers if the track is successfully added.
   *
   * @param track    The track specification to be added.
   * @param beforeId The optional ID of the track before which the specified track should be added. `None` is
   *                 equivalent with appending.
   */
  def addTrackBefore(track: TrackSpec, beforeId: Option[TrackSpec.Id]): Unit = {
    val countBefore = _tracks.size
    _tracks = _tracks.addBefore(track, beforeId)

    if (_tracks.size > countBefore) {
      val actualBeforeId = if (beforeId.exists(_tracks.contains)) beforeId else None
      businessync.publish(TrackAddedEvent(track, actualBeforeId))
    }
  }

  /**
   * Updates an existing track in the session with the provided track specification.
   *
   * If the given `id` does not exist, nothing happens.
   *
   * If the track is successfully updated, a [[TrackUpdatedEvent]] is published containing the updated track
   * information.
   *
   * @param track The track specification to be updated. Its `id` is used to find and update
   *              the corresponding track in the session.
   */
  def updateTrack(track: TrackSpec): Unit = {
    val trackBefore = _tracks.get(track.id)
    _tracks = _tracks.update(track)

    if (trackBefore.exists(_ ne track)) {
      businessync.publish(TrackUpdatedEvent(track))
    }
  }

  /**
   * Moves the track identified by `idToMove` to the position before the track identified by `beforeId`.
   *
   * If `beforeId` doesn't exist, the track will be moved to the end of the collection.
   * Nothing happens if `idToMove` does not exist.
   *
   * If the track is successfully moved a [[TrackMovedEvent]] is published.
   *
   * @param idToMove The unique identifier of the track to be moved.
   * @param beforeId The unique identifier of the track before which the `idToMove` track should be positioned.
   */
  def moveTrackBefore(idToMove: TrackSpec.Id, beforeId: TrackSpec.Id): Unit = moveTrackBefore(idToMove, Some(beforeId))

  /**
   * Moves the track identified by `idToMove` to the position before the track identified by `beforeId`.
   *
   * If `beforeId` is `None`, or it doesn't exist, the track will be moved to the end of the collection.
   * Nothing happens if `idToMove` does not exist.
   *
   * If the track is successfully moved a [[TrackMovedEvent]] is published.
   *
   * @param idToMove The unique identifier of the track to be moved.
   * @param beforeId An optional unique identifier of the track before which the `idToMove` track should be
   *                 positioned. If `None` or non-existent, the track is moved to the end of the
   *                 collection.
   */
  def moveTrackBefore(idToMove: TrackSpec.Id, beforeId: Option[TrackSpec.Id]): Unit = {
    val indexBefore = _tracks.indexOf(idToMove)
    _tracks = _tracks.moveBefore(idToMove, beforeId)

    if (indexBefore != _tracks.indexOf(idToMove)) {
      val actualBeforeId = if (beforeId.exists(_tracks.contains)) beforeId else None
      businessync.publish(TrackMovedEvent(idToMove, actualBeforeId))
    }
  }

  /**
   * Moves the track identified by `idToMove` to the end of the collection.
   *
   * Nothing happens if `idToMove` does not exist.
   *
   * If the track is successfully moved a [[TrackMovedEvent]] is published.
   *
   * @param idToMove The unique identifier of the track to be moved.
   */
  def moveTrackToEnd(idToMove: TrackSpec.Id): Unit = moveTrackBefore(idToMove, None)

  /**
   * Removes a track from the session by its unique identifier.
   *
   * If the given `id` does not exist, nothing happens.
   *
   * If the track is successfully removed, a [[TrackRemovedEvent]] is published.
   *
   * @param id The unique identifier of the track to be removed.
   */
  def removeTrack(id: TrackSpec.Id): Unit = {
    val countBefore = _tracks.size
    _tracks = _tracks.remove(id)

    if (_tracks.size < countBefore) {
      businessync.publish(TrackRemovedEvent(id))
    }
  }
}
