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

case class TrackSpec(id: TrackSpec.Id,
                     name: String,
                     input: Option[TrackInput],
                     tuningChanger: Option[TuningChanger],
                     tuner: Option[Tuner],
                     output: TrackOutput,
                     muted: Boolean = false) {
  require(id != null && id.nonEmpty, "id must not be null or empty!")
  require(name != null && name.nonEmpty, "name must not be null or empty!")
}

object TrackSpec {
  type Id = String
}

case class TrackSpecs(tracks: Seq[TrackSpec] = Seq.empty) {

  def apply(index: Int): TrackSpec = tracks(index)

  def get(index: Int): Option[TrackSpec] = tracks.lift(index)

  def update(index: Int, trackSpec: TrackSpec): TrackSpecs = {
    require(index >= 0 && index < tracks.size, s"Index $index is out of bounds!")

    copy(tracks = tracks.updated(index, trackSpec))
  }
}
