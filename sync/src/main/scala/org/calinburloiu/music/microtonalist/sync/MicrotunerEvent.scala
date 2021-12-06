package org.calinburloiu.music.microtonalist.sync

/**
 * Base interface for events passed via Guava `EventBus`.
 */
trait MicrotunerEvent {
  def name: String = this.getClass.getSimpleName
}
