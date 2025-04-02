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
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import org.calinburloiu.music.scmidi.MidiManager

import java.util.concurrent.*
import javax.annotation.concurrent.NotThreadSafe
import scala.collection.immutable.VectorMap

// TODO #121 Logic to update tracks.

/**
 * Manages a collection of MIDI tracks and updates their tuning based on external events.
 */
@NotThreadSafe
class TrackManager(private val midiManager: MidiManager,
                   private val tuningService: TuningService,
                   private val executorService: ExecutorService = TrackManager.createExecutorService())
  extends AutoCloseable with StrictLogging {

  private var tracksById: VectorMap[TrackSpec.Id, Track] = VectorMap()

  def tracks: Seq[Track] = tracksById.values.toSeq

  private def tracks_=(tracks: Seq[Track]): Unit = {
    tracksById = tracks.map(track => track.spec.id -> track).to(VectorMap)
  }

  /**
   * Replaces all existing tracks with new ones based on the provided track specifications and configures them.
   *
   * @param trackSpecs The specifications of the tracks to be created.
   */
  def replaceAllTracks(trackSpecs: TrackSpecs): Unit = {
    closeTracks()

    tracks = trackSpecs.tracks
      .filter { spec =>
        if (spec.muted) {
          logger.info(s"Track \"${trackSpecs.nameOf(spec.id)}\" with id=${spec.id} is muted. Skipping.")
        }

        !spec.muted
      }.map { spec => Track(spec, midiManager, tuningService) }

    // Wire inter-track connections
    for (currTrack <- tracks) {
      currTrack.spec.input match {
        case Some(FromTrackInputSpec(trackId, _)) =>
          val fromTrack = tracksById(trackId)
          fromTrack.multiTransmitter.addReceiver(currTrack.receiver)
        case _ => // Nothing to do here
      }

      currTrack.spec.output match {
        case Some(ToTrackOutputSpec(trackId, _)) =>
          val toTrack = tracksById(trackId)
          currTrack.multiTransmitter.addReceiver(toTrack.receiver)
        case _ => // Nothing to do here
      }
    }
  }

  /**
   * Applies a specified tuning to all managed tracks.
   *
   * @param tuning The tuning to be applied to the tracks.
   */
  def tune(tuning: Tuning): Unit = {
    logger.info(s"Tuning to ${tuning.toPianoKeyboardString}")
    for (track <- tracks) {
      track.tune(tuning)
    }
  }

  /**
   * Closes all managed resources associated with the TrackManager.
   *
   * This method ensures that the MIDI devices of all tracks are properly closed.
   */
  override def close(): Unit = {
    closeTracks()
    executorService.shutdown()
  }

  private def closeTracks(): Unit = {
    tracks.foreach(_.close())
  }

  /**
   * Handles tuning change events by applying the updated tuning to all managed tracks.
   *
   * @param event The tuning session event containing the current tuning to be applied to the tracks.
   */
  // TODO #90 Remove @Subscribe after implementing businessync.
  @Subscribe
  private def onTuningChanged(event: TuningEvent): Unit = {
    tune(event.currentTuning)
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
