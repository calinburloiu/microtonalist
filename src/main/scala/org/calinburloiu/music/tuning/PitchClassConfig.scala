package org.calinburloiu.music.tuning

// TODO We might want to rename this class.
case class PitchClassConfig(
  mapQuarterTonesLow: Boolean,
  halfTolerance: Double = PitchClassConfig.DEFAULT_HALF_TOLERANCE
)

object PitchClassConfig {

  val DEFAULT_HALF_TOLERANCE: Double = 0.5e-2
}
