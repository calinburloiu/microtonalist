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

@ThreadSafe
class TrackService(trackSession: TrackSession,
                   businessync: Businessync) {

  def open(uri: URI): Unit = businessync.run { () =>
    trackSession.open(uri)
  }

  def replaceAllTracks(tracks: TrackSpecs): Unit = businessync.run { () =>
    trackSession.tracks = tracks
  }
}
