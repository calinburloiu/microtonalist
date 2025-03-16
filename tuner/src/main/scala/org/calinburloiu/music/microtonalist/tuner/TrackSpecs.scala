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

import org.calinburloiu.music.microtonalist.tuner.TrackInitSpec.{Cc, CcValue}
import org.calinburloiu.music.scmidi.ScCcMidiMessage

import javax.sound.midi.MidiMessage

/**
 * Specification of a track used for performing a MIDI instrument with microtones used for specifying its MIDI and 
 * tuning configuration.
 *
 * @param id               Unique identifier of a track.
 * @param name             User defined name of a track. Character `"#"` will be substituted with the 1-based track
 *                         index. If the
 *                         user wants to keep the `"#"` as it is, they can escape it as `"\\#"`.
 * @param input            Plugin used to configure the track input.
 * @param tuningChangers   A sequence of [[TuningChanger]] plugins that decide whether the tuning should be changed or
 *                         not. The decision is of the first one that returns an effective [[TuningChange]], so the 
 *                         decision is taken by an OR operator. Note that if none decides to trigger a change, no change
 *                         will be performed.
 * @param tuner            An option for a [[Tuner]] plugin used to handle tuning operations and modify MIDI messages.
 * @param output           Plugin used to configure the track output.
 * @param muted            Tells whether the plugin is muted or not.
 * @param initMidiMessages Optional sequence of MIDI message to be initially passed through the track, as if they
 *                         come from the input, that can be used to initialize the MIDI pipeline of the track,
 *                         including [[TuningChanger]]s, the [[Tuner]] and the output.
 */
case class TrackSpec(id: TrackSpec.Id,
                     name: String,
                     input: Option[TrackInputSpec] = None,
                     tuningChangers: Seq[TuningChanger] = Seq.empty,
                     tuner: Option[Tuner] = None,
                     output: Option[TrackOutputSpec] = None,
                     muted: Boolean = false,
                     initSpec: Option[TrackInitSpec] = None) {
  require(id != null && id.nonEmpty, "id must not be null or empty!")
  require(name != null && name.nonEmpty, "name must not be null or empty!")

  def initMidiMessages: Seq[MidiMessage] = {
    // TODO #64 Get channel from input/output
    val channel = 0
    val ccMessages = for {
      spec <- initSpec.toSeq
      (cc, value) <- spec.cc
    } yield ScCcMidiMessage(channel, cc, value).javaMidiMessage

    // TODO #64 Also output program change and make sure bank select if before it
    ccMessages
  }
}

object TrackSpec {
  /**
   * Type of tracks unique identifiers.
   */
  type Id = String
}

case class TrackInitSpec(programChange: Option[Int] = None,
                         cc: Map[Cc, CcValue] = Map.empty) {
  require(programChange.forall(p => p >= 1 && p <= 128), "Program change must be between 1 and 128!")
  require(cc.forall { case (cc, value) => cc >= 0 && cc <= 127 && value >= 0 && value <= 127 },
    "CC values must be between 0 and 127!")
}

object TrackInitSpec {
  type Cc = Int
  type CcValue = Int
}

/**
 * Object encapsulating a list of [[TrackSpec]] instances that allows querying a basic list update operations. The items
 * are unique by their ID, and they are accessible in `O(1)` either by their 0-based index or by their ID.
 *
 * @param tracks The sequence of track items.
 */
case class TrackSpecs(tracks: Seq[TrackSpec] = Seq.empty) {

  private val trackIndexById: Map[TrackSpec.Id, Int] = tracks.zipWithIndex.map {
    case (track, index) => track.id -> index
  }.toMap

  /**
   * Returns the `TrackSpec` corresponding to the specified unique identifier.
   *
   * @param id The unique identifier of the desired `TrackSpec`.
   * @return The `TrackSpec` associated with the given ID.
   * @throws NoSuchElementException if a track with that ID does not exist.
   */
  def apply(id: TrackSpec.Id): TrackSpec = tracks(trackIndexById(id))

  /**
   * Retrieves the `TrackSpec` corresponding to the specified index.
   *
   * @param index The 0-based index of the desired `TrackSpec` in the collection.
   * @return The `TrackSpec` located at the given index.
   * @throws IndexOutOfBoundsException if a track with that index does not exist.
   */
  def apply(index: Int): TrackSpec = tracks(index)

  /**
   * Retrieves an optional `TrackSpec` corresponding to the specified unique identifier.
   *
   * @param id The unique identifier of the desired `TrackSpec`.
   * @return An `Option` containing the retrieved `TrackSpec` if found, or `None` if no matching identifier exists.
   */
  def get(id: TrackSpec.Id): Option[TrackSpec] = trackIndexById.get(id).map(tracks)

  /**
   * Retrieves an optional `TrackSpec` corresponding to the specified index.
   *
   * @param index The 0-based index of the desired `TrackSpec` in the collection.
   * @return An `Option` containing the `TrackSpec` at the given index if it exists, or `None` if the index is out of
   *         bounds.
   */
  def get(index: Int): Option[TrackSpec] = tracks.lift(index)

  /**
   * Retrieves the name of the track corresponding to the specified ID, substituting `"#"` with the track number
   * (`index + 1`). If the user wants to keep the `"#"` as it is, they can escape it as `"\\#"`.
   *
   * @param id The unique identifier of the track whose name is to be retrieved.
   * @return an option containing the track name with indexed placeholders replaced, or None if the ID does not exist.
   */
  def nameOf(id: TrackSpec.Id): Option[String] = {
    val index = indexOf(id)
    if (index >= 0) {
      Some(tracks(index).name
        .replace("\\#", "\u0000")
        .replace("#", (index + 1).toString)
        .replace("\u0000", "#"))
    } else {
      None
    }
  }

  /**
   * Finds the index of a track based on its unique identifier.
   *
   * @param id The unique identifier of the `TrackSpec` whose index is to be retrieved.
   * @return The 0-based index of the track if it exists in the collection, or `-1` if it does not exist.
   */
  def indexOf(id: TrackSpec.Id): Int = trackIndexById.getOrElse(id, -1)

  /**
   * Checks whether the collection contains a track with the specified unique identifier.
   *
   * @param id The unique identifier of the `TrackSpec` to check for.
   * @return `true` if a track with the specified identifier exists in the collection, otherwise `false`.
   */
  def contains(id: TrackSpec.Id): Boolean = trackIndexById.contains(id)

  /**
   * Returns the total number of tracks in the collection.
   *
   * @return The number of tracks in the collection.
   */
  def size: Int = tracks.size

  /**
   * Checks if the collection of tracks is empty.
   *
   * @return `true` if the collection contains no tracks, otherwise `false`.
   */
  def isEmpty: Boolean = tracks.isEmpty

  /**
   * Checks whether the collection of tracks is not empty.
   *
   * @return `true` if the collection contains one or more tracks, otherwise `false`.
   */
  def nonEmpty: Boolean = tracks.nonEmpty

  /**
   * Retrieves the unique identifiers of all tracks in the collection.
   *
   * @return A sequence of unique track identifiers.
   */
  def ids: Seq[TrackSpec.Id] = tracks.map(_.id)

  /**
   * Adds the specified track to the TrackSpecs collection before the track specified by `beforeId`.
   * If `beforeId` is not provided, or it doesn't exist, the track will be added to the end of the collection.
   * If a track with the same ID already exists in the collection, no changes are made.
   *
   * @param track    The `TrackSpec` to be added.
   * @param beforeId The optional ID of the track before which the specified track should be added.
   *                 If `None` or non-existent, the track is added at the end of the collection.
   * @return A new `TrackSpecs` object, or the same instance if nothing changed.
   */
  def addBefore(track: TrackSpec, beforeId: Option[TrackSpec.Id]): TrackSpecs = {
    if (trackIndexById.contains(track.id)) {
      // Do nothing if a track with the same ID already exists
      this
    } else {
      val index = beforeId.flatMap(trackIndexById.get).getOrElse(tracks.size)
      copy(tracks = tracks.patch(index, Seq(track), 0))
    }
  }

  /**
   * Updates the `TrackSpec` in the collection if a track with the same ID already exists.
   *
   * @param track The `TrackSpec` to update in the collection. Its `id` is used to find a matching track.
   * @return A new `TrackSpecs` object with the updated track, or the same instance if no matching track is found.
   */
  def update(track: TrackSpec): TrackSpecs = trackIndexById.get(track.id) match {
    case None => this
    case Some(index) => _update(index, track)
  }

  /**
   * Updates the `TrackSpec` at the specified index in the collection with the provided `TrackSpec`.
   *
   * @param index The 0-based index of the track to be updated. Must be within the bounds of the collection.
   * @param track The new `TrackSpec` object to replace the existing track at the specified index.
   * @return A new `TrackSpecs` object with the updated track at the specified index.
   * @throws IllegalArgumentException if the provided index is out of bounds.
   */
  def update(index: Int, track: TrackSpec): TrackSpecs = {
    require(index >= 0 && index < tracks.size, s"Index $index is out of bounds!")
    require(contains(track.id), s"Track with ID ${track.id} does not exist in the collection!")

    _update(index, track)
  }

  private def _update(index: Int, track: TrackSpec): TrackSpecs = copy(tracks = tracks.updated(index, track))

  /**
   * Moves a track with the specified ID to a position before another track identified by `beforeId`.
   * If the `idToMove` is not found, no changes are made. If `beforeId` is not provided or does not exist,
   * the track is moved to the end of the collection.
   *
   * @param idToMove The unique identifier of the track to be moved.
   * @param beforeId An optional unique identifier of the track before which the `idToMove` track should be positioned.
   *                 If `None` or non-existent, the track is moved to the end of the collection.
   * @return A new `TrackSpecs` object with the track moved to the desired position, or the same instance
   *         if the `idToMove` is not found.
   */
  def moveBefore(idToMove: TrackSpec.Id, beforeId: Option[TrackSpec.Id]): TrackSpecs = get(idToMove) match {
    case None => this
    case Some(track) => remove(idToMove).addBefore(track, beforeId)
  }

  /**
   * Removes the track with the specified unique identifier from the collection.
   * If no track with the given ID exists, the collection remains unchanged and is returned as is.
   *
   * @param id The unique identifier of the `TrackSpec` to be removed.
   * @return A new `TrackSpecs` object without the specified track, or the same instance if the track does not exist.
   */
  def remove(id: TrackSpec.Id): TrackSpecs = trackIndexById.get(id) match {
    case None => this
    case Some(index) => copy(tracks = tracks.patch(index, Seq.empty, 1))
  }
}
