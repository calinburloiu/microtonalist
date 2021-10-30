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

package org.calinburloiu.music.microtuner

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtuner.midi.MidiProcessor
import org.calinburloiu.music.tuning.OctaveTuning

/**
 * Trait that can be implemented for tuning an output instrument based on specific protocol.
 */
trait Tuner {
  def tune(tuning: OctaveTuning): Unit
}

/**
 * [[Tuner]] variant to be used for implementation that use the standard Java MIDI library.
 */
trait TunerProcessor extends Tuner with MidiProcessor

class TunerException(cause: Throwable) extends RuntimeException(
  "Failed to send tune message to device! Did you disconnect the device?", cause)

/** Fake [[Tuner]] that can be mixed in with a real tuner to log the current [[OctaveTuning]]. */
trait LoggerTuner extends Tuner with StrictLogging {

  import org.calinburloiu.music.tuning.PianoKeyboardTuningUtils._

  abstract override def tune(tuning: OctaveTuning): Unit = {
    logger.info(s"Tuning to ${tuning.toPianoKeyboardString}")

    super.tune(tuning)
  }
}
