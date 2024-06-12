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

import com.google.common.base.Preconditions.checkElementIndex
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.{Interval, RealInterval}

import scala.annotation.tailrec

case class TuningList(tunings: Seq[OctaveTuning]) extends Iterable[OctaveTuning] {

  def apply(index: Int): OctaveTuning = {
    checkElementIndex(index, size)

    tunings(index)
  }

  override def size: Int = tunings.size

  override def iterator: Iterator[OctaveTuning] = tunings.iterator
}

object TuningList extends StrictLogging {

  def fromScaleList(scaleList: ScaleList): TuningList = {
    val globalFillScale = scaleList.globalFill.scale
    val globalFillTuning = scaleList.globalFill.tuningMapper.mapScale(globalFillScale, scaleList.tuningRef)
    val partialTunings = createPartialTunings(RealInterval.Unison, Vector.empty,
      scaleList.modulations, scaleList.tuningRef)

    scaleList.tuningReducer.reduceTunings(partialTunings, globalFillTuning)
  }

  @tailrec
  private[this] def createPartialTunings(cumulativeTransposition: Interval,
                                         partialTuningsAcc: Seq[PartialTuning],
                                         modulations: Seq[Modulation],
                                         tuningRef: TuningRef): Seq[PartialTuning] = {
    if (modulations.isEmpty) {
      partialTuningsAcc
    } else {
      val crtTransposition = modulations.head.transposition
      // TODO Not sure why I've put an if here in the past. I think I should remove it only use the else part.
      val newCumulativeTransposition = if (cumulativeTransposition.normalize.isUnison) {
        crtTransposition.normalize
      } else {
        (cumulativeTransposition + crtTransposition).normalize
      }
      val partialTuning = createPartialTuning(
        newCumulativeTransposition, modulations.head, tuningRef)

      createPartialTunings(newCumulativeTransposition, partialTuningsAcc :+ partialTuning,
        modulations.tail, tuningRef)
    }
  }

  private[this] def createPartialTuning(cumulativeTransposition: Interval,
                                        modulation: Modulation,
                                        tuningRef: TuningRef): PartialTuning = {
    val scaleName = modulation.scaleMapping.scale.name

    val extensionTuning = modulation.extension.map(_.tuningFor(cumulativeTransposition, tuningRef))
      .getOrElse(PartialTuning.EmptyOctave)

    val tuning = modulation.scaleMapping.tuningFor(cumulativeTransposition, tuningRef).overwrite(extensionTuning)
    tuning.copy(name = scaleName)
  }
}
