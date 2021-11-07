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
// TODO Params such as tolerance should be part of a TuningReducer specific spec
class MergeTuningReducer(tolerance: Double = 0.5e-2) extends TuningReducer with StrictLogging {

  override def apply(partialTunings: Seq[PartialTuning], globalFillTuning: PartialTuning): TuningList = {
    checkArgument(partialTunings.nonEmpty)

    val tuningSize = partialTunings.head.size
    val reducedPartialTunings =
      collect(partialTunings, PartialTuning.empty(tuningSize), tuningSize)
    val maybeTunings = reducedPartialTunings.map { partialTuning =>
      val enrichedPartialTuning = Seq(
        partialTuning,
        globalFillTuning
      ).reduce(_ fill _)

      enrichedPartialTuning.resolve(partialTuning.name)
    }

    if (maybeTunings.forall(_.nonEmpty)) {
      TuningList(maybeTunings.map(_.get))
    } else {
      throw new IncompleteTuningsException(s"Some tunings are not complete: $maybeTunings")
    }
  }

  private[this] def collect(partialTunings: Seq[PartialTuning],
                            backFill: PartialTuning,
                            tuningSize: Int): List[PartialTuning] = {
    if (partialTunings.isEmpty) {
      List.empty
    } else {
      val (mergedPartialTuning, partialTuningsLeft) = merge(PartialTuning.empty(tuningSize), partialTunings)
      val mergedPartialTuningWithBackFill = mergedPartialTuning.fill(backFill)
      val forwardResult = collect(partialTuningsLeft, mergedPartialTuningWithBackFill, tuningSize)
      val result = forwardResult.headOption match {
        case Some(forFill) => mergedPartialTuningWithBackFill.fill(forFill)
        case None => mergedPartialTuningWithBackFill
      }
      result :: forwardResult
    }
  }

  @tailrec
  private[this] def merge(acc: PartialTuning,
                          partialTunings: Seq[PartialTuning]): (PartialTuning, Seq[PartialTuning]) = {
    partialTunings.headOption match {
      case Some(nextPartialTuning) =>
        acc.merge(nextPartialTuning, tolerance) match {
          case Some(mergedTuning) =>
            merge(mergedTuning, partialTunings.tail)

          case None => (acc, partialTunings)
        }

      case None => (acc, partialTunings)
    }
  }
}
