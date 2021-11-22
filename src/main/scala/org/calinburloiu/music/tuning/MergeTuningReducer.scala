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

import com.typesafe.scalalogging.StrictLogging

import scala.annotation.tailrec

/**
 * Reducing algorithm for a sequence of [[PartialTuning]]s that attempts to merge consecutive `PartialTuning`s that
 * don't have conflicts. The merging is performed in the order of the sequence.
 *
 * Two `PartialTuning`s are said to have conflicts if they have at least one pair corresponding pitch class deviations
 * with different values, the rest of them being equal or have close values (see `tolerance`).
 *
 * The algorithm also attempt to apply two kinds _local fill_:
 *
 *   1. **Back-fill:** deviations that come from preceding merged `PartialTuning`s.
 *      2. **Fore-fill:** deviations that come from succeeding merged `PartialTuning`s.
 *
 * The local fill applied attempts to minimize the number of notes retuned when switching tunings. When one plays a
 * piano with sustain pedal and the tuning is changed, a large number of nodes retuned could result in an unwanted
 * effect.
 *
 * @param tolerance Error in cents that should be tolerated when comparing corresponding pitch class deviations of
 *                  `PartialTuning`s.
 */
case class MergeTuningReducer(tolerance: Double = 0.5e-2) extends TuningReducer with StrictLogging {

  override def reduceTunings(partialTunings: Seq[PartialTuning],
                             globalFillTuning: PartialTuning = PartialTuning.StandardTuningOctave): TuningList = {
    if (partialTunings.isEmpty) {
      return TuningList(Seq.empty)
    }

    val tuningSize = partialTunings.head.size
    val reducedPartialTunings =
      collect(partialTunings, PartialTuning.empty(tuningSize), tuningSize)
    val maybeTunings = reducedPartialTunings.map { partialTuning =>
      val enrichedPartialTuning = Seq(
        partialTuning,
        globalFillTuning
      ).reduce(_ fill _)

      enrichedPartialTuning.resolve
    }

    if (maybeTunings.forall(_.nonEmpty)) {
      TuningList(maybeTunings.map(_.get))
    } else {
      // TODO Consider not throwing here, but instead returning a special object
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
        case Some(foreFill) => mergedPartialTuningWithBackFill.fill(foreFill)
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
