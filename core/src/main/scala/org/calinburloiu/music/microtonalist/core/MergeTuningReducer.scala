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
 *   1. **Fore-fill:** deviations that come from succeeding merged `PartialTuning`s.
 *
 * The local fill applied attempts to minimize the number of notes retuned when switching tunings. When one plays a
 * piano with sustain pedal and the tuning is changed, a large number of nodes retuned could result in an unwanted
 * effect.
 *
 * @param equalityTolerance Error in cents that should be tolerated when comparing corresponding pitch class
 *                          deviations of `PartialTuning`s to avoid double precision errors.
 */
case class MergeTuningReducer(equalityTolerance: Double = DefaultCentsTolerance) extends TuningReducer with
  StrictLogging {

  override val typeName: String = MergeTuningReducer.typeName

  override def reduceTunings(partialTunings: Seq[PartialTuning],
                             globalFillTuning: PartialTuning = PartialTuning.StandardTuningOctave): TuningList = {
    if (partialTunings.isEmpty) {
      return TuningList(Seq.empty)
    }

    val tuningSize = partialTunings.head.size
    val reducedPartialTunings = collect(partialTunings, PartialTuning.empty(tuningSize), tuningSize)
    val tunings = reducedPartialTunings.map { partialTuning =>
      val enrichedPartialTuning = partialTuning.fill(globalFillTuning)
      if (!enrichedPartialTuning.isComplete) {
        logger.info(s"Incomplete tuning: ${enrichedPartialTuning.unfilledPitchClassesString}")
      }

      enrichedPartialTuning.resolve
    }

    TuningList(tunings)
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
        acc.merge(nextPartialTuning, equalityTolerance) match {
          case Some(mergedTuning) =>
            merge(mergedTuning, partialTunings.tail)

          case None => (acc, partialTunings)
        }

      case None => (acc, partialTunings)
    }
  }
}

object MergeTuningReducer {
  val typeName: String = "merge"
}
