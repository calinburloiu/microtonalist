package org.calinburloiu.music.plugin

/** Marker trait for all configurations used to instantiate a plugin implementation. */
trait PluginConfig {

  override def hashCode(): Int

  override def equals(obj: Any): Boolean
}
