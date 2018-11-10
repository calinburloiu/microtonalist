package org.calinburloiu.music.tuning

import org.calinburloiu.music.plugin.{Plugin, PluginConfig}

abstract class TuningListReducer(val config: Option[TuningListReducerConfig]) extends Plugin {

  def apply(partialTuningList: PartialTuningList): TuningList
}

trait TuningListReducerConfig extends PluginConfig

class TuningListReducerException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
