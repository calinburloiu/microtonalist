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
import org.calinburloiu.music.scmidi.{MidiDeviceHandle, MidiManager}

import java.util.concurrent.*
import javax.annotation.concurrent.NotThreadSafe

// TODO #121 Logic to update tracks.

/**
 * Manages a collection of MIDI tracks and updates their tuning based on external events.
 */
@NotThreadSafe
class TrackManager(private val midiManager: MidiManager,
                   private val tuningService: TuningService,
                   private val executorService: ExecutorService = TrackManager.createExecutorService())
  extends AutoCloseable with StrictLogging {

  private var tracks: Seq[Track] = Seq.empty

  /**
   * Replaces all existing tracks with new ones based on the provided track specifications and configures them.
   *
   * @param trackSpecs The specifications of the tracks to be created.
   */
  def replaceAllTracks(trackSpecs: TrackSpecs): Unit = {
    closeTracks()

    tracks = trackSpecs.tracks.flatMap { trackSpec =>
      val inputDeviceHandle = createInputDeviceHandle(trackSpec)
      val outputDeviceHandle = createOutputDeviceHandle(trackSpec)

      if (trackSpec.input.isEmpty && trackSpec.output.isEmpty) {
        logger.warn(s"Track \"${trackSpec.name}\" with id=${trackSpec.id} has no input and output device specified. " +
          s"Skipping.")
        None
      } else if (inputDeviceHandle.isEmpty && outputDeviceHandle.isEmpty) {
        logger.warn(s"Track \"${trackSpec.name}\" with id=${trackSpec.id} has no input and output device available. " +
          s"Skipping.")
        None
      } else {
        val tuningChangeProcessor = if (trackSpec.tuningChangers.nonEmpty)
          Some(new TuningChangeProcessor(trackSpec.tuningChangers, tuningService))
        else
          None
        val tunerProcessor = trackSpec.tuner.map { tuner => new TunerProcessor(tuner) }

        val track = new Track(trackSpec.id, inputDeviceHandle, tuningChangeProcessor, tunerProcessor,
          outputDeviceHandle)
        Some(track)
      }
    }
  }

  /**
   * Applies a specified tuning to all managed tracks.
   *
   * @param tuning The tuning to be applied to the tracks.
   */
  def tune(tuning: Tuning): Unit = {
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

  private def createInputDeviceHandle(trackSpec: TrackSpec): Option[MidiDeviceHandle] = {
    trackSpec.input.flatMap {
      case DeviceTrackInputSpec(midiDeviceId, _) =>
        if (midiManager.isInputAvailable(midiDeviceId)) Some(midiManager.openInput(midiDeviceId))
        else None
      case _ =>
        // TODO #97
        logger.info(s"Unimplemented track input ${trackSpec.input.get}")
        None
    }
  }

  private def createOutputDeviceHandle(trackSpec: TrackSpec): Option[MidiDeviceHandle] = {
    trackSpec.output.flatMap {
      case DeviceTrackOutputSpec(midiDeviceId, _) =>
        if (midiManager.isOutputAvailable(midiDeviceId)) Some(midiManager.openOutput(midiDeviceId))
        else None
      case _ =>
        // TODO #97
        logger.info(s"Unimplemented track output ${trackSpec.output.get}")
        None
    }
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
