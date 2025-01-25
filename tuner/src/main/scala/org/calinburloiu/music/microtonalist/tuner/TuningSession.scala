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

  def tunings_=(value: Seq[OctaveTuning]): Unit = if (_tunings != value) {
    _tunings = value

    val oldTuningIndex = _tuningIndex
    _tuningIndex = Math.max(Math.min(_tuningIndex, value.size - 1), 0)

    publish(oldTuningIndex)
  }

  def tuningIndex: Int = _tuningIndex

  def tuningIndex_=(index: Int): Unit = {
    require(0 <= index && index < _tunings.size, s"Expecting tuning index to be between 0 and ${_tunings.size - 1}!")

    if (index != _tuningIndex) {
      val oldTuningIndex = _tuningIndex
      _tuningIndex = index

      publish(oldTuningIndex)
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

  private def publish(oldTuningIndex: Int): Unit = {
    businessync.publish(TuningChangedEvent(currentTuning, tuningIndex, oldTuningIndex))
  }
}
