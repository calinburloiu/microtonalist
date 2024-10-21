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

/**
 * [[TuningReducer]] algorithm that essentially does no reduce and attempts to map each partial tuning to a final
 * tuning. It should be used if no reduction is wanted.
 */
case class DirectTuningReducer() extends TuningReducer with StrictLogging {

  override def reduceTunings(partialTunings: Seq[PartialTuning],
                             globalFillTuning: PartialTuning = PartialTuning.StandardTuningOctave): TuningList = {
    val tunings = partialTunings.map { partialTuning =>
      val enrichedPartialTuning = partialTuning.fill(globalFillTuning)
      if (!enrichedPartialTuning.isComplete) {
        logger.info(s"Incomplete tuning: ${enrichedPartialTuning.unfilledPitchClassesString}")
      }

      enrichedPartialTuning.resolve
    }

    TuningList(tunings)
  }
}
