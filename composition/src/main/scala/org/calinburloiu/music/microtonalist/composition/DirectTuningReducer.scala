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
import org.calinburloiu.music.microtonalist.tuner.Tuning

/**
 * [[TuningReducer]] algorithm that essentially performs no reduce and only applies the global fill. It should be
 * used if no reduction is wanted.
 */
object DirectTuningReducer extends TuningReducer with StrictLogging {

  override val typeName: String = "direct"

  override def reduceTunings(tunings: Seq[Tuning],
                             globalFillTuning: Tuning = Tuning.Standard): TuningList = {
    val resultTunings = tunings.map { tuning =>
      val enrichedTuning = tuning.fill(globalFillTuning)
      if (!enrichedTuning.isComplete) {
        logger.info(s"Incomplete tuning: ${enrichedTuning.unfilledPitchClassesString}")
      }

      enrichedTuning
    }

    TuningList(resultTunings)
  }
}
