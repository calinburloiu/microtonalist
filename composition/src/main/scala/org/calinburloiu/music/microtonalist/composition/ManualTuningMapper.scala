/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.calinburloiu.music.intonation.{CentsInterval, Interval, Scale}
import org.calinburloiu.music.microtonalist.tuner.Tuning
import org.calinburloiu.music.scmidi.PitchClass

/**
 * A [[TuningMapper]] that maps scales to a tuning, with tuning offsets for some of the pitch classes, by using a
 * user-provided mapping, from pitch classes to scale pitch indexes.
 *
 * @param keyboardMapping user-provided mapping from pitch classes to scale pitch indexes
 */
case class ManualTuningMapper(keyboardMapping: KeyboardMapping) extends TuningMapper {

  import ManualTuningMapper.*

  override val typeName: String = ManualTuningMapper.typeName

  override def mapScale(scale: Scale[Interval], ref: TuningReference, transposition: Interval): Tuning = {
    require(keyboardMapping.indexesInScale.flatten.max < scale.size)

    val processedScale = scale.transpose(transposition)
    val tuningValues = keyboardMapping.values.map { pair =>
      val maybeScalePitchIndex = pair._2
      maybeScalePitchIndex match {
        case Some(scalePitchIndex) =>
          val pitchClass = PitchClass.fromNumber(pair._1)
          val interval = processedScale(scalePitchIndex)
          val totalCentsInterval = (ref.baseTuningPitch.interval + CentsInterval(interval.cents)).normalize
          val offset = ManualTuningMapper.computeTuningOffset(totalCentsInterval, pitchClass)
          if (offset <= MinExclusiveTuningOffset || offset >= MaxExclusiveTuningOffset) {
            throw new TuningMapperOverflowException(
              s"Offset $offset for $pitchClass overflowed range ($MinExclusiveTuningOffset, " +
                s"$MaxExclusiveTuningOffset)!")
          }

          Some(offset)

        case None =>
          None
      }
    }

    Tuning(processedScale.name, tuningValues)
  }
}

object ManualTuningMapper {
  val typeName: String = "manual"

  val MinExclusiveTuningOffset: Double = -100.0
  val MaxExclusiveTuningOffset: Double = 100.0

  /**
   * Computes the offset in cents from 12-EDO for the given pitch class.
   *
   * @param totalCentsInterval a normalized total number of cents counted from a 12-EDO C which contains absolute
   *                           scale intervals
   * @param pitchClass         pitch class from which the offset is computed
   * @return an offset in cents
   */
  private def computeTuningOffset(totalCentsInterval: CentsInterval, pitchClass: PitchClass): Double = {
    if (pitchClass == PitchClass.C) {
      // Because totalCentsInterval is counted from a 12-EDO C, it can either be close to 0 (but above it) or close
      // to 1200 (but below it). We take the offset as the one with minimum absolute value.
      val v1 = totalCentsInterval.cents
      val v2 = totalCentsInterval.cents - 1200
      if (Math.abs(v1) < Math.abs(v2)) v1 else v2
    } else {
      totalCentsInterval.cents - pitchClass * 100
    }
  }
}
