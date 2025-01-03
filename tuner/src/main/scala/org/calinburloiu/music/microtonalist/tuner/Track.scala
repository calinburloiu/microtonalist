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
import org.calinburloiu.music.scmidi.{MidiSerialProcessor, ScCcMidiMessage}

import javax.sound.midi.{MidiMessage, Receiver}

/**
 * MIDI route for tuning an output device.
 *
 * @param tuningSwitchProcessor Interceptor used for detecting MIDI messages that change the tuning.
 * @param tuner                 Class responsible for tuning the output instrument based on specific protocol.
 * @param outputReceiver        MIDI [[Receiver]] of the output instrument.
 * @param ccParams              Map of CC parameters to be set during initialization.
 */
class Track(tuningSwitchProcessor: Option[TuningSwitchProcessor],
            tuner: TunerProcessor,
            outputReceiver: Receiver,
            ccParams: Map[Int, Int] = Map.empty) extends Receiver with StrictLogging {
  val pipeline: MidiSerialProcessor = new MidiSerialProcessor(
    Seq(tuningSwitchProcessor, Some(tuner)).flatten, outputReceiver)

  initCcParams()

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    pipeline.send(message, timeStamp)
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getCanonicalName}...")

  private def initCcParams(): Unit = {
    for ((number, value) <- ccParams) {
      outputReceiver.send(ScCcMidiMessage(Track.DefaultOutputChannel, number, value).javaMidiMessage, -1)
    }
  }
}

object Track {
  val DefaultOutputChannel: Int = 0
}
