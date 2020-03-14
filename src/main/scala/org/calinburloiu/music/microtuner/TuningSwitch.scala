/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner

import org.calinburloiu.music.tuning.{Tuning, TuningList}

class TuningSwitch(
  val tuner: Tuner,
  val tuningList: TuningList,
  initialPosition: Int = 0
) {

  private[this] var _currentPosition: Int = initialPosition

  tuner.tune(currentTuning)

  def apply(index: Int): Unit = {
    if (_currentPosition > tuningList.tunings.size - 1) {
      // TODO message
      throw new IllegalArgumentException()
    } else if (index != _currentPosition) {
      _currentPosition = index

      tuner.tune(currentTuning)
    }
  }

  def prev(): Unit = {
    // TODO Use Guava for mod with negatives: IntMath.mod
    if (_currentPosition > 0) {
      _currentPosition -= 1
    } else {
      _currentPosition = tuningList.tunings.size - 1
    }

    tuner.tune(currentTuning)
  }

  def next(): Unit = {
    _currentPosition = (_currentPosition + 1) % tuningList.tunings.size

    tuner.tune(currentTuning)
  }

  def currentPosition: Int = _currentPosition

  def currentTuning: Tuning = tuningList(_currentPosition)
}
