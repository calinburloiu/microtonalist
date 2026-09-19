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
import org.calinburloiu.music.scmidi.{MidiDeviceDisconnectedEvent, MidiDeviceFailedToDisconnectEvent, MidiDeviceId,
  MidiDeviceOpenedEvent, MidiDirection, MidiEvent, MidiManager}

import java.util.concurrent.*
import javax.annotation.concurrent.NotThreadSafe
import scala.collection.immutable.VectorMap

// TODO #121 Logic to update tracks.

/**
 * Manages a collection of MIDI tracks and updates them based on external events: it re-tunes every track when the
 * tuning changes, resets the tuner of the tracks whose output device opens, and releases the output of the tracks
 * whose input device gets disconnected.
 */
@NotThreadSafe
class TrackManager(private val midiManager: MidiManager,
                   private val tuningService: TuningService,
                   private val executorService: ExecutorService = TrackManager.createExecutorService())
  extends AutoCloseable with StrictLogging {

  import TrackManager.InputDeviceGone

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

    // Forget the closed tracks before building the new ones, whose devices may publish events while they open
    tracks = Seq.empty

    tracks = trackSpecs.tracks
      .filter { spec =>
        if (spec.muted) {
          logger.info(s"Track \"${trackSpecs.nameOf(spec.id).getOrElse("")}\" with id=${spec.id} is muted. Skipping.")
        }

        !spec.muted
      }.map { spec => Track(spec, midiManager, tuningService) }

    // Wire inter-track connections
    // TODO #296 The two branches below wire each direction independently, so a spec pair declaring the same link
    //  from both ends — A.output = ToTrack(B) and B.input = FromTrack(A) — adds B.receiver to A's transmitter twice
    //  and B then processes every message twice. The duplicate add fires no onAttach, so it is silent.
    for (currTrack <- tracks) {
      currTrack.spec.input match {
        case Some(FromTrackInputSpec(trackId, _)) =>
          val fromTrack = tracksById(trackId)
          fromTrack.transmitter.addReceiver(currTrack.receiver)
        case _ => // Nothing to do here
      }

      currTrack.spec.output match {
        case Some(ToTrackOutputSpec(trackId, _)) =>
          val toTrack = tracksById(trackId)
          currTrack.transmitter.addReceiver(toTrack.receiver)
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

  /**
   * Handles the MIDI device events that concern the devices of the tracks:
   *
   *   - when an output device opens, it resets the tuner of every track whose output is that device, since the device
   *     may have (re)opened after the track was built;
   *   - when an input device gets disconnected, or fails to, it releases the input of every track whose input is that
   *     device, so that no note stays held on its output.
   *
   * @param event The MIDI event published by the [[MidiManager]].
   */
  // TODO #90 Remove @Subscribe after implementing businessync. Guava calls this handler on the thread that publishes
  //  the event, which is CoreMIDI4J's notification thread for a device change, while TrackManager is meant to be used
  //  on the business thread only. It reads the tracks on that thread, so a device that opens while replaceAllTracks
  //  builds the tracks on another thread can miss its tuner reset.
  @Subscribe
  private def onMidiEvent(event: MidiEvent): Unit = event match {
    case MidiDeviceOpenedEvent(deviceId, MidiDirection.Output) =>
      // TODO #303 Restore the current tuning after resetting the tuner.
      tracksWithOutputDevice(deviceId).foreach(_.resetTuner())
    case InputDeviceGone(deviceId) =>
      // TODO #303 Restore the current tuning after resetting the tuner.
      tracksWithInputDevice(deviceId).foreach(_.releaseInput())
    case _ => // Nothing to do for the other events
  }

  private def tracksWithInputDevice(deviceId: MidiDeviceId): Seq[Track] = tracks.filter { track =>
    track.spec.input.collect { case DeviceTrackInputSpec(midiDeviceId, _) => midiDeviceId }.contains(deviceId)
  }

  private def tracksWithOutputDevice(deviceId: MidiDeviceId): Seq[Track] = tracks.filter { track =>
    track.spec.output.collect { case DeviceTrackOutputSpec(midiDeviceId, _) => midiDeviceId }.contains(deviceId)
  }
}

object TrackManager extends LazyLogging {

  /**
   * Matches the [[MidiEvent]]s that tell an input device is gone, whatever came of disconnecting it: a
   * [[MidiDeviceDisconnectedEvent]] and the [[MidiDeviceFailedToDisconnectEvent]] that replaces it when releasing the
   * device throws, which leaves the device just as gone.
   *
   * The two share a handler, and Scala forbids binding a variable in a pattern alternative, so they are matched by
   * name here instead of by `MidiDeviceDisconnectedEvent(deviceId, _) | MidiDeviceFailedToDisconnectEvent(…)`.
   */
  private object InputDeviceGone {
    def unapply(event: MidiEvent): Option[MidiDeviceId] = event match {
      case MidiDeviceDisconnectedEvent(deviceId, MidiDirection.Input) => Some(deviceId)
      case MidiDeviceFailedToDisconnectEvent(deviceId, MidiDirection.Input, _) => Some(deviceId)
      case _ => None
    }
  }

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
