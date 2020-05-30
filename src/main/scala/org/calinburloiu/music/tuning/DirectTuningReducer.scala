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

class DirectTuningReducer extends TuningReducer with StrictLogging {

  override def apply(partialTuningList: PartialTuningList): TuningList = {
    val maybeTunings = partialTuningList.tuningModulations.map { modulation =>
      val partialTuning = Seq(
        modulation.tuning,
        modulation.fillTuning,
        partialTuningList.globalFillTuning
      ).reduce(_ enrich _)
      partialTuning.resolve(modulation.tuningName)
    }

    if (maybeTunings.forall(_.nonEmpty)) {
      TuningList(maybeTunings.map(_.get))
    } else {
      throw new DirectTuningReducerException(
        s"Some tunings are not complete: $maybeTunings")
    }
  }
}

class DirectTuningReducerException(message: String, cause: Throwable = null)
    extends TuningReducerException(message, cause)
