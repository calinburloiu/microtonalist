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

/**
 * Default track specification repository implementation that accesses tracks from other repositories based on URI.
 *
 * Other repositories are accessed based on URI in the following way:
 *
 *   - Relative URIs and those with `file` scheme use [[FileTrackRepo]]. [[TrackRepo]]s don't have a base URI, that's
 *     why it was chosen to interpret relative URIs as files. Callers are advised to always resolve relative URI
 *     based on the base URI before making calls to the repo. In this way relative URI can be based on any scheme.
 *   - URIs with `http`/`https` scheme use [[HttpTrackRepo]].
 *   - URIs with `microtonalist` scheme use [[LibraryTrackRepo]].
 *
 * @param fileTrackRepo    a [[FileTrackRepo]] instance
 * @param httpTrackRepo    an [[HttpTrackRepo]] instance
 * @param libraryTrackRepo a [[LibraryTrackRepo]] instance
 */
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
