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

import javax.annotation.concurrent.ThreadSafe

@ThreadSafe
case class TrackSpec(id: TrackSpec.Id,
                     name: String,
                     input: Option[TrackInput],
                     tuningChangers: Seq[TuningChanger],
                     tuner: Option[Tuner],
                     output: Option[TrackOutput],
                     muted: Boolean = false,
                     initMidiConfig: InitMidiConfig = InitMidiConfig()) {
  require(id != null && id.nonEmpty, "id must not be null or empty!")
  require(name != null && name.nonEmpty, "name must not be null or empty!")
}

object TrackSpec {
  type Id = String
}

case class InitMidiConfig(ccParams: Map[Int, Int] = Map.empty) {
  require(ccParams.keySet.forall(ccParam => ccParam >= 0 && ccParam <= 127),
    "CC parameters must be in the range [0, 127]!")
  require(ccParams.values.forall(ccParam => ccParam >= 0 && ccParam <= 127),
    "CC parameter values must be in the range [0, 127]!")
}

@ThreadSafe
case class TrackSpecs(tracks: Seq[TrackSpec] = Seq.empty) {

  private val trackIndexById: Map[TrackSpec.Id, Int] = tracks.zipWithIndex.map {
    case (track, index) => track.id -> index
  }.toMap

  def apply(id: TrackSpec.Id): TrackSpec = tracks(trackIndexById(id))

  def apply(index: Int): TrackSpec = tracks(index)

  def get(id: TrackSpec.Id): Option[TrackSpec] = trackIndexById.get(id).map(tracks)

  def get(index: Int): Option[TrackSpec] = tracks.lift(index)

  /**
   * Retrieves the name of the track corresponding to the specified ID, substituting `"#"` with the track number
   * (`index + 1`). If the user wants to keep the `"#"` as it, they can escape it as `"\\#"`.
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

  def indexOf(id: TrackSpec.Id): Int = trackIndexById.getOrElse(id, -1)

  def contains(id: TrackSpec.Id): Boolean = trackIndexById.contains(id)

  def size: Int = tracks.size

  def isEmpty: Boolean = tracks.isEmpty

  def nonEmpty: Boolean = tracks.nonEmpty

  def ids: Seq[TrackSpec.Id] = tracks.map(_.id)

  def addBefore(track: TrackSpec, beforeId: Option[TrackSpec.Id]): TrackSpecs = {
    if (trackIndexById.contains(track.id)) {
      // Do nothing if a track with the same ID already exists
      this
    } else {
      val index = beforeId.flatMap(trackIndexById.get).getOrElse(tracks.size)
      copy(tracks = tracks.patch(index, Seq(track), 0))
    }
  }

  def update(track: TrackSpec): TrackSpecs = trackIndexById.get(track.id) match {
    case None => this
    case Some(index) => update(index, track)
  }

  def update(index: Int, track: TrackSpec): TrackSpecs = {
    require(index >= 0 && index < tracks.size, s"Index $index is out of bounds!")

    copy(tracks = tracks.updated(index, track))
  }

  def moveBefore(idToMove: TrackSpec.Id, beforeId: Option[TrackSpec.Id]): TrackSpecs = get(idToMove) match {
    case None => this
    case Some(track) => remove(idToMove).addBefore(track, beforeId)
  }

  def remove(id: TrackSpec.Id): TrackSpecs = trackIndexById.get(id) match {
    case None => this
    case Some(index) => copy(tracks = tracks.patch(index, Seq.empty, 1))
  }
}
