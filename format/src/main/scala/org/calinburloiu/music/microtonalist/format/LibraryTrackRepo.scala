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
