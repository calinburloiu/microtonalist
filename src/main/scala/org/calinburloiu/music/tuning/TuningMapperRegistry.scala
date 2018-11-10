package org.calinburloiu.music.tuning

import org.calinburloiu.music.plugin.{PluginFactory, PluginRegistry}

class TuningMapperRegistry extends PluginRegistry[TuningMapper] {

  override lazy val registeredPluginFactories: Seq[PluginFactory[TuningMapper]] = Seq(
    new AutoTuningMapperFactory
  )
}
