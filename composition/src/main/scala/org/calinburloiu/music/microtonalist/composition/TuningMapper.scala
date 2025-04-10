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

package org.calinburloiu.music.microtonalist.composition

import org.calinburloiu.music.intonation.{Interval, RealInterval, Scale}
import org.calinburloiu.music.microtonalist.common.Plugin
import org.calinburloiu.music.microtonalist.tuner.Tuning
import org.calinburloiu.music.scmidi.PitchClass

/**
 * Maps a [[Scale]] to a [[Tuning]], by choosing the right keys to be used. Keys not used in the tuning
 * will have `None` offsets.
 *
 * It is said that a _conflict_ occurs on a tuning key if two scale pitches attempt to map to the same tuning key.
 * This results in throwing a [[TuningMapperConflictException]].
 */
trait TuningMapper extends Plugin {

  override val familyName: String = TuningMapper.FamilyName

  /**
   * Maps a scale to a tuning.
   *
   * @param scale         Scale to map.
   * @param ref           Tuning reference.
   * @param transposition Interval by which the scale should be transposed before mapping it.
   * @return a tuning for the given scale.
   */
  def mapScale(scale: Scale[Interval], ref: TuningReference, transposition: Interval): Tuning

  /**
   * Maps a scale to a tuning.
   *
   * @param scale Scale to map.
   * @param ref   Tuning reference.
   * @return a tuning for the given scale.
   */
  def mapScale(scale: Scale[Interval], ref: TuningReference): Tuning = {
    val unison = scale.intonationStandard.map(_.unison).getOrElse(RealInterval.Unison)
    mapScale(scale, ref, unison)
  }
}

object TuningMapper {

  val FamilyName: String = "tuningMapper"

  /**
   * A [[AutoTuningMapper]] that does not map quarter tones low (e.g. E half-flat is mapped to E on a piano).
   */
  val Default: AutoTuningMapper = AutoTuningMapper(shouldMapQuarterTonesLow = false)
}

// TODO Wouldn't a more functional approach than an exception be more appropriate? Or encode the conflicts inside?

/**
 * Exception thrown if a conflict occurs while mapping a scale to a tuning.
 *
 * @see [[TuningMapper]]
 */
class TuningMapperConflictException(scale: Scale[Interval], conflicts: Map[PitchClass, Seq[TuningPitch]])
  extends RuntimeException(s"Cannot tune automatically scale \"${scale.name}\", some pitch classes" +
    s" have conflicts: $conflicts")

/**
 * Exception thrown if the tuning offset for a pitch class exceeds the allowed values, typically greater or equal than
 * 100.
 */
class TuningMapperOverflowException(message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)
