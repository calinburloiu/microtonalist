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

import com.google.common.math.{DoubleMath, IntMath}
import org.calinburloiu.music.intonation.{Interval, Scale}
import org.calinburloiu.music.microtonalist.common.Plugin
import org.calinburloiu.music.microtonalist.composition.AutoTuningMapper.DefaultShouldMapQuarterTonesLow
import org.calinburloiu.music.microtonalist.tuner.{DefaultCentsTolerance, Tuning}
import org.calinburloiu.music.scmidi.PitchClass

import scala.collection.mutable

/**
 * Enum type for the method used to detect the soft chromatic genus pattern between scale's intervals to allow mapping
 * them on a keyboard by using the characteristic augmented second, despite `shouldMapQuarterTonesLow` property.
 *
 * A pattern of intervals that have the soft chromatic genus is defined here as a trichord with two neighboring
 * relative intervals out of which one is a three-quarter-tone interval and the other is a remaining between a whole
 * tone and an augmented second. The [[aug2Threshold]] property determines the minimum value in cents of the
 * "augmented second".
 *
 * These two intervals can be seen in a tetrachord by adding an interval which has a size between a semitone and a
 * three-quarter-tone.
 *
 * For example, an Easter Hijaz / Hicaz soft tetrachord may have the absolute intervals 12/11, 5/4 and 4/3. The first
 * relative interval is a three-quarter-tone of about 151 cents and the augmented second has about 236 cents. When
 * `shouldMapQuarterTonesLow` is set to false this tetrachord would be mapped on the C key to C, D, E and F keys.
 * However, one would expect a chromatic tetrachord to be mapped to C, Db, E and F. This feature allows that.
 *
 * @param typeName      The identified of the mapping method.
 * @param aug2Threshold The minimum size in cents of the augmented second.
 */
sealed abstract class SoftChromaticGenusMapping(override val typeName: String,
                                                val aug2Threshold: Double) extends Plugin {
  override val familyName: String = "softChromaticGenusMapping"
}

object SoftChromaticGenusMapping {
  val FamilyName: String = "softChromaticGenusMapping"

  val Default: SoftChromaticGenusMapping = Off

  val All: Seq[SoftChromaticGenusMapping] = Seq(Off, Strict, PseudoChromatic)

  /** The special mapping of the soft chromatic genus is disabled. */
  case object Off extends SoftChromaticGenusMapping("off", Double.PositiveInfinity)

  /** The augmented second is slightly larger than a whole tone. */
  case object Strict extends SoftChromaticGenusMapping("strict", 210.0)

  /**
   * The augmented second is about the size a whole tone. Strictly speaking this wouldn't be the chromatic genus
   * anymore, being a soft diatonic. But the way intervals are arranged makes the structure sound like a very soft
   * chromatic. Ottoman Makam Hüzzam may contain such a structure starting from its third degree.
   */
  case object PseudoChromatic extends SoftChromaticGenusMapping("pseudoChromatic", 190.0)
}

/**
 * A [[TuningMapper]] that attempts to automatically map scales to a tuning with deviations for some of the pitch
 * classes.
 *
 * Note that some complex scales cannot be mapped automatically because multiple pitches would require to use the same
 * tuning key, resulting in a conflict.
 *
 * @param shouldMapQuarterTonesLow  'true' if the mapper should attempt to map a quarter tone to the lower pitch class
 *                                  with +50 cents deviation, or `false` if it should attempt to map it to the higher
 *                                  pitch class with -50 cents deviation. Note that in case of a conflict, with
 *                                  multiple intervals on the same key pitch class, the mapper will prioritize the
 *                                  conflict resolution and avoid this flag.
 * @param quarterToneTolerance      tolerance value used for deviations when they are close to +50 or -50 cents in
 *                                  order to avoid precision errors while mapping a quarter tone to its pitch class
 * @param softChromaticGenusMapping Method used to detect the soft chromatic genus pattern between scale's intervals
 *                                  to allow mapping them on a keyboard by using the characteristic augmented second,
 *                                  despite `shouldMapQuarterTonesLow` property.
 * @param overrideKeyboardMapping   a [[KeyboardMapping]] containing scale pitch index mar ked as exceptions that are
 *                                  going to be manually mapped to a user specified pitch class
 * @param tolerance                 Error in cents that should be tolerated when comparing corresponding pitch class
 *                                  deviations of `PartialTuning`s to avoid floating-point precision errors.
 */
case class AutoTuningMapper(shouldMapQuarterTonesLow: Boolean = DefaultShouldMapQuarterTonesLow,
                            quarterToneTolerance: Double = DefaultQuarterToneTolerance,
                            softChromaticGenusMapping: SoftChromaticGenusMapping = SoftChromaticGenusMapping.Default,
                            overrideKeyboardMapping: KeyboardMapping = KeyboardMapping.empty,
                            tolerance: Double = DefaultCentsTolerance) extends TuningMapper {
  override val typeName: String = AutoTuningMapper.typeName

  private type PitchesInfo = Map[Int, TuningPitch]

  private val SoftChromaticSmallInterval = 150.0
  private val SoftChromaticMaxAug2Interval = 300.0

  private val scalePitchIndexesMappedManually: Set[Int] = overrideKeyboardMapping.indexesInScale.flatten.toSet
  private val manualTuningMapper: Option[ManualTuningMapper] = {
    if (overrideKeyboardMapping.isEmpty) None
    else Some(ManualTuningMapper(overrideKeyboardMapping))
  }

  override def mapScale(scale: Scale[Interval], ref: TuningReference, transposition: Interval): Tuning = {
    val transposedScale = scale.transpose(transposition)

    val pitchesInfo = mapScaleToPitchesInfo(transposedScale, ref)
    val tuningName = computeTuningName(scale, pitchesInfo)
    val autoPartialTuning: Tuning = createPartialTuning(pitchesInfo, tuningName)

    val manualPartialTuning: Tuning = getManualPartialTuning(transposedScale, ref)

    val resultPartialTuning = autoPartialTuning.merge(manualPartialTuning, tolerance)
    assert(resultPartialTuning.isDefined,
      "Pitch classes from overrideKeyboardMapping shouldn't be automatically mapped!")
    resultPartialTuning.get
  }

  /**
   * Automatically maps an interval to a pitch class on the keyboard.
   *
   * @param interval                         interval to be mapped
   * @param ref                              reference taken when mapping the interval
   * @param overrideShouldMapQuarterTonesLow if defined, it is used instead of the class member
   *                                         [[shouldMapQuarterTonesLow]]
   * @return a pitch class with its deviation from 12-EDO
   */
  def mapInterval(interval: Interval,
                  ref: TuningReference,
                  overrideShouldMapQuarterTonesLow: Option[Boolean] = None): TuningPitch = {
    val totalCents = ref.baseTuningPitch.cents + interval.cents
    val halfDown = overrideShouldMapQuarterTonesLow.getOrElse(shouldMapQuarterTonesLow)
    val totalSemitones = roundWithTolerance(totalCents / 100, halfDown, quarterToneTolerance / 100)
    val deviation = totalCents - 100 * totalSemitones
    val pitchClassNumber = IntMath.mod(totalSemitones, 12)

    TuningPitch(PitchClass.fromNumber(pitchClassNumber), deviation)
  }

  /**
   * @return the [[KeyboardMapping]] found by the mapper for the given scale and reference
   */
  def keyboardMappingOf(scale: Scale[Interval], ref: TuningReference): KeyboardMapping = {
    val pitchesInfo = mapScaleToPitchesInfo(scale, ref)

    /**
     * @param pitches Duplicated non-conflicting pitches that are mapped to the same pitch class. They might differ
     *                slightly in deviation (due to precision errors) and have different scale pitch index.
     * @return The min scale pitch index to use for all those pitches.
     */
    def extractScalePitchIndex(pitches: PitchesInfo): Int = {
      pitches.min((a: (Int, TuningPitch), b: (Int, TuningPitch)) => a._1.compare(b._1))._1
    }

    val scalePitchIndexesByPitchClass = pitchesInfo
      .groupBy(_._2.pitchClass)
      .view.mapValues(extractScalePitchIndex)
      .toMap
    val overriddenScalePitchIndexesByPitchClass = scalePitchIndexesByPitchClass ++ overrideKeyboardMapping.toMap
    val keyboardMappingScalePitchIndexes = (PitchClass.C.number to PitchClass.B.number).map { pitchClass =>
      overriddenScalePitchIndexesByPitchClass.get(PitchClass.fromNumber(pitchClass))
    }

    KeyboardMapping(keyboardMappingScalePitchIndexes)
  }

  /**
   * @return A sequence of pitch information objects each containing a [[TuningPitch]] and a scale pitch index, with the
   *         pitches mentioned in `overrideKeyboardMapping` excluded.
   */
  private def mapScaleToPitchesInfo(scale: Scale[Interval], ref: TuningReference): PitchesInfo = {
    val mutablePitchesInfo = mutable.Map[Int, TuningPitch]()
    val intervals: Seq[(Interval, Int)] = scale.intervals.zipWithIndex.map {
      case (interval, pitchIndex) => (interval.normalize, pitchIndex)
    }.sortBy(_._1)
    // We add an extra degree to the range and then use modulo to also cover the quarter-tone between the last and
    // the first degree. This was a fix for https://github.com/calinburloiu/microtonalist/issues/76.
    val iterationRange = if (shouldMapQuarterTonesLow) {
      0 to intervals.size
    } else {
      intervals.size to 0 by -1
    }
    var lastPitchClassNumber = -1

    for (iterationIndex <- iterationRange) {
      val intervalIndex = iterationIndex % intervals.size
      val interval = intervals(intervalIndex)._1
      val scalePitchIndex = intervals(intervalIndex)._2

      if (!scalePitchIndexesMappedManually.contains(scalePitchIndex)) {
        var tuningPitch = mapInterval(interval, ref)
        val pitchClass = tuningPitch.pitchClass

        if (pitchClass.number == lastPitchClassNumber || overrideKeyboardMapping(pitchClass).isDefined) {
          // Conflict detected! Attempting to remap the quarter-tone interval in the opposite direction.
          tuningPitch = mapInterval(interval, ref, Some(!shouldMapQuarterTonesLow))
        }

        mutablePitchesInfo.update(scalePitchIndex, tuningPitch)
        lastPitchClassNumber = pitchClass
      }
    }
    val pitchesInfo = mutablePitchesInfo.toMap

    // Check if there are conflicts
    val tuningPitches = pitchesInfo.values.toSeq
    val groupedTuningPitches = tuningPitches.groupBy(_.pitchClass)
    val conflicts = groupedTuningPitches.filter(item => filterConflicts(item._2))

    if (conflicts.nonEmpty) {
      throw new TuningMapperConflictException(scale, conflicts)
    }

    applySoftChromaticGenusMapping(pitchesInfo, scale, ref)
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

  private def applySoftChromaticGenusMapping(pitchesInfo: PitchesInfo,
                                             scale: Scale[Interval],
                                             ref: TuningReference): PitchesInfo = {
    val aug2Threshold = softChromaticGenusMapping.aug2Threshold

    def detect2ndDegreeOfSoftHijaz(intervalBelow: Double, intervalAbove: Double,
                                   keysBelow: Int, keysAbove: Int): Boolean = {
      !shouldMapQuarterTonesLow &&
        DoubleMath.fuzzyEquals(intervalBelow, SoftChromaticSmallInterval, quarterToneTolerance) &&
        DoubleMath.fuzzyCompare(intervalAbove, aug2Threshold, tolerance) >= 0 &&
        DoubleMath.fuzzyCompare(intervalAbove, SoftChromaticMaxAug2Interval, tolerance) <= 0 &&
        keysBelow == 2 && keysAbove == 2
    }

    def detect3rdDegreeOfSoftHijaz(intervalBelow: Double, intervalAbove: Double,
                                   keysBelow: Int, keysAbove: Int): Boolean = {
      shouldMapQuarterTonesLow &&
        DoubleMath.fuzzyCompare(intervalBelow, aug2Threshold, tolerance) >= 0 &&
        DoubleMath.fuzzyCompare(intervalBelow, SoftChromaticMaxAug2Interval, tolerance) <= 0 &&
        DoubleMath.fuzzyEquals(intervalAbove, SoftChromaticSmallInterval, quarterToneTolerance) &&
        keysBelow == 2 && keysAbove == 2
    }

    def mapQuarterTonePitch(index: Int, tuningPitch: TuningPitch): (Int, TuningPitch) = {
      val prevIndex = IntMath.mod(index - 1, pitchesInfo.size)
      val nextIndex = IntMath.mod(index + 1, pitchesInfo.size)

      val maybePrevTuningPitch = pitchesInfo.get(prevIndex)
      val maybeNextTuningPitch = pitchesInfo.get(nextIndex)
      (maybePrevTuningPitch, maybeNextTuningPitch) match {
        case (Some(prevTuningPitch), Some(nextTuningPitch)) =>
          val intervalBelow = (scale(index) - scale(prevIndex)).cents
          val intervalAbove = (scale(nextIndex) - scale(index)).cents
          val keysBelow = IntMath.mod(tuningPitch.pitchClass - prevTuningPitch.pitchClass, 12)
          val keysAbove = IntMath.mod(nextTuningPitch.pitchClass - tuningPitch.pitchClass, 12)

          if (detect2ndDegreeOfSoftHijaz(intervalBelow, intervalAbove, keysBelow, keysAbove)) {
            (index, mapInterval(scale(index), ref, Some(true)))
          } else if (detect3rdDegreeOfSoftHijaz(intervalBelow, intervalAbove, keysBelow, keysAbove)) {
            (index, mapInterval(scale(index), ref, Some(false)))
          } else {
            (index, tuningPitch)
          }

        case _ => (index, tuningPitch)
      }
    }

    if (softChromaticGenusMapping == SoftChromaticGenusMapping.Off) {
      pitchesInfo
    } else {
      pitchesInfo.map { case (index, tuningPitch) =>
        if (tuningPitch.isQuarterTone(quarterToneTolerance)) {
          mapQuarterTonePitch(index, tuningPitch)
        } else {
          (index, tuningPitch)
        }
      }
    }
  }

  private def computeTuningName(scale: Scale[Interval], pitchesInfo: PitchesInfo): String = {
    // TODO #34 Temporary workaround to add the base pitch class in the tuning name before taking it from lineage
    val indexOfUnison = scale.indexOfUnison
    val baseTuningPitch = pitchesInfo.get(indexOfUnison)
    val tuningNamePrefix = baseTuningPitch.map { tuningPitch => s"${tuningPitch.pitchClass} " }.getOrElse("")

    tuningNamePrefix + scale.name
  }

  private def createPartialTuning(pitchesInfo: PitchesInfo, tuningName: String) = {
    val deviationsByPitchClass = pitchesInfo
      .values.map { tuningPitch => TuningPitch.unapply(tuningPitch).get }
      .toMap
    val partialTuningValues = (PitchClass.C.number to PitchClass.B.number).map { pitchClassNum =>
      deviationsByPitchClass.get(PitchClass.fromNumber(pitchClassNum))
    }
    val autoPartialTuning = Tuning(tuningName, partialTuningValues)
    autoPartialTuning
  }

  private def getManualPartialTuning(scale: Scale[Interval], ref: TuningReference) = {
    val manualPartialTuning = manualTuningMapper match {
      case Some(manualTuningMapper) =>
        // Clearing the scale name to avoid name merging
        manualTuningMapper.mapScale(scale.rename(""), ref)
      case None => Tuning.empty(12)
    }

    manualPartialTuning
  }
}

object AutoTuningMapper {
  val typeName: String = "auto"

  val DefaultShouldMapQuarterTonesLow: Boolean = false

  lazy val Default: AutoTuningMapper = AutoTuningMapper()
}
