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

package org.calinburloiu.music.scmidi


/**
 * Representation of the Pitch Bend Sensitivity (RPN #0) from the MIDI standard.
 *
 * Pitch Bend Sensitivity determines the range of pitch bend changes for a MIDI channel,
 * expressed in semitones and cents. The actual pitch bend changes range from
 * -`semitones`.`cents` to +`semitones`.`cents`.
 *
 * @param semitones The number of semitones for the pitch bend range (0-127).
 *                  This defines the integer part of the range.
 * @param cents     The additional pitch bend range in cents (0-127).
 *                  This adds fractional precision to the semitone value.
 * @throws IllegalArgumentException If the values for `semitones` or `cents`
 *                                  are outside the valid MIDI range (0-127).
 * @example
 * {{{
 * val sensitivity = PitchBendSensitivity(2)       // 2 semitones range
 * val fineTuned = PitchBendSensitivity(2, 50)     // 2 semitones and 50 cents range
 * }}}
 */
case class PitchBendSensitivity(semitones: Int, cents: Int = 0) {
  MidiRequirements.requireUnsigned7BitValue("semitones", semitones)
  MidiRequirements.requireUnsigned7BitValue("cents", cents)

  /**
   * Represents the total pitch bend sensitivity range in cents.
   */
  val totalCents: Int = 100 * semitones + cents
}

object PitchBendSensitivity {
  val Default: PitchBendSensitivity = PitchBendSensitivity(2)
}
