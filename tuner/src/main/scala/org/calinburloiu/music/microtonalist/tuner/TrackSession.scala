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
import scala.collection.mutable

@NotThreadSafe
class TrackSession(trackManager: TrackManager,
                   businessync: Businessync) extends OpenableSession {
  private var _uri: Option[URI] = None
  private var _tracks: TrackSpecs = TrackSpecs()

  private val trackIndexById: mutable.Map[TrackSpec.Id, Int] = mutable.Map()

  def tracks: TrackSpecs = _tracks

  def tracks_=(newTrackSpecs: TrackSpecs): Unit = {
    _tracks = newTrackSpecs
  }

  override def open(uri: URI): Unit = {
    _uri = Some(uri)

    // TODO [#119] Read tracks files
    ???
  }

  override def close(): Unit = {
    _uri = None
  }

  override def isOpened: Boolean = _uri.isDefined

  override def uri: Option[URI] = _uri

  def getTrack(id: TrackSpec.Id): Option[TrackSpec] = trackIndexById.get(id).map(_tracks.apply)

  def getTrack(index: Int): Option[TrackSpec] = _tracks.get(index)

  def addTrack(trackSpec: TrackSpec): Unit = addTrackBefore(trackSpec, None)

  def addTrackBefore(trackSpec: TrackSpec, beforeId: TrackSpec.Id): Unit = addTrackBefore(trackSpec, Some(beforeId))

  def addTrackBefore(trackSpec: TrackSpec, beforeId: Option[TrackSpec.Id]): Unit = ???

  def updateTrack(trackSpec: TrackSpec): Unit = ???

  def moveTrackBefore(idToMove: TrackSpec.Id, beforeId: TrackSpec.Id): Unit = moveTrackBefore(idToMove, Some(beforeId))

  def moveTrackBefore(idToMove: TrackSpec.Id, beforeId: Option[TrackSpec.Id]): Unit = ???

  def moveTrackToEnd(idToMove: TrackSpec.Id): Unit = moveTrackBefore(idToMove, None)

  def removeTrack(id: TrackSpec.Id): Unit = ???

  def removeTrack(index: Int): Unit = ???
}
