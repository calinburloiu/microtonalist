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

import javax.annotation.concurrent.NotThreadSafe

/**
 * Manages the sequence of tunings and provides functionality to select one of them.
 *
 * This class should only be accessed from the business thread.
 *
 * @param businessync An instance of `Businessync` responsible for handling event publication.
 */
@NotThreadSafe
class TuningSession(businessync: Businessync) {
  private var _tunings: Seq[Tuning] = Seq()
  private var _tuningIndex: Int = 0

  /**
   * Retrieves the current sequence of tunings available within the tuning session.
   *
   * @return the current sequence of tunings.
   */
  def tunings: Seq[Tuning] = _tunings

  /**
   * Updates the sequence of tunings and ensures the [[tuningIndex]] remains valid within the new tunings.
   * If the sequence of tunings changes, a `TuningsUpdatedEvent` is published.
   *
   * @param newTunings the new sequence of tuning objects to replace the current tunings.
   */
  def tunings_=(newTunings: Seq[Tuning]): Unit = if (_tunings != newTunings) {
    _tunings = newTunings
    // Modifying _tuningIndex without calling its setter to avoid publishing a redundant TuningIndexUpdatedEvent
    _tuningIndex = Math.max(Math.min(_tuningIndex, newTunings.size - 1), 0)

    businessync publish TuningsUpdatedEvent(newTunings, _tuningIndex)
  }

  /**
   * Retrieves the current tuning index (0-based), which indicates the position of the
   * currently selected tuning within the sequence of tunings.
   *
   * @return the current tuning index.
   */
  def tuningIndex: Int = _tuningIndex

  /**
   * Sets the current tuning index (0-based) to the specified value and publishing a [[TuningIndexUpdatedEvent]] if the
   * index changes.
   *
   * @param newIndex the new tuning index to set; must be within the range `[0, tunings.size)`.
   */
  def tuningIndex_=(newIndex: Int): Unit = {
    require(newIndex >= 0, s"Tuning index must be positive, but got $newIndex!")

    val sanitizedIndex = Math.min(newIndex, tunings.size - 1)

    if (sanitizedIndex != _tuningIndex) {
      _tuningIndex = sanitizedIndex

      businessync publish TuningIndexUpdatedEvent(sanitizedIndex, currentTuning)
    }
  }

  /**
   * Retrieves the current tuning from the sequence of available tunings based on the current tuning index.
   * If the index is invalid or no tunings are defined, returns the default 12-tone equal temperament tuning.
   *
   * @return the currently selected tuning, or the default `12-EDO` tuning if the index is invalid or out of range.
   */
  def currentTuning: Tuning = tunings.lift(tuningIndex).getOrElse(Tuning.Standard)

  /**
   * Returns the number of tunings currently available in the session.
   *
   * @return the total count of tunings.
   */
  def tuningCount: Int = tunings.size

  /**
   * Selects the previous tuning in the sequence of available tunings. If the current tuning index is
   * at the beginning of the list, it wraps around to the last tuning.
   *
   * @return the updated tuning index after selecting the previous tuning.
   */
  def previousTuning(): Int = {
    nextBy(-1)
  }

  /**
   * Selects the next tuning in the sequence of available tunings. If the current tuning index
   * is at the end of the list, it wraps around to the first tuning.
   *
   * @return the updated tuning index after selecting the next tuning.
   */
  def nextTuning(): Int = {
    nextBy(1)
  }

  /**
   * Calculates and updates the tuning index by adding a specified step to the current tuning index and wrapping it
   * within the valid range of available tunings. Values may be negative to go backwards.
   *
   * @param step the number of steps to move the tuning index forward or backward.
   *             Positive values move forward, negative values move backward.
   * @return the updated tuning index after applying the specified step.
   */
  def nextBy(step: Int): Int = {
    tuningIndex = IntMath.mod(tuningIndex + step, tuningCount)
    _tuningIndex
  }
}
