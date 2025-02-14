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
import org.calinburloiu.music.microtonalist.tuner.MtsTuner.DefaultThru
import org.calinburloiu.music.scmidi.MidiDeviceId

import javax.sound.midi.MidiMessage

/**
 * Base class for all MIDI Tuning Standard (MTS) `Tuner` implementations.
 *
 * @param mtsMessageGenerator Used for generating SysEx MIDI message for MTS.
 * @param thru                Whether to redirect input messages to the output. Note that this can be false when the
 *                            instrument has local control on, and it just needs to receive the MTS SysEx MIDI
 *                            messages that change the tuning.
 */
abstract class MtsTuner(val mtsMessageGenerator: MtsMessageGenerator,
                        val thru: Boolean = DefaultThru) extends Tuner with StrictLogging {

  override def tune(tuning: OctaveTuning): Seq[MidiMessage] = Seq(mtsMessageGenerator.generate(tuning))

  override def process(message: MidiMessage): Seq[MidiMessage] = if (thru) Seq(message) else Seq.empty
}

object MtsTuner {
  val DefaultThru: Boolean = false
}

/**
 * A case class implementing a MIDI Tuning Standard (MTS) tuner for the octave-based,
 * 1-byte, non-real-time tuning protocol.
 */
case class MtsOctave1ByteNonRealTimeTuner(override val thru: Boolean = DefaultThru,
                                          override val altTuningOutput: Option[MidiDeviceId] = None)
  extends MtsTuner(MtsMessageGenerator.Octave1ByteNonRealTime, thru) {

  override val typeName: String = "mtsOctave1ByteNonRealTime"
}

/**
 * A case class implementing a MIDI Tuning Standard (MTS) tuner for the octave-based,
 * 2-byte, non-real-time tuning protocol.
 */
case class MtsOctave2ByteNonRealTimeTuner(override val thru: Boolean = DefaultThru,
                                          override val altTuningOutput: Option[MidiDeviceId] = None)
  extends MtsTuner(MtsMessageGenerator.Octave2ByteNonRealTime, thru) {

  override val typeName: String = "mtsOctave2ByteNonRealTime"
}

/**
 * A case class implementing a MIDI Tuning Standard (MTS) tuner for the octave-based,
 * 1-byte, real-time tuning protocol.
 */
case class MtsOctave1ByteRealTimeTuner(override val thru: Boolean = DefaultThru,
                                       override val altTuningOutput: Option[MidiDeviceId] = None)
  extends MtsTuner(MtsMessageGenerator.Octave1ByteRealTime, thru) {

  override val typeName: String = "mtsOctave1ByteRealTime"
}

/**
 * A case class implementing a MIDI Tuning Standard (MTS) tuner for the octave-based,
 * 2-byte, real-time tuning protocol.
 */
case class MtsOctave2ByteRealTimeTuner(override val thru: Boolean = DefaultThru,
                                       override val altTuningOutput: Option[MidiDeviceId] = None)
  extends MtsTuner(MtsMessageGenerator.Octave2ByteRealTime, thru) {

  override val typeName: String = "mtsOctave2ByteRealTime"
}
