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
import org.calinburloiu.music.scmidi.MidiDeviceId

import javax.annotation.concurrent.NotThreadSafe
import javax.sound.midi.MidiMessage

/**
 * Trait that can be implemented for tuning an output instrument based on a specific protocol.
 */
@NotThreadSafe
trait Tuner extends Plugin {
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
   * Resets the internal state of the tuner to its default / initial configuration and returns the MIDI messages that
   * should configure / initialize the output device to be usable by this tuner.
   *
   * This method ''must'' be called before using the tuner for the first time and the messages returned ''must'' be
   * sent to the output device to properly work with this tuner. For example, a tuner based on pitch bend requires
   * the output device to be configured with the correct pitch bend sensitivity.
   *
   * @return the MIDI messages that should configure / initialize the output device.
   */
  def reset(): Seq[MidiMessage] = Seq.empty

  /**
   * Tunes the output instrument using the specified octave tuning.
   *
   * @param tuning The tuning instance that specifies the name and deviation in cents for each
   *               of the 12 pitch classes in the octave.
   */
  def tune(tuning: OctaveTuning): Seq[MidiMessage]

  def process(message: MidiMessage): Seq[MidiMessage]
}

object Tuner {
  val FamilyName: String = "tuner"
}
