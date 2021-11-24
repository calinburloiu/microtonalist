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

import com.google.common.math.IntMath
import org.calinburloiu.music.intonation.{Interval, PitchClassDeviation, Scale}
import org.calinburloiu.music.microtuner.TuningRef

/**
 * A [[TuningMapper]] that attempts to automatically map scales to a piano keyboard tuning that specifies a key for
 * each pitch class, from C to B.
 *
 * Note that some complex scales cannot be mapped automatically because multiple pitches would require to use the same
 * tuning key, resulting in a conflict.
 *
 * @param mapQuarterTonesLow 'true' if a quarter tone should be the lower pitch class with +50 cents deviation or
 *                           `false` if it should be the higher pitch class with -50 cents deviation
 * @param halfTolerance      tolerance value used for deviations when they are close to +50 or -50 cents in order to
 *                           avoid precision errors while mapping a quarter tone to its pitch class
 * @param tolerance          Error in cents that should be tolerated when comparing corresponding pitch class deviations of
 *                           `PartialTuning`s to avoid double precision errors.
 */
case class AutoTuningMapper(mapQuarterTonesLow: Boolean = false,
                            halfTolerance: Double = DefaultCentsTolerance,
                            tolerance: Double = DefaultCentsTolerance) extends TuningMapper {

  override def mapScale(scale: Scale[Interval], ref: TuningRef): PartialTuning = {
    val pitchClassDeviations: Seq[PitchClassDeviation] = scale.intervals.map(mapInterval(_, ref))

    // TODO #5 Consider Transforming Seq[PitchClassDeviation] into PartialTuning in a reusable manner:
    //     1) In PartialTuning
    //     2) Via KeyboardTuningMapper
    val groupsOfPitchClasses = pitchClassDeviations.groupBy(_.pitchClass)
    val conflicts = groupsOfPitchClasses.filter(item => filterConflicts(item._2))
    if (conflicts.nonEmpty) {
      throw new TuningMapperConflictException("Cannot tune automatically, some pitch classes have conflicts:" +
        conflicts)
    } else {
      val pitchClassesMap = pitchClassDeviations.map(PitchClassDeviation.unapply(_).get).toMap
      val partialTuningValues = (0 until 12).map { index =>
        pitchClassesMap.get(index)
      }

      PartialTuning(partialTuningValues, scale.name)
    }
  }

  private def filterConflicts(pitchClassDeviations: Seq[PitchClassDeviation]): Boolean = {
    if (pitchClassDeviations.lengthCompare(1) == 0) {
      // Can't have a conflict when there is a single candidate on a pitch class
      false
    } else {
      val first = pitchClassDeviations.head
      pitchClassDeviations.tail.exists(item => !item.equalsWithTolerance(first, tolerance))
    }
  }

  override def mapInterval(interval: Interval, ref: TuningRef): PitchClassDeviation = {
    val totalCents = ref.basePitchClassDeviation.cents + interval.cents
    val totalSemitones = roundWithTolerance(totalCents / 100, mapQuarterTonesLow, halfTolerance / 100)
    val deviation = totalCents - 100 * totalSemitones
    val pitchClass = IntMath.mod(totalSemitones, 12)

    PitchClassDeviation(pitchClass, deviation)
  }
}
