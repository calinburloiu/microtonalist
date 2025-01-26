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

import com.google.common.eventbus.Subscribe

// TODO #95 Logic to update tracks.
class TrackManager(private val tracks: Seq[Track]) {
  // TODO #90 Remove @Subscribe after implementing to businessync.
  @Subscribe
  def onTuningChanged(event: TuningIndexUpdatedEvent): Unit = for (track <- tracks) {
    track.tune(event.currentTuning)
  }
}
