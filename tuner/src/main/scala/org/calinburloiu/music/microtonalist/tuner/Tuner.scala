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

import org.calinburloiu.music.microtonalist.common.Plugin
import org.calinburloiu.music.microtonalist.composition.OctaveTuning
import org.calinburloiu.music.microtonalist.tuner.Tuner.FamilyName
import org.calinburloiu.music.scmidi.{MidiDeviceId, MidiProcessor}

/**
 * Trait that can be implemented for tuning an output instrument based on a specific protocol.
 */
trait Tuner extends Plugin with MidiProcessor {
  override val familyName: String = FamilyName

  /**
   * Optional MIDI output identifier for an alternative MIDI output device designated for sending the MIDI tuning
   * messages.
   *
   * If specified, this device will be used exclusively for tuning operations instead of the actual MIDI output. If
   * not, the actual MIDI output will be used.
   *
   * Note that there are [[Tuner]]s for which setting this to [[Some]] value doesn't make sense, because in their
   * case tuning values alone do not have any effect without the other messages. This is the case for tuners that use
   * pitch bend.
   */
  val altTuningOutput: Option[MidiDeviceId] = None

  /**
   * Tunes the output instrument using the specified octave tuning.
   *
   * @param tuning The tuning instance that specifies the name and deviation in cents for each
   *               of the 12 pitch classes in the octave.
   */
  def tune(tuning: OctaveTuning): Unit

  override protected def onDisconnect(): Unit = {
    tune(OctaveTuning.Edo12)
  }
}

object Tuner {
  val FamilyName: String = "tuner"
}

class TunerException(cause: Throwable) extends RuntimeException(
  "Failed to send tune message to device! Did you disconnect the device?", cause)
