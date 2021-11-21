/*
 * Copyright 2020 Calin-Andrei Burloiu
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

import org.calinburloiu.music.intonation.{Interval, PitchClassDeviation, Scale}
import org.calinburloiu.music.microtuner.TuningRef

// TODO #5 Consider merging AutoTuningMapper with AutoTuningMapperContext/Config
/**
 * A [[TuningMapper]] that attempts to automatically map scales to a piano keyboard tuning that specifies a key for
 * each pitch class, from C to B.
 *
 * Note that some complex scales cannot be mapped automatically because multiple pitches would require to use the same
 * tuning key, resulting in a conflict.
 *
 * @param context configuration object that fine tunes the way a scale pitch is mapped to a tuning key
 */
class AutoTuningMapper(val context: AutoTuningMapperContext = AutoTuningMapperContext())
  extends TuningMapper {

  private implicit val implicitPitchClassConfig: AutoTuningMapperContext = context

  def this(mapQuarterTonesLow: Boolean) =
    this(AutoTuningMapperContext(mapQuarterTonesLow, AutoTuningMapperContext.DefaultHalfTolerance))

  override def mapScale(scale: Scale[Interval], ref: TuningRef): PartialTuning = {
    val pitchClassDeviations: Seq[PitchClassDeviation] = scale.intervals.map { interval =>
      ref.basePitchClassDeviation + interval
    }.distinct

    val groupsOfPitchClasses = pitchClassDeviations.groupBy(_.pitchClass)
    val conflictsFound = groupsOfPitchClasses.exists(_._2.lengthCompare(1) > 0)
    if (conflictsFound) {
      throw new TuningMapperConflictException("Cannot tune automatically, some pitch classes have conflicts:" +
        conflictsFound)
    } else {
      val pitchClassesMap = pitchClassDeviations.map(PitchClassDeviation.unapply(_).get).toMap
      val partialTuningValues = (0 until 12).map { index =>
        pitchClassesMap.get(index)
      }

      PartialTuning(partialTuningValues, scale.name)
    }
  }

  override def mapInterval(interval: Interval): PitchClassDeviation = ???

  override def toString: String = s"AutoTuningMapper($context)"

  def canEqual(other: Any): Boolean = other.isInstanceOf[AutoTuningMapper]

  override def equals(other: Any): Boolean = other match {
    case that: AutoTuningMapper =>
      (that canEqual this) &&
        context == that.context
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(context)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}
