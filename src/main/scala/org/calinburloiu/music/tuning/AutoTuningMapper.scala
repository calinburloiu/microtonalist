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

import org.calinburloiu.music.intonation.{Interval, PitchClass, Scale}

class AutoTuningMapper(val pitchClassConfig: PitchClassConfig = PitchClassConfig())
    extends TuningMapper { // TODO #3 Remove base class config

  private[this] implicit val implicitPitchClassConfig: PitchClassConfig = pitchClassConfig

  def this(mapQuarterTonesLow: Boolean) =
    this(PitchClassConfig(mapQuarterTonesLow, PitchClassConfig.DefaultHalfTolerance))

  override def apply(basePitchClass: PitchClass, scale: Scale[Interval]): PartialTuning = {
    // TODO Refactor (check commented lines or think about a generic solution like KeyboardMapper).
//    val pitchClasses: Seq[PitchClass] = scale.intervals
//      .map(_.normalize).distinct
//      .map { interval =>
//        val cents = interval.cents + basePitchClass.cents
//        Converters.fromCentsToPitchClass(cents, autoTuningMapperConfig.mapQuarterTonesLow)
//      }
    val pitchClasses: Seq[PitchClass] = scale.intervals.map { interval =>
      basePitchClass + interval
    }.distinct

    val groupsOfPitchClasses = pitchClasses.groupBy(_.semitone)
    val pitchClassesWithConflicts = groupsOfPitchClasses
      .filter(_._2.distinct.lengthCompare(1) > 0)
    if (pitchClassesWithConflicts.nonEmpty) {
      throw new AutoTuningMapperException(
          "Cannot tune automatically, some pitch classes have conflicts:" +
              pitchClassesWithConflicts)
    } else {
      val pitchClassesMap = pitchClasses.map(PitchClass.unapply(_).get).toMap
      val partialTuningValues = (0 until 12).map { index =>
        pitchClassesMap.get(index)
      }

      PartialTuning(partialTuningValues)
    }
  }

  override def toString: String = s"AutoTuningMapper($pitchClassConfig)"

  def canEqual(other: Any): Boolean = other.isInstanceOf[AutoTuningMapper]

  // TODO #3 Regenerate equals, hashCode, toString after refactoring
  override def equals(other: Any): Boolean = other match {
    case that: AutoTuningMapper =>
      (that canEqual this) &&
        pitchClassConfig == that.pitchClassConfig
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(pitchClassConfig)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

class AutoTuningMapperException(message: String) extends TuningMapperException(message, null)
