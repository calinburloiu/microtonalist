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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.scmidi.MidiSerialProcessor
import org.calinburloiu.music.scmidi.message.{AllNotesOffMidiMsg, CcMidiMsg, MidiCc, MidiMsg}
import org.calinburloiu.music.scmidi.{ConcurrentMidiTransmitter, MidiChannelCount, MidiDeviceHandle, MidiDirection,
  MidiManager, MidiReceiver}

import javax.annotation.concurrent.ThreadSafe

/**
 * MIDI route for tuning an output device.
 *
 * When the track has a device output, the output device receiver is an initial receiver of the pipeline, so the
 * pipeline attaches to it — and a tuner sends its `reset()` messages to the device — as soon as the track is built.
 * A receiver added later through [[transmitter]] (another track) attaches on its own: the device receiver, already
 * there, is left alone rather than being detached and re-attached.
 *
 * @param spec             The declarative description this track is built from: its id, input, output, tuner and
 *                         tuning changers.
 * @param midiManager      Used to open the input and output MIDI devices named by the spec, and to release them when
 *                         the track is closed.
 * @param tuningService    Notified by the [[TuningChangeProcessor]] when a [[TuningChanger]] decides an effective
 *                         tuning change.
 */
@ThreadSafe
class Track(val spec: TrackSpec,
            midiManager: MidiManager,
            tuningService: TuningService) extends Runnable, AutoCloseable, StrictLogging {

  private val inputDeviceHandle: Option[MidiDeviceHandle] = spec.input.collect {
    case DeviceTrackInputSpec(midiDeviceId, _) => midiManager.openDevice(midiDeviceId, MidiDirection.Input)
  }
  private val tuningChangeProcessor: Option[TuningChangeProcessor] = if (spec.tuningChangers.nonEmpty) {
    Some(TuningChangeProcessor(spec.tuningChangers, tuningService))
  } else {
    None
  }
  private val tunerProcessor: Option[TunerProcessor] = spec.tuner.map { tuner => TunerProcessor(tuner) }
  private val outputDeviceHandle: Option[MidiDeviceHandle] = spec.output.collect {
    case DeviceTrackOutputSpec(midiDeviceId, _) => midiManager.openDevice(midiDeviceId, MidiDirection.Output)
  }

  private val pipeline: MidiSerialProcessor = MidiSerialProcessor(
    Seq(tuningChangeProcessor, tunerProcessor).flatten, outputDeviceHandle.map(_.receiver).toSeq)

  // TODO #298 A track whose output is another track has no output receivers until TrackManager wires the link, and
  //  a processor with none drops messages without processing them. Everything arriving between this subscription
  //  and that wiring is therefore lost to the tuner and the channel state tracker.
  inputDeviceHandle.foreach(_.transmitter.addReceiver(receiver))

  def id: TrackSpec.Id = spec.id

  // TODO #121 Not implemented
  // TODO #90 We probably need to remove this and `extends Runnable` if we make each `Track` a Pekko actor
  override def run(): Unit = {
    logger.warn("Track#run is not yet implemented!")
  }

  /**
   * @return the receiver every MIDI message of this track enters through.
   */
  def receiver: MidiReceiver = pipeline.receiver

  /**
   * @return the transmitter this track's output goes out through: the output device receiver and the receivers of
   *         the tracks fed by this one.
   */
  def transmitter: ConcurrentMidiTransmitter = pipeline.transmitter

  /**
   * Closes the track. It:
   *
   *   1. unsubscribes from its input device, so that nothing the device still sends enters the track;
   *   1. detaches its output device, which the tuner switches back to 12-EDO as it gets detached;
   *   1. switches back to 12-EDO the tracks it feeds, which stay attached;
   *   1. releases its devices through the [[MidiManager]].
   *
   * Each output gets the 12-EDO messages exactly once. The track detaches from its devices because a released handle
   * whose device is still available stays live, and a track built later for the same device gets that same handle: a
   * closed track still attached to it would go on receiving from the input and sending to the output.
   */
  override def close(): Unit = {
    logger.info(s"Closing track $id...")

    inputDeviceHandle.foreach(_.transmitter.removeReceiver(receiver))

    logger.info(s"Switching back to 12-EDO for track $id...")
    // Removing the output device receiver makes the TunerProcessor send it the 12-EDO messages, so tuning afterwards
    // reaches only the receivers left, the tracks this one feeds.
    outputDeviceHandle.foreach(handle => transmitter.removeReceiver(handle.receiver))
    // TODO #305 This also makes 12-EDO the tuner's current tuning. A read-only method rendering the 12-EDO messages
    //  replaces it.
    tune(Tuning.Standard)

    spec.input.foreach {
      case DeviceTrackInputSpec(midiDeviceId, _) => midiManager.closeDevice(midiDeviceId, MidiDirection.Input)
      case _ => // Not a device: nothing to release
    }
    spec.output.foreach {
      case DeviceTrackOutputSpec(midiDeviceId, _) => midiManager.closeDevice(midiDeviceId, MidiDirection.Output)
      case _ => // Not a device: nothing to release
    }
  }

  /**
   * Tunes the output instrument using the provided tuning.
   *
   * @param tuning The tuning to be applied.
   */
  def tune(tuning: Tuning): Unit = {
    tunerProcessor.foreach(_.tune(tuning))
  }

  /**
   * Resets the tuner of this track, if any, sending the messages that reconfigure the output instrument to the output
   * of the track, e.g. after the output device (re)opened, followed by the ones that restore the current tuning of the
   * tuner, so that the output does not fall back to 12-EDO.
   */
  def resetTuner(): Unit = {
    tunerProcessor.foreach(_.reset())
  }

  /**
   * Releases the output of this track after its input became unavailable, so that no note stays held on it. It:
   *
   *   1. releases the Hold (Sustain) and Sostenuto pedals, then sends All Notes Off, on each of the 16 MIDI channels
   *      straight to the output of the track, bypassing the tuner, so that it reaches every channel the tuner may
   *      have used, such as MPE Member Channels. The pedals go first because a latched one takes priority over All
   *      Notes Off, so a note it holds would keep sounding otherwise;
   *   1. resets the tuning changers, so that a trigger held when the input disappeared does not swallow the first
   *      trigger after it comes back;
   *   1. resets the tuner, as [[resetTuner]] does, which also clears the note state of tuners that keep one and
   *      restores the current tuning.
   *
   * The track keeps no state of its own about held notes.
   */
  // TODO #316 A track this one feeds is not released: the messages below reach its pipeline input, where its tuner
  //  discards what falls outside its input zone, and its own tuner and tuning changers are never reset, so its output
  //  device can keep notes held.
  def releaseInput(): Unit = {
    val outputReceivers = transmitter.receivers
    for (channel <- 0 until MidiChannelCount; outputReceiver <- outputReceivers) {
      outputReceiver.send(CcMidiMsg(channel, MidiCc.SustainPedal, 0), -1)
      outputReceiver.send(CcMidiMsg(channel, MidiCc.SostenutoPedal, 0), -1)
      outputReceiver.send(AllNotesOffMidiMsg(channel), -1)
    }

    tuningChangeProcessor.foreach(_.reset())
    resetTuner()
  }
}

object Track {
  /**
   * Default MIDI output channel to be used if not other is provided in a context where a channel number is required.
   *
   * Note that the channel number is 0-based internally, although the `.tracks` files and the UI may expose it as
   * 1-based.
   */
  val DefaultOutputChannel: Int = 0
}
