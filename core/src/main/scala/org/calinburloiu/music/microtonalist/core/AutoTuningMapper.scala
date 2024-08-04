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

import scala.collection.mutable

/**
 * A [[TuningMapper]] that attempts to automatically map scales to a tuning with deviations for some of the pitch
 * classes.
 *
 * Note that some complex scales cannot be mapped automatically because multiple pitches would require to use the same
 * tuning key, resulting in a conflict.
 *
 * @param shouldMapQuarterTonesLow 'true' if the mapper should attempt to map a quarter tone to the lower pitch class
 *                                 with +50 cents deviation, or `false` if it should attempt to map it to the higher
 *                                 pitch class with -50 cents deviation. Note that in case of a conflict, with
 *                                 multiple intervals on the same key pitch class, the mapper will prioritize the
 *                                 conflict resolution and avoid this flag.
 * @param quarterToneTolerance     tolerance value used for deviations when they are close to +50 or -50 cents in
 *                                 order to avoid precision errors while mapping a quarter tone to its pitch class
 * @param overrideKeyboardMapping  a [[KeyboardMapping]] containing scale pitch index mar ked as exceptions that are
 *                                 going to be manually mapped to a user specified pitch class
 * @param tolerance                Error in cents that should be tolerated when comparing corresponding pitch class
 *                                 deviations of `PartialTuning`s to avoid floating-point precision errors.
 */
case class AutoTuningMapper(shouldMapQuarterTonesLow: Boolean,
                            quarterToneTolerance: Double = DefaultCentsTolerance,
                            overrideKeyboardMapping: KeyboardMapping = KeyboardMapping.empty,
                            tolerance: Double = DefaultCentsTolerance) extends TuningMapper {

  import AutoTuningMapper._

  private val scalePitchIndexesMappedManually: Set[Int] = overrideKeyboardMapping.indexesInScale.flatten.toSet
  private val manualTuningMapper: Option[ManualTuningMapper] = {
    if (overrideKeyboardMapping.isEmpty) None
    else Some(ManualTuningMapper(overrideKeyboardMapping))
  }

  override def mapScale(scale: Scale[Interval], ref: TuningRef): PartialTuning = {
    val pitchesInfo = mapScaleToPitchesInfo(scale, ref)
    val deviationsByPitchClass = pitchesInfo
      .map { case PitchInfo(tuningPitch, _) => TuningPitch.unapply(tuningPitch).get }
      .toMap
    val partialTuningValues = (PitchClass.C.number to PitchClass.B.number).map { pitchClassNum =>
      deviationsByPitchClass.get(PitchClass.fromInt(pitchClassNum))
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
  def mapInterval(interval: Interval,
                  ref: TuningRef,
                  overrideShouldMapQuarterTonesLow: Option[Boolean] = None): TuningPitch = {
    val totalCents = ref.baseTuningPitch.cents + interval.cents
    val halfDown = overrideShouldMapQuarterTonesLow.getOrElse(shouldMapQuarterTonesLow)
    val totalSemitones = roundWithTolerance(totalCents / 100, halfDown, quarterToneTolerance / 100)
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
     *                slightly in deviation (due to precision errors) and have different scale pitch index.
     * @return The min scale pitch index to use for all those pitches.
     */
    def extractScalePitchIndex(pitches: Seq[PitchInfo]): Int = {
      pitches.foldLeft(Int.MaxValue) { (minScalePitchIndex, pitch) =>
        val scalePitchIndex = pitch.scalePitchIndex
        if (scalePitchIndex < minScalePitchIndex) scalePitchIndex else minScalePitchIndex
      }
    }

    val scalePitchIndexesByPitchClass = pitches
      .groupBy(_.tuningPitch.pitchClass)
      .view.mapValues(extractScalePitchIndex)
      .toMap
    val overriddenScalePitchIndexesByPitchClass = scalePitchIndexesByPitchClass ++ overrideKeyboardMapping.toMap
    val keyboardMappingScalePitchIndexes = (PitchClass.C.number to PitchClass.B.number).map { pitchClass =>
      overriddenScalePitchIndexesByPitchClass.get(PitchClass.fromInt(pitchClass))
    }

    KeyboardMapping(keyboardMappingScalePitchIndexes)
  }

  /**
   * @return A sequence of pitch information objects each containing a [[TuningPitch]] and a scale pitch index, with the
   *         pitches mentioned in `overrideKeyboardMapping` excluded.
   */
  private def mapScaleToPitchesInfo(scale: Scale[Interval], ref: TuningRef): Seq[PitchInfo] = {
    val pitchesInfoBuffer = mutable.ArrayBuffer[PitchInfo]()
    val scalePitchIndexRange = if (shouldMapQuarterTonesLow) {
      0 until scale.size
    } else {
      (scale.size - 1) to 0 by -1
    }
    var lastPitchClassNumber = -1
    for (scalePitchIndex <- scalePitchIndexRange) {
      val currInterval = scale(scalePitchIndex)
      var tuningPitch = mapInterval(currInterval, ref)
      if (tuningPitch.pitchClass.number == lastPitchClassNumber) {
        tuningPitch = mapInterval(currInterval, ref, Some(!shouldMapQuarterTonesLow))
      }

      pitchesInfoBuffer += PitchInfo(tuningPitch, scalePitchIndex)

      lastPitchClassNumber = tuningPitch.pitchClass.number
    }

    val pitchesInfo = pitchesInfoBuffer.toSeq

    // Check if there are conflicts
    val tuningPitches = pitchesInfo.map(_.tuningPitch)
    val groupedTuningPitches = tuningPitches.groupBy(_.pitchClass)
    val conflicts = groupedTuningPitches.filter(item => filterConflicts(item._2))

    if (conflicts.nonEmpty) {
      throw new TuningMapperConflictException(scale, conflicts)
    } else {
      pitchesInfo.filter { pitchInfo => !scalePitchIndexesMappedManually.contains(pitchInfo.scalePitchIndex) }
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
      tuningPitches.tail.exists(item => !item.almostEquals(first, tolerance))
    }
  }
}

object AutoTuningMapper {
  private case class PitchInfo(tuningPitch: TuningPitch, scalePitchIndex: Int)
}
