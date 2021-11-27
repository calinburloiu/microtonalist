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
import org.calinburloiu.music.intonation.{Interval, PitchClass, Scale}
import org.calinburloiu.music.microtuner.TuningRef

case class ManualTuningMapper(mapping: Seq[Option[Int]], deviationRange: (Int, Int) = (-100, 100)) extends TuningMapper {
  require(mapping.size == 12, "mapping must have exactly 12 values")
  require(mapping.forall(_.getOrElse(0) >= 0), "mapping values must be natural numbers")
  require(deviationRange._1 < 0 && deviationRange._2 > 0,
    "deviationRange must be a pair of a negative and a positive number")

  override def mapScale(scale: Scale[Interval], ref: TuningRef): PartialTuning = {
    val partialTuningValues = mapping.zipWithIndex.map { pair =>
      val maybeScaleDegree = pair._1
      maybeScaleDegree match {
        case Some(scaleDegree) =>
          val pitchClass = PitchClass.fromInt(pair._2)
          val interval = scale(scaleDegree)
          val totalCents = ref.baseTuningPitch.cents + interval.cents
          val deviation = totalCents % 1200 - pitchClass * 100
          if (deviation < deviationRange._1 || deviation > deviationRange._2) {
            throw new TuningMapperOverflowException(
              s"Deviation $deviation for ${pitchClass} overflowed range [${deviationRange._1}, ${deviationRange._2}!")
          }

          Some(deviation)

        case None =>
          None
      }
    }

    PartialTuning(partialTuningValues, scale.name)
  }
}
