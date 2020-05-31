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

import com.google.common.base.Preconditions
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.Interval
import org.calinburloiu.music.microtuner.{Modulation, OriginOld, ScaleList}

import scala.annotation.tailrec

case class TuningList(
  tunings: Seq[Tuning]
) extends Iterable[Tuning] {

  def apply(index: Int): Tuning = {
    Preconditions.checkElementIndex(index, size)

    tunings(index)
  }

  override def size: Int = tunings.size

  override def iterator: Iterator[Tuning] = tunings.iterator
}

object TuningList extends StrictLogging {

  def fromScaleList(scaleList: ScaleList): TuningList = {
    val globalFillScale = scaleList.globalFill.scale
    val globalFillTuning = scaleList.globalFill.tuningMapper(scaleList.origin.basePitchClass, globalFillScale)

    val tuningModulations = createTuningModulations(Interval.UNISON, Vector.empty,
      scaleList.modulations, scaleList.origin)

    val partialTuningList = PartialTuningList(globalFillTuning, tuningModulations)
    scaleList.tuningListReducer(partialTuningList)
  }

  @tailrec
  private[this] def createTuningModulations(
      cumulativeTransposition: Interval,
      tuningModulationsAcc: Seq[TuningModulation],
      modulations: Seq[Modulation],
      origin: OriginOld): Seq[TuningModulation] = {
    if (modulations.isEmpty) {
      tuningModulationsAcc
    } else {
      val crtTransposition = modulations.head.transposition
      // TODO Do we need to normalize?
      val newCumulativeTransposition = if (cumulativeTransposition.normalize.isUnison) {
        crtTransposition.normalize
      } else {
        (cumulativeTransposition + crtTransposition).normalize
      }
      val tuningModulation = createTuningModulation(
        newCumulativeTransposition, modulations.head, origin)

      createTuningModulations(newCumulativeTransposition, tuningModulationsAcc :+ tuningModulation,
        modulations.tail, origin)
    }
  }

  private[this] def createTuningModulation(
      cumulativeTransposition: Interval,
      modulation: Modulation,
      origin: OriginOld): TuningModulation = {
    val scaleName = modulation.scaleMapping.scale.name

    val extensionTuning = modulation.extension.map(_.tuning(origin, cumulativeTransposition))
      .getOrElse(PartialTuning.Empty12PianoKeys)

    val tuning = modulation.scaleMapping.tuning(origin, cumulativeTransposition).overwrite(extensionTuning)

    val fillTuning = modulation.fill.map(_.tuning(origin, cumulativeTransposition))
      .getOrElse(PartialTuning.Empty12PianoKeys)

    TuningModulation(scaleName, tuning, fillTuning)
  }
}
