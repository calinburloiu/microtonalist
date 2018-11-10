package org.calinburloiu.music.tuning

import org.calinburloiu.music.intonation.{Interval, PitchClass, Scale}
import org.calinburloiu.music.plugin.{Plugin, PluginConfig}

abstract class TuningMapper(val config: Option[TuningMapperConfig]) extends Plugin {

  def apply(basePitchClass: PitchClass, scale: Scale[Interval]): PartialTuning
}

/** Marker trait for all configurations used to instantiate a
  * [[org.calinburloiu.music.tuning.TuningMapper]] implementation.
  */
trait TuningMapperConfig extends PluginConfig

class TuningMapperException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
