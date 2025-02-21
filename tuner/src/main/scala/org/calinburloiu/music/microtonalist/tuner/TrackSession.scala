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

@NotThreadSafe
class TrackSession(trackManager: TrackManager,
                   businessync: Businessync) extends OpenableSession {
  private var _uri: Option[URI] = None
  private var _tracks: TrackSpecs = TrackSpecs()

  def tracks: TrackSpecs = _tracks

  def tracks_=(newTrackSpecs: TrackSpecs): Unit = {
    _tracks = newTrackSpecs

    businessync.publish(TracksUpdatedEvent(newTrackSpecs))
  }

  override def open(uri: URI): Unit = {
    _uri = Some(uri)

    // TODO #119 Read tracks files
    ???

    businessync.publish(TracksOpenedEvent(uri, tracks))
  }

  override def close(): Unit = {
    _uri = None

    businessync.publish(TracksClosedEvent(uri.get))
  }

  override def isOpened: Boolean = _uri.isDefined

  override def uri: Option[URI] = _uri

  def indexOf(id: TrackSpec.Id): Option[Int] = _tracks.indexOf(id)

  def getTrack(id: TrackSpec.Id): Option[TrackSpec] = _tracks.get(id)

  def getTrack(index: Int): Option[TrackSpec] = _tracks.get(index)

  def addTrack(track: TrackSpec): Unit = addTrackBefore(track, None)

  def addTrackBefore(track: TrackSpec, beforeId: TrackSpec.Id): Unit = addTrackBefore(track, Some(beforeId))

  def addTrackBefore(track: TrackSpec, beforeId: Option[TrackSpec.Id]): Unit = {
    _tracks = _tracks.addBefore(track, beforeId)

    businessync.publish(TrackAddedEvent(track, beforeId))
  }

  def updateTrack(track: TrackSpec): Unit = {
    _tracks = _tracks.update(track)

    businessync.publish(TrackUpdatedEvent(track))
  }

  def moveTrackBefore(idToMove: TrackSpec.Id, beforeId: TrackSpec.Id): Unit = moveTrackBefore(idToMove, Some(beforeId))

  def moveTrackBefore(idToMove: TrackSpec.Id, beforeId: Option[TrackSpec.Id]): Unit = {
    _tracks = _tracks.moveBefore(idToMove, beforeId)

    businessync.publish(TrackMovedEvent(idToMove, beforeId))
  }

  def moveTrackToEnd(idToMove: TrackSpec.Id): Unit = moveTrackBefore(idToMove, None)

  def removeTrack(id: TrackSpec.Id): Unit = {
    _tracks = _tracks.remove(id)

    businessync.publish(TrackRemovedEvent(id))
  }
}
