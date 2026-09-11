/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.scmidi.javamidi

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.{Businessync, BusinessyncEvent}

import scala.collection.mutable

/**
 * A [[Businessync]] that records the events published through it instead of posting them, so that a test can assert
 * on the exact sequence a component publishes. It is not thread-safe; tests publish from a single thread.
 */
class RecordingBusinessync extends Businessync(EventBus()) {
  private val _events: mutable.Buffer[BusinessyncEvent] = mutable.ArrayBuffer()

  /** The events published so far, in order. */
  def events: Seq[BusinessyncEvent] = _events.toSeq

  override def publish(event: BusinessyncEvent): Unit = {
    _events += event
  }
}
