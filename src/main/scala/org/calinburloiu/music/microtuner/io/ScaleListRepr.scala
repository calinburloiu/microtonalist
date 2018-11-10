package org.calinburloiu.music.microtuner.io

import org.calinburloiu.music.intonation.{Interval, PitchClass, Scale}
import org.calinburloiu.music.intonation.io.{Ref, ScaleLibrary}
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
