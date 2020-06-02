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

import com.google.common.base.Preconditions._
import com.typesafe.scalalogging.StrictLogging

import scala.annotation.tailrec

// TODO #2 Document after improving the algorithm, explaining what it does.
class MergeTuningReducer extends TuningReducer with StrictLogging {

  override def apply(partialTuningList: PartialTuningList): TuningList = {
    checkArgument(partialTuningList.tuningModulations.nonEmpty)

    val tuningSize = partialTuningList.tuningModulations.head.tuning.size
    val reducedTuningModulations =
      collect(Vector.empty[TuningModulation], partialTuningList.tuningModulations, tuningSize)
    val maybeTunings = reducedTuningModulations.map { tuningModulation =>
      val enrichedPartialTuning = Seq(
        tuningModulation.tuning,
        tuningModulation.fillTuning,
        partialTuningList.globalFillTuning
      ).reduce(_ enrich _)

      enrichedPartialTuning.resolve(tuningModulation.tuningName)
    }

    if (maybeTunings.forall(_.nonEmpty)) {
      TuningList(maybeTunings.map(_.get))
    } else {
      throw new IncompleteTuningsException(s"Some tunings are not complete: $maybeTunings")
    }
  }

  @tailrec
  private[this] def collect(acc: Seq[TuningModulation],
                            tuningModulations: Seq[TuningModulation],
                            tuningSize: Int): Seq[TuningModulation] = {
    if (tuningModulations.isEmpty) {
      acc
    } else {
      val (mergedTuningModulation, tuningModulationsLeft) =
        merge(emptyTuningModulation(tuningSize), tuningModulations)

      collect(acc :+ mergedTuningModulation, tuningModulationsLeft, tuningSize)
    }
  }

  private[this] def emptyTuningModulation(size: Int) = {
    val emptyPartialTuning = PartialTuning.empty(size)
    TuningModulation("", emptyPartialTuning, emptyPartialTuning)
  }

  @tailrec
  private[this] def merge(acc: TuningModulation,
                          tuningModulations: Seq[TuningModulation]): (TuningModulation, Seq[TuningModulation]) = {
    tuningModulations.headOption match {
      case Some(nextTuningModulation) =>
        acc.tuning merge nextTuningModulation.tuning match {
          case Some(mergedTuning) =>
            val mergedName = mergeName(acc.tuningName, nextTuningModulation.tuningName)
            val enrichedFillTuning = acc.fillTuning enrich nextTuningModulation.fillTuning
            val newAcc = TuningModulation(mergedName, mergedTuning, enrichedFillTuning)
            merge(newAcc, tuningModulations.tail)

          case None => (acc, tuningModulations)
        }

      case None => (acc, tuningModulations)
    }
  }

  private[this] def mergeName(leftName: String, rightName: String): String = {
    if (leftName.isEmpty) {
      rightName
    } else {
      s"$leftName | $rightName"
    }
  }
}
