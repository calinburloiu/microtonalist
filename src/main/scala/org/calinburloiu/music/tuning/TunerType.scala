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

package org.calinburloiu.music.tuning

import enumeratum.{Enum, EnumEntry}

sealed trait TunerType extends EnumEntry

/**
 * Tuner type to be used based on input/output device capabilities.
 */
object TunerType extends Enum[TunerType] {
  override def values: IndexedSeq[TunerType] = findValues

  /** Tuner that sends system exclusive MIDI Tuning Standard (MIDI 1.0) messages. */
  case object Mts extends TunerType

  /** Tuner that only allows monophonic playing by sending pitch bend values to tune notes. */
  case object MonophonicPitchBend extends TunerType

  /**
   * Tuner that uses MIDI Polyphonic Expression (MPE) output to achieve microtonal playing.
   * Only recommended for a non-MPE input, such as a standard MIDI keyboard.
   */
  case object MpeOutput extends TunerType

  /** Tuner that fully supports MIDI Polyphonic Expression (MPE), including input from MPE instruments. */
  case object Mpe extends TunerType
}
