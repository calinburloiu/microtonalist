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
import play.api.libs.json.{JsNull, JsValue}

case class ScaleListRepr(
  name: Option[String],
  origin: OriginRepr,
  modulations: Seq[ModulationRepr],
  tuningListReducer: Option[PluginSpecRepr] = None,
  globalFill: Ref[Scale[Interval]],
  globalFillTuningMapper: Option[PluginSpecRepr] = None,
  config: Option[ScaleListConfigRepr]
) {

  def resolve(implicit scaleLibrary: ScaleLibrary): ScaleListRepr = {
    copy(
      modulations = modulations.map { modulation =>
        modulation.copy(
          scale = modulation.scale.resolve,
          extension = modulation.extension.map(_.resolve),
          fill = modulation.fill.map(_.resolve)
        )
      },
      globalFill = globalFill.resolve
    )
  }
}

case class OriginRepr(
  basePitchClass: Int
)

case class PluginSpecRepr(
  id: String,
  config: JsValue = JsNull
)

case class ModulationRepr(
  transposition: Option[Interval] = None,
  scale: Ref[Scale[Interval]],
  tuningMapper: Option[PluginSpecRepr],
  extension: Option[Ref[Scale[Interval]]],
  fill: Option[Ref[Scale[Interval]]],
  fillTuningMapper: Option[PluginSpecRepr]
)

case class ScaleListConfigRepr(
  mapQuarterTonesLow: Boolean = false
)

object ScaleListConfigRepr {

  val DEFAULT = ScaleListConfigRepr()
}
