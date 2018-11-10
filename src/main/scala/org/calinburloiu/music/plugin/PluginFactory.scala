package org.calinburloiu.music.plugin

trait PluginFactory[+P <: Plugin] {

  val pluginId: String

  val configClass: Option[Class[_ <: PluginConfig]]

  val defaultConfig: Option[PluginConfig]

  def create(config: Option[PluginConfig]): P
}
