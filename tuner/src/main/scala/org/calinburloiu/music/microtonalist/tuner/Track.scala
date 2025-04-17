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
import org.calinburloiu.music.scmidi.{MidiDeviceHandle, MidiManager, MidiSerialProcessor, MidiSplitter, MultiTransmitter}

import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.{MidiMessage, Receiver}

/**
 * MIDI route for tuning an output device.
 *
 * @param tuningChangeProcessor Interceptor used for detecting MIDI messages that change the tuning.
 */
@ThreadSafe
class Track(val spec: TrackSpec,
            midiManager: MidiManager,
            tuningService: TuningService,
            initMidiMessages: Seq[MidiMessage] = Seq.empty) extends Runnable, AutoCloseable, StrictLogging {

  private val inputDeviceHandle: Option[MidiDeviceHandle] = spec.input.collect {
    case DeviceTrackInputSpec(midiDeviceId, _) => midiManager.openInput(midiDeviceId)
  }
  private val tuningChangeProcessor: Option[TuningChangeProcessor] = if (spec.tuningChangers.nonEmpty) {
    Some(new TuningChangeProcessor(spec.tuningChangers, tuningService))
  } else {
    None
  }
  private val tunerProcessor: Option[TunerProcessor] = spec.tuner.map { tuner => new TunerProcessor(tuner) }
  private val outputDeviceHandle: Option[MidiDeviceHandle] = spec.output.collect {
    case DeviceTrackOutputSpec(midiDeviceId, _) => midiManager.openOutput(midiDeviceId)
  }

  private val outputSplitter: MidiSplitter = new MidiSplitter
  private val pipeline: MidiSerialProcessor = new MidiSerialProcessor(
    Seq(tuningChangeProcessor, tunerProcessor).flatten, outputSplitter.receiver)

  inputDeviceHandle.foreach(_.multiTransmitter.addReceiver(receiver))

  private val outputReceiver: Option[Receiver] = outputDeviceHandle.map(_.receiver)
  outputReceiver.foreach { receiver =>
    outputSplitter.multiTransmitter.addReceiver(receiver)
  }

  sendInitMidiMessages()

  def id: TrackSpec.Id = spec.id

  // TODO #121 Implement Track#run
  override def run(): Unit = {
    logger.warn("Track#run is not yet implemented!")
  }

  def receiver: Receiver = new Receiver {
    override def send(message: MidiMessage, timeStamp: Long): Unit = {
      pipeline.receiver.send(message, timeStamp)
    }

    override def close(): Unit = {}
  }

  def multiTransmitter: MultiTransmitter = outputSplitter.multiTransmitter

  override def close(): Unit = {
    logger.info(s"Closing track $id...")

    logger.info(s"Switching back to 12-EDO for track $id...")
    tune(Tuning.Standard)

    inputDeviceHandle.foreach(_.close())
    outputDeviceHandle.foreach(_.close())
  }

  /**
   * Tunes the output instrument using the provided tuning.
   *
   * @param tuning The tuning to be applied.
   */
  def tune(tuning: Tuning): Unit = {
    tunerProcessor.foreach(_.tune(tuning))
  }

  private def sendInitMidiMessages(): Unit = {
    for (message <- initMidiMessages) {
      pipeline.receiver.send(message, -1)
    }
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
