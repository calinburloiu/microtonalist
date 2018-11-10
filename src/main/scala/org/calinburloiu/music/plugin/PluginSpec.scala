package org.calinburloiu.music.plugin

case class PluginSpec[C <: PluginConfig](
  id: String,
  config: C
)
