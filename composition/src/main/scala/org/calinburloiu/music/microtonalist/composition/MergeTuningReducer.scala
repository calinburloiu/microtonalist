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

package org.calinburloiu.music.microtonalist.composition

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.tuner.{DefaultCentsTolerance, Tuning}

import scala.annotation.tailrec

/**
 * Reducing algorithm for a sequence of [[Tuning]]s that attempts to merge consecutive [[Tuning]]s that
 * don't have conflicts. The merging is performed in the order of the sequence.
 *
 * Two [[Tuning]]s are said to have conflicts if they have at least one pair of corresponding pitch class offsets
 * with different values, the rest of them being equal or have close values (see `tolerance`).
 *
 * The algorithm also attempt to apply two kinds _local fill_:
 *
 *   1. **Back-fill:** tuning offsets that come from preceding merged [[Tuning]]s.
 *   1. **Fore-fill:** tuning offsets that come from succeeding merged [[Tuning]]s.
 *
 * The local fill applied attempts to minimize the number of notes retuned when switching tunings. When one plays a
 * piano with sustain pedal and the tuning is changed, a large number of notes retuned could result in an unwanted
 * effect.
 *
 * @param equalityTolerance Error in cents that should be tolerated when comparing corresponding pitch class
 *                          offsets of [[Tuning]]s to avoid double precision errors.
 */
case class MergeTuningReducer(equalityTolerance: Double = DefaultCentsTolerance)
  extends TuningReducer with StrictLogging {

  override val typeName: String = MergeTuningReducer.typeName

  override def reduceTunings(tunings: Seq[Tuning],
                             globalFillTuning: Tuning = Tuning.Standard): TuningList = {
    if (tunings.isEmpty) {
      return TuningList(Seq.empty)
    }

    val tuningSize = tunings.head.size
    val reducedTunings = collect(tunings, Tuning.empty(tuningSize), tuningSize)
    val resultTunings = reducedTunings.map { tuning =>
      val enrichedTuning = tuning.fill(globalFillTuning)
      if (!enrichedTuning.isComplete) {
        logger.info(s"Incomplete tuning: ${enrichedTuning.unfilledPitchClassesString}")
      }

      enrichedTuning
    }

    TuningList(resultTunings)
  }

  private[this] def collect(tunings: Seq[Tuning],
                            backFill: Tuning,
                            tuningSize: Int): List[Tuning] = {
    if (tunings.isEmpty) {
      List.empty
    } else {
      val (mergedTuning, tuningsLeft) = merge(Tuning.empty(tuningSize), tunings)
      val mergedTuningWithBackFill = mergedTuning.fill(backFill)
      val forwardResult = collect(tuningsLeft, mergedTuningWithBackFill, tuningSize)
      val result = forwardResult.headOption match {
        case Some(foreFill) => mergedTuningWithBackFill.fill(foreFill)
        case None => mergedTuningWithBackFill
      }
      result :: forwardResult
    }
  }

  @tailrec
  private[this] def merge(acc: Tuning,
                          tunings: Seq[Tuning]): (Tuning, Seq[Tuning]) = {
    tunings.headOption match {
      case Some(nextTuning) =>
        acc.merge(nextTuning, equalityTolerance) match {
          case Some(mergedTuning) =>
            merge(mergedTuning, tunings.tail)

          case None => (acc, tunings)
        }

      case None => (acc, tunings)
    }
  }
}

object MergeTuningReducer {
  val typeName: String = "merge"
}
