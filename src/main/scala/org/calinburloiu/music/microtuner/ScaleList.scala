package org.calinburloiu.music.microtuner

import org.calinburloiu.music.intonation._
import org.calinburloiu.music.tuning.{PartialTuning, TuningListReducer, TuningMapper}

case class ScaleList(
                      name: String,
                      origin: OriginOld,
                      modulations: Seq[Modulation],
                      tuningListReducer: TuningListReducer,
                      globalFill: ScaleMapping
)

case class Modulation(
  transposition: Interval,
  scaleMapping: ScaleMapping,
  extension: Option[ScaleMapping],
  fill: Option[ScaleMapping]
)

case class ScaleMapping(
  scale: Scale[Interval],
  tuningMapper: TuningMapper
) {

  // TODO Pay attention to the evil unison below.
  def tuning(origin: OriginOld, transposition: Interval = Interval.UNISON): PartialTuning = {
    tuningMapper(origin.basePitchClass, scale.transpose(transposition))
  }
}