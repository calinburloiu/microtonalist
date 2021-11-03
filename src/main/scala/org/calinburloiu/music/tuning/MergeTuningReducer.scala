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
    checkArgument(partialTuningList.partialTunings.nonEmpty)

    val tuningSize = partialTuningList.partialTunings.head.size
    val reducedPartialTunings =
      collect(Vector.empty[PartialTuning], partialTuningList.partialTunings, tuningSize)
    val maybeTunings = reducedPartialTunings.map { partialTuning =>
      val enrichedPartialTuning = Seq(
        partialTuning,
        partialTuningList.globalFillTuning
      ).reduce(_ enrich _)

      enrichedPartialTuning.resolve(partialTuning.name)
    }

    if (maybeTunings.forall(_.nonEmpty)) {
      TuningList(maybeTunings.map(_.get))
    } else {
      throw new IncompleteTuningsException(s"Some tunings are not complete: $maybeTunings")
    }
  }

  @tailrec
  private[this] def collect(acc: Seq[PartialTuning],
                            partialTunings: Seq[PartialTuning],
                            tuningSize: Int): Seq[PartialTuning] = {
    if (partialTunings.isEmpty) {
      acc
    } else {
      val (mergedTuningModulation, tuningModulationsLeft) =
        merge(emptyPartialTuning(tuningSize), partialTunings)

      collect(acc :+ mergedTuningModulation, tuningModulationsLeft, tuningSize)
    }
  }

  private[this] def emptyPartialTuning(size: Int) = {
    PartialTuning.empty(size)
  }

  @tailrec
  private[this] def merge(acc: PartialTuning,
                          partialTunings: Seq[PartialTuning]): (PartialTuning, Seq[PartialTuning]) = {
    partialTunings.headOption match {
      case Some(nextPartialTuning) =>
        acc merge nextPartialTuning match {
          case Some(mergedTuning) =>
            val mergedName = mergeName(acc.name, nextPartialTuning.name)
            merge(mergedTuning, partialTunings.tail)

          case None => (acc, partialTunings)
        }

      case None => (acc, partialTunings)
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
