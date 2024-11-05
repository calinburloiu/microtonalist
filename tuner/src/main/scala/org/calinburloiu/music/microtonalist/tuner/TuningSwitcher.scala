/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.tuner

import com.google.common.eventbus.EventBus
import com.google.common.math.IntMath
import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.music.microtonalist.core.{CompositionSession, OctaveTuning, TuningList}

// TODO #88 Is this a controller? Should we rename it?
/**
 * Class responsible to switch between tunings.
 * @param tuners Tuners for various output instruments called when the tuning is changed.
 * @param compositionSession
 * @param eventBus Event bus for sending events.
 */
class TuningSwitcher(val tuners: Seq[Tuner],
                     val compositionSession: CompositionSession,
                     eventBus: EventBus) extends LazyLogging {
  require(tuners.nonEmpty, "there should be at least one tuner")

  private[this] var _tuningIndex: Int = 0

  def apply(index: Int): Unit = {
    if (_tuningIndex > tuningList.tunings.size - 1) {
      throw new IllegalArgumentException(s"Expected tuning index to be between 0 and ${tuningList.tunings.size - 1}")
    } else if (index != _tuningIndex) {
      val oldTuningIndex = _tuningIndex
      _tuningIndex = index

      tune()
      eventBus.post(TuningChangedEvent(tuningIndex, oldTuningIndex))
    }
  }

  def prev(): Unit = {
    nextBy(-1)
  }

  def next(): Unit = {
    nextBy(1)
  }

  def nextBy(step: Int): Unit = {
    apply(IntMath.mod(tuningIndex + step, tuningCount))
  }

  def tuningIndex: Int = _tuningIndex

  def currentTuning: OctaveTuning = tuningList(_tuningIndex)

  def tuningCount: Int = tuningList.tunings.size

  def tune(): Unit = {
    try {
      tuners.foreach(_.tune(currentTuning))
    } catch {
      case e: TunerException => logger.error("Failed to switch tuning: " + e.getMessage)
      case e: IllegalStateException => logger.error("Failed to switch tuning: " + e.getMessage)
    }
  }

  def tuningList: TuningList = compositionSession.composition.tuningList
}
