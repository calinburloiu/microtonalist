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

import org.calinburloiu.businessync.BusinessyncEvent
import org.calinburloiu.music.microtonalist.composition.Tuning

/**
 * Base class for all events published by [[TuningSession]].
 */
sealed abstract class TuningSessionEvent extends BusinessyncEvent {
  /**
   * Identifier representing the index (0-based) within the sequence of tunings related to the tuning session.
   */
  val tuningIndex: Int

  /**
   * The current tuning object describing the tuning that should be applied during this event.
   */
  val currentTuning: Tuning
}

/**
 * Event representing an update in the tuning index within a tuning session. This event specifies the
 * new tuning index and the corresponding tuning that is currently being applied.
 *
 * @param tuningIndex   Identifier representing the index within the sequence of tunings related to the session.
 * @param currentTuning The current tuning object, describing the pitch class deviations in cents for
 *                      the specified musical tuning.
 */
case class TuningIndexUpdatedEvent(override val tuningIndex: Int,
                                   override val currentTuning: Tuning) extends TuningSessionEvent

/**
 * Event representing an update in the sequence of tunings during a tuning session.
 *
 * Note that updating tuning sequence might also affect the index when the new list is smaller that the index.
 *
 * This event is emitted to notify listeners of changes in the available tunings as part of a tuning session.
 * The `tuningIndex` must be within the bounds of the provided `tunings` sequence if the sequence is non-empty.
 * If the sequence is empty, a default value of `Tuning.Edo12` (12-tone equal temperament) will be used as
 * the `currentTuning`.
 *
 * @param tunings     The new tuning list.
 * @param tuningIndex Index of the currently selected tuning within the `tunings` sequence.
 */
case class TuningsUpdatedEvent(tunings: Seq[Tuning], override val tuningIndex: Int) extends TuningSessionEvent {
  require(tunings.isEmpty || 0 <= tuningIndex && tuningIndex < tunings.size,
    s"Tuning index $tuningIndex is out of bounds for tunings $tunings!")

  /**
   * The current tuning applied in the session. This is determined by the index of the selected tuning
   * within the `tunings` sequence. If the sequence is empty, the default tuning `Tuning.Edo12` is used instead.
   */
  override val currentTuning: Tuning = tunings.lift(tuningIndex).getOrElse(Tuning.Standard)
}
