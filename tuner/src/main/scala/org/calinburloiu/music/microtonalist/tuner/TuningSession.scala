/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import com.google.common.math.IntMath
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.composition.OctaveTuning

class TuningSession(businessync: Businessync) {
  private var _tunings: Seq[OctaveTuning] = Seq()
  private var _tuningIndex: Int = 0

  def tunings: Seq[OctaveTuning] = _tunings

  def tunings_=(newTunings: Seq[OctaveTuning]): Unit = if (_tunings != newTunings) {
    _tunings = newTunings
    // Modifying _tuningIndex without calling its setter to avoid publishing a redundant TuningIndexUpdatedEvent
    _tuningIndex = Math.max(Math.min(_tuningIndex, newTunings.size - 1), 0)

    businessync publish TuningsUpdatedEvent(newTunings, _tuningIndex)
  }

  def tuningIndex: Int = _tuningIndex

  def tuningIndex_=(newIndex: Int): Unit = {
    require(0 <= newIndex && newIndex < _tunings.size,
      s"Tuning index $newIndex is out of bounds for tunings of size ${_tunings.size}!")

    if (newIndex != _tuningIndex) {
      _tuningIndex = newIndex

      businessync publish TuningIndexUpdatedEvent(newIndex, currentTuning)
    }
  }

  def currentTuning: OctaveTuning = tunings.lift(tuningIndex).getOrElse(OctaveTuning.Edo12)

  def tuningCount: Int = tunings.size

  def previousTuning(): Int = {
    nextBy(-1)
  }

  def nextTuning(): Int = {
    nextBy(1)
  }

  def nextBy(step: Int): Int = {
    tuningIndex = IntMath.mod(tuningIndex + step, tuningCount)
    _tuningIndex
  }
}
