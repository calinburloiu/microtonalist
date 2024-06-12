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

import com.google.common.math.IntMath
import org.calinburloiu.music.intonation.{Interval, Scale}
import org.calinburloiu.music.scmidi.PitchClass

/**
 * A [[TuningMapper]] that attempts to automatically map scales to a tuning with deviations for some of the pitch
 * classes.
 *
 * Note that some complex scales cannot be mapped automatically because multiple pitches would require to use the same
 * tuning key, resulting in a conflict.
 *
 * @param mapQuarterTonesLow      'true' if a quarter tone should be the lower pitch class with +50 cents deviation or
 *                                `false` if it should be the higher pitch class with -50 cents deviation
 * @param halfTolerance           tolerance value used for deviations when they are close to +50 or -50 cents in
 *                                order to
 *                                avoid precision errors while mapping a quarter tone to its pitch class
 * @param overrideKeyboardMapping a [[KeyboardMapping]] containing scale degree marked as exceptions that are going
 *                                to be manually mapped to a user specified pitch class
 * @param tolerance               Error in cents that should be tolerated when comparing corresponding pitch class
 *                                deviations of
 *                                `PartialTuning`s to avoid double precision errors.
 */
case class AutoTuningMapper(mapQuarterTonesLow: Boolean,
                            halfTolerance: Double = DefaultCentsTolerance,
                            overrideKeyboardMapping: KeyboardMapping = KeyboardMapping.empty,
                            tolerance: Double = DefaultCentsTolerance) extends TuningMapper {

  import AutoTuningMapper._

  private val scaleDegreesMappedManually: Set[Int] = overrideKeyboardMapping.scaleDegrees.flatten.toSet
  private val manualTuningMapper: Option[ManualTuningMapper] = {
    if (overrideKeyboardMapping.isEmpty) None
    else Some(ManualTuningMapper(overrideKeyboardMapping))
  }

  override def mapScale(scale: Scale[Interval], ref: TuningRef): PartialTuning = {
    // TODO #4 Consider Transforming Seq[TuningPitch] into PartialTuning in a reusable manner:
    //     1) In PartialTuning
    //     2) Via KeyboardTuningMapper
    val pitchesInfo = mapScaleToPitchesInfo(scale, ref)
    val deviationsByPitchClass = pitchesInfo
      .map { case PitchInfo(tuningPitch, _) => TuningPitch.unapply(tuningPitch).get }
      .toMap
    val partialTuningValues = (PitchClass.C.number to PitchClass.B.number).map { pitchClass =>
      deviationsByPitchClass.get(PitchClass.fromInt(pitchClass))
    }
    val autoPartialTuning = PartialTuning(partialTuningValues, scale.name)

    val manualPartialTuning = manualTuningMapper match {
      case Some(manualTuningMapper) => manualTuningMapper.mapScale(scale, ref)
      case None => PartialTuning.empty(12)
    }

    val resultPartialTuning = autoPartialTuning.merge(manualPartialTuning, tolerance)
    assert(resultPartialTuning.isDefined,
      "Pitch classes from overrideKeyboardMapping shouldn't be automatically mapped!")
    resultPartialTuning.get
  }

  /**
   * Automatically maps an interval to a pitch class on the keyboard.
   *
   * @param interval interval to be mapped
   * @param ref      reference taken when mapping the interval
   * @return a pitch class with its deviation from 12-EDO
   */
  def mapInterval(interval: Interval, ref: TuningRef): TuningPitch = {
    val totalCents = ref.baseTuningPitch.cents + interval.cents
    val totalSemitones = roundWithTolerance(totalCents / 100, mapQuarterTonesLow, halfTolerance / 100)
    val deviation = totalCents - 100 * totalSemitones
    val pitchClassNumber = IntMath.mod(totalSemitones, 12)

    TuningPitch(PitchClass.fromInt(pitchClassNumber), deviation)
  }

  /**
   * @return the [[KeyboardMapping]] found by the mapper for the given scale and reference
   */
  def keyboardMappingOf(scale: Scale[Interval], ref: TuningRef): KeyboardMapping = {
    val pitches = mapScaleToPitchesInfo(scale, ref)

    /**
     * @param pitches Duplicated non-conflicting pitches that are mapped to the same pitch class. They might differ
     *                slightly in deviation (due to precision errors) and have different scale degree.
     * @return The min scale degree to use for all those pitches.
     */
    def extractScaleDegree(pitches: Seq[PitchInfo]): Int = {
      pitches.foldLeft(Int.MaxValue) { (minScaleDegree, pitch) =>
        val scaleDegree = pitch.scaleDegree
        if (scaleDegree < minScaleDegree) scaleDegree else minScaleDegree
      }
    }

    val scaleDegreesByPitchClass = pitches
      .groupBy(_.tuningPitch.pitchClass)
      .view.mapValues(extractScaleDegree)
      .toMap
    val overriddenScaleDegreesByPitchClass = scaleDegreesByPitchClass ++ overrideKeyboardMapping.toMap
    val keyboardMappingScaleDegrees = (PitchClass.C.number to PitchClass.B.number).map { pitchClass =>
      overriddenScaleDegreesByPitchClass.get(PitchClass.fromInt(pitchClass))
    }

    KeyboardMapping(keyboardMappingScaleDegrees)
  }

  /**
   * @return A sequence of pitch information objects each containing a [[TuningPitch]] and a scale degree, with the
   *         pitches mentioned in `overrideKeyboardMapping` excluded.
   */
  private def mapScaleToPitchesInfo(scale: Scale[Interval], ref: TuningRef): Seq[PitchInfo] = {
    val pitchesInfo = scale.intervals.zipWithIndex.map { case (interval, scaleDegree) =>
      PitchInfo(mapInterval(interval, ref), scaleDegree)
    }

    val tuningPitches = pitchesInfo.map(_.tuningPitch)
    val groupedTuningPitches = tuningPitches.groupBy(_.pitchClass)
    val conflicts = groupedTuningPitches.filter(item => filterConflicts(item._2))
    if (conflicts.nonEmpty) {
      throw new TuningMapperConflictException("Cannot tune automatically, some pitch classes have conflicts:" +
        conflicts)
    } else {
      pitchesInfo.filter { pitchInfo => !scaleDegreesMappedManually.contains(pitchInfo.scaleDegree) }
    }
  }

  /**
   * @return true if there are conflicting pitches mapped to the same pitch class, or false otherwise
   */
  private def filterConflicts(tuningPitches: Seq[TuningPitch]): Boolean = {
    if (tuningPitches.lengthCompare(1) == 0) {
      // Can't have a conflict when there is a single candidate on a pitch class
      false
    } else {
      val first = tuningPitches.head
      tuningPitches.tail.exists(item => !item.equalsWithTolerance(first, tolerance))
    }
  }
}

object AutoTuningMapper {
  private case class PitchInfo(tuningPitch: TuningPitch, scaleDegree: Int)
}
