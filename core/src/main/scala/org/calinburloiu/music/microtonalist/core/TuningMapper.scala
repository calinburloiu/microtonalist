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

package org.calinburloiu.music.microtonalist.core

import org.calinburloiu.music.intonation.{Interval, Scale}
import org.calinburloiu.music.scmidi.PitchClass

/**
 * Maps a [[Scale]] to a [[PartialTuning]], by choosing the right keys to be used. Keys not used in the partial tuning
 * will have `None` deviations.
 *
 * It is said that a _conflict_ occurs on a tuning key if two scale pitches attempt to map to the same tuning key.
 * This results in throwing a [[TuningMapperConflictException]].
 */
trait TuningMapper {
  def mapScale(scale: Scale[Interval], ref: TuningRef): PartialTuning
}

object TuningMapper {
  /**
   * A [[AutoTuningMapper]] that does not map quarter tones low (e.g. E half-flat is mapped to E on a piano).
   */
  val Default: AutoTuningMapper = AutoTuningMapper(shouldMapQuarterTonesLow = false)
}

// TODO Wouldn't a more functional approach than an exception be more appropriate? Or encode the conflicts inside?
/**
 * Exception thrown if a conflict occurs while mapping a scale to a partial tuning.
 *
 * @see [[TuningMapper]]
 */
class TuningMapperConflictException(scale: Scale[Interval], conflicts: Map[PitchClass, Seq[TuningPitch]])
  extends RuntimeException(s"Cannot tune automatically scale \"${scale.name}\", some pitch classes" +
    s" have conflicts: $conflicts")

/**
 * Exception thrown if the deviation for a pitch class exceeds the allowed values, typically greater or equal than
 * 100.
 */
class TuningMapperOverflowException(message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)
