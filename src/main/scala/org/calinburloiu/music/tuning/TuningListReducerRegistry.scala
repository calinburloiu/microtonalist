package org.calinburloiu.music.tuning

import org.calinburloiu.music.plugin.{PluginFactory, PluginRegistry}

class TuningListReducerRegistry extends PluginRegistry[TuningListReducer] {

  override def registeredPluginFactories: Seq[PluginFactory[TuningListReducer]] = Seq(
    new DirectTuningListReducerFactory,
    new MergeTuningListReducerFactory
  )
}
