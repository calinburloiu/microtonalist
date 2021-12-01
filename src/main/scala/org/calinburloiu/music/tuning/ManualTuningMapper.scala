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
import org.calinburloiu.music.intonation.{CentsInterval, Interval, PitchClass, Scale}
import org.calinburloiu.music.microtuner.TuningRef

case class ManualTuningMapper(keyboardMapping: KeyboardMapping) extends TuningMapper {
  import ManualTuningMapper._

  override def mapScale(scale: Scale[Interval], ref: TuningRef): PartialTuning = {
    val partialTuningValues = keyboardMapping.values.map { pair =>
      val maybeScaleDegree = pair._2
      maybeScaleDegree match {
        case Some(scaleDegree) =>
          val pitchClass = PitchClass.fromInt(pair._1)
          val interval = scale(scaleDegree)
          val totalCentsInterval = (ref.baseTuningPitch.interval + CentsInterval(interval.cents)).normalize
          val deviation = ManualTuningMapper.computeDeviation(totalCentsInterval, pitchClass)
          if (deviation <= MinExclusiveDeviation || deviation >= MaxExclusiveDeviation) {
            throw new TuningMapperOverflowException(
              s"Deviation $deviation for ${pitchClass} overflowed range ($MinExclusiveDeviation, $MaxExclusiveDeviation)!")
          }

          Some(deviation)

        case None =>
          None
      }
    }

    PartialTuning(partialTuningValues, scale.name)
  }
}

object ManualTuningMapper {
  val MinExclusiveDeviation: Double = -100.0
  val MaxExclusiveDeviation: Double = 100.0

  private def computeDeviation(totalCentsInterval: CentsInterval, pitchClass: PitchClass): Double = {
    if (pitchClass == PitchClass.C) {
      val v1 = totalCentsInterval.cents
      val v2 = totalCentsInterval.cents - 1200
      if (Math.abs(v1) < Math.abs(v2)) v1 else v2
    } else {
      totalCentsInterval.cents - pitchClass * 100
    }
  }
}
