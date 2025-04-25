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

import java.net.URI
import javax.annotation.concurrent.ThreadSafe
import scala.concurrent.Future

/**
 * Service that exposes track management capabilities to the application layer.
 *
 * The service makes sure that all operations are executed on the business thread.
 *
 * @param session     Object where all mutable operations are performed.
 * @param businessync Provides thread communication capabilities.
 */
@ThreadSafe
class TrackService(session: TrackSession,
                   businessync: Businessync) {

  /**
   * Opens a new track session by loading the track list from the given URI.
   *
   * @param uri the URI from which tracks are read.
   */
  def open(uri: URI): Future[Unit] = businessync.callAsync { () =>
    session.open(uri)
  }

  /**
   * Replaces all tracks in the current session with the provided track specifications.
   *
   * @param tracks The new track specifications to replace the current tracks.
   */
  def replaceAllTracks(tracks: TrackSpecs): Unit = businessync.run { () =>
    session.tracks = tracks
  }
}
