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

package org.calinburloiu.music.microtonalist.format

import org.calinburloiu.music.microtonalist.tuner.{BadTracksRequestException, TrackRepo, TrackSpecs}

import java.net.URI
import scala.concurrent.Future

class DefaultTrackRepo(fileTrackRepo: Option[FileTrackRepo],
                       httpTrackRepo: Option[HttpTrackRepo],
                       libraryTrackRepo: Option[LibraryTrackRepo]) extends TrackRepo {
  private val trackRepoSelector: RepoSelector[TrackRepo] = new DefaultRepoSelector(
    fileTrackRepo, httpTrackRepo, libraryTrackRepo,
    uri => new BadTracksRequestException(uri, Some(s"Unsupported URI scheme: ${uri.getScheme}")))

  override def readTracks(uri: URI): TrackSpecs = trackRepoSelector.selectRepoOrThrow(uri).readTracks(uri)

  override def readTracksAsync(uri: URI): Future[TrackSpecs] = trackRepoSelector.selectRepoOrThrow(uri)
    .readTracksAsync(uri)

  override def writeTracks(trackSpecs: TrackSpecs, uri: URI): Unit = trackRepoSelector.selectRepoOrThrow(uri)
    .writeTracks(trackSpecs, uri)

  override def writeTracksAsync(trackSpecs: TrackSpecs, uri: URI): Future[Unit] =
    trackRepoSelector.selectRepoOrThrow(uri).writeTracksAsync(trackSpecs, uri)
}
