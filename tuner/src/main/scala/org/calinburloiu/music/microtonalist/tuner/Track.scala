/*
 * Copyright 2021 Calin-Andrei Burloiu
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
import org.calinburloiu.music.microtonalist.composition.OctaveTuning
import org.calinburloiu.music.microtonalist.composition.PianoKeyboardTuningUtils.TuningExtension
import org.calinburloiu.music.scmidi.{MidiSerialProcessor, ScCcMidiMessage}

import java.util.UUID
import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.{MidiMessage, Receiver}

/**
 * MIDI route for tuning an output device.
 *
 * @param tuningChangeProcessor Interceptor used for detecting MIDI messages that change the tuning.
 * @param tuner                 Class responsible for tuning the output instrument based on specific protocol.
 * @param outputReceiver        MIDI [[Receiver]] of the output instrument.
 * @param ccParams              Map of CC parameters to be set during initialization.
 */
@ThreadSafe
class Track(tuningChangeProcessor: Option[TuningChangeProcessor],
            tunerProcessor: TunerProcessor,
            outputReceiver: Receiver,
            ccParams: Map[Int, Int] = Map.empty) extends Receiver with Runnable with StrictLogging {

  // TODO #97 Reading Tracks from a file requires assigning this value from the constructor.
  val id: String = UUID.randomUUID().toString

  private val pipeline: MidiSerialProcessor = new MidiSerialProcessor(
    Seq(tuningChangeProcessor, Some(tunerProcessor)).flatten, outputReceiver)

  initCcParams()

  // TODO #97 Implement Track#run
  override def run(): Unit = {
    logger.warn("Track#run is not yet implemented!")
  }

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    pipeline.send(message, timeStamp)
  }

  /**
   * Tunes the output instrument using the provided tuning.
   *
   * @param tuning The tuning to be applied.
   */
  def tune(tuning: OctaveTuning): Unit = {
    logger.info(s"Tuning to ${tuning.toPianoKeyboardString}")
    tunerProcessor.tune(tuning)
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getName}...")

  private def initCcParams(): Unit = {
    for ((number, value) <- ccParams) {
      outputReceiver.send(ScCcMidiMessage(Track.DefaultOutputChannel, number, value).javaMidiMessage, -1)
    }
  }
}

object Track {
  // TODO #97 Using a default channel is an ugly hack
  @deprecated
  val DefaultOutputChannel: Int = 0
}
