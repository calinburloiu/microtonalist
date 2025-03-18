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

import org.calinburloiu.music.microtonalist.tuner.{TrackRepo, TrackSpecs}

import java.net.URI
import scala.concurrent.Future

/**
 * Special track specification repository implementation that accesses scales from user's configured private
 * Microtonalist Library.
 *
 * The user can configure a base URI for the library, `libraryBaseUri`, which can be a file system path or a remote HTTP
 * URL. Scales can then be imported by using a special URI with format `microtonalist:///<path-in-library>`, where
 * `<path-in-library>` is relative to the configured base URI.
 *
 * For example, if the user configures `file:///Users/john/Music/microtonalist/lib/` as the base URI for the library,
 * the Microtonalist Library URI `microtonalist:///tracks/default.mtlist.tracks` used as a tracks file URI will
 * actually point to `/Users/john/Music/microtonalist/lib/tracks/default.mtlist.tracks`.
 *
 * @param libraryBaseUri base URI for Microtonalist Library
 * @param fileTrackRepo  a [[FileTrackRepo]] instance
 * @param httpTrackRepo  an [[HttpTrackRepo]] instance
 */
class LibraryTrackRepo(libraryBaseUri: URI,
                       fileTrackRepo: FileTrackRepo,
                       httpTrackRepo: HttpTrackRepo) extends TrackRepo {

  private val repoSelector: RepoSelector[TrackRepo] = new DefaultRepoSelector(
    Some(fileTrackRepo), Some(httpTrackRepo), None)

  override def readTracks(uri: URI): TrackSpecs = {
    val resolvedUri = libraryBaseUri.resolve(uri)
    repoSelector.selectRepoOrThrow(resolvedUri).readTracks(resolvedUri)
  }

  override def readTracksAsync(uri: URI): Future[TrackSpecs] = {
    val resolvedUri = libraryBaseUri.resolve(uri)
    repoSelector.selectRepoOrThrow(resolvedUri).readTracksAsync(resolvedUri)
  }

  override def writeTracks(trackSpecs: TrackSpecs, uri: URI): Unit = {
    val resolvedUri = libraryBaseUri.resolve(uri)
    repoSelector.selectRepoOrThrow(resolvedUri).writeTracks(trackSpecs, resolvedUri)
  }

  override def writeTracksAsync(trackSpecs: TrackSpecs, uri: URI): Future[Unit] = {
    val resolvedUri = libraryBaseUri.resolve(uri)
    repoSelector.selectRepoOrThrow(resolvedUri).writeTracksAsync(trackSpecs, resolvedUri)
  }
}
