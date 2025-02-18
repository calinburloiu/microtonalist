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
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent._
import javax.annotation.concurrent.NotThreadSafe

// TODO #97 Logic to update tracks.

/**
 * Manages a collection of MIDI tracks and updates their tuning based on external events.
 */
@NotThreadSafe
class TrackManager(@deprecated("Only expose TrackSpecs publicly")
                   private val tracks: Seq[Track],
                   private val executorService: ExecutorService = TrackManager.createExecutorService()) {

  /**
   * Handles changes in tuning by applying the updated tuning to all managed tracks.
   *
   * @param event The tuning session event containing the current tuning to be applied to the tracks.
   */
  // TODO #90 Remove @Subscribe after implementing businessync.
  @Subscribe
  def onTuningChanged(event: TuningSessionEvent): Unit = {
    for (track <- tracks) {
      track.tune(event.currentTuning)
    }
  }
}

object TrackManager extends LazyLogging {
  private[tuner] val TrackThreadsNamePrefix: String = "Track-"
  private[tuner] val TrackThreadsGroup: ThreadGroup = new ThreadGroup("Track")
  private val TrackThreadsPriority: Int = Thread.NORM_PRIORITY + 2

  /**
   * Creates a custom version of [[Executors.newCachedThreadPool()]] which:
   *
   *   - Puts track threads into a special [[ThreadGroup]], [[TrackThreadsGroup]].
   *   - Configures a custom name for track threads formatted as `"Track-<id>"`, where `<id>` is the [[Track#id]].
   *   - Bumps the thread priority above normal and business thread.
   *   - Prevents creating a thread when the [[Runnable]] is not a [[Track]].
   */
  private def createExecutorService(): ExecutorService = {
    val threadFactory = new ThreadFactory {
      override def newThread(runnable: Runnable): Thread = runnable match {
        case track: Track =>
          val thread = new Thread(TrackThreadsGroup, track, TrackThreadsNamePrefix + track.id, 0)
          thread.setDaemon(false)
          thread.setPriority(TrackThreadsPriority)

          thread
        case _ =>
          logger.warn("Expected to create a thread from a Track instance but got: " + runnable.getClass.getName)
          null
      }
    }
    new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable], threadFactory)
  }
}
