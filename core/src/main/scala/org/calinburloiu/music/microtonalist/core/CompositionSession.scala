/*
 * Copyright 2024 Calin-Andrei Burloiu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.calinburloiu.music.microtonalist.core

import com.google.common.eventbus.Subscribe
import org.calinburloiu.music.microtonalist.sync.MicrotonalistEvent

import java.net.URI

case object ReloadEvent extends MicrotonalistEvent

case class CompositionSession(uri: URI, compositionRepo: CompositionRepo) {
  load()

  private var _composition: Composition = _

  private def load(): Unit = {
    _composition = compositionRepo.read(uri)
  }

  def composition: Composition = _composition

  def reload(): Unit = load()

  @Subscribe
  def onReload(event: ReloadEvent.type): Unit = {
    reload()
  }
}
