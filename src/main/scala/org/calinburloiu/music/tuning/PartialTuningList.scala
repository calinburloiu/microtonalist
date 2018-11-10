package org.calinburloiu.music.tuning

case class PartialTuningList(
  globalFillTuning: PartialTuning,
  tuningModulations: Seq[TuningModulation]
)

case class TuningModulation(
  tuningName: String,
  tuning: PartialTuning,
  fillTuning: PartialTuning
)
