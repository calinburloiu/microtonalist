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

package org.calinburloiu.music.microtuner.format

import org.calinburloiu.music.intonation.format.{Ref, ScaleLibrary}
import org.calinburloiu.music.intonation.{Interval, Scale}
import org.calinburloiu.music.tuning.{TuningMapper, TuningReducer}

/**
 * Class used as a representation for the JSON format of a scale list.
 */
case class ScaleListRepr(name: Option[String],
                         origin: OriginRepr,
                         modulations: Seq[ModulationRepr],
                         tuningReducer: Option[TuningReducer] = None,
                         globalFill: Ref[Scale[Interval]],
                         globalFillTuningMapper: Option[TuningMapper] = None,
                         config: Option[ScaleListConfigRepr]) {

  def resolve(implicit scaleLibrary: ScaleLibrary): ScaleListRepr = {
    copy(
      modulations = modulations.map { modulation =>
        modulation.copy(
          scale = modulation.scale.resolve,
          extension = modulation.extension.map(_.resolve)
        )
      },
      globalFill = globalFill.resolve
    )
  }
}

case class OriginRepr(basePitchClass: Int)

case class ModulationRepr(transposition: Option[Interval] = None,
                          scale: Ref[Scale[Interval]],
                          tuningMapper: Option[TuningMapper],
                          extension: Option[Ref[Scale[Interval]]])

case class ScaleListConfigRepr(mapQuarterTonesLow: Boolean = false)

object ScaleListConfigRepr {

  val Default: ScaleListConfigRepr = ScaleListConfigRepr()
}
