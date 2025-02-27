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

import java.net.URI
import scala.concurrent.Future

trait TrackRepo {

  def readTracks(uri: URI): TrackSpecs

  def readTracksAsync(uri: URI): Future[TrackSpecs]

  def writeTracks(trackSpecs: TrackSpecs, uri: URI): Unit

  def writeTracksAsync(trackSpecs: TrackSpecs, uri: URI): Future[Unit]
}

class TrackRepoException(message: String, cause: Throwable) extends RuntimeException(message, cause)

class TracksNotFoundException(uri: URI, cause: Throwable = null)
  extends TrackRepoException(s"Track with $uri was not found!", cause)
