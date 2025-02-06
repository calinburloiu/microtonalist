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

/**
 * Represents a configuration to define triggers for [[TuningChange]]s.
 *
 * @tparam T The type of the trigger used to identify tuning changes.
 * @param previous Configures the trigger for changing to the previous tuning.
 * @param next     Configures the trigger for changing to the next tuning.
 * @param index    A map that configures the triggers as values for changing to a given tuning index as key.
 * @throws IllegalArgumentException if no trigger is defined for any of previous, next, or index.
 * @throws IllegalArgumentException if any index key is less than 0.
 */
case class TuningChangeTriggers[T](previous: Option[T] = None,
                                   next: Option[T] = None,
                                   index: Map[Int, T] = Map.empty[Int, T]) {
  require(next.size + previous.size + index.size > 0, "At least one trigger must be defined!")
  require(index.keys.forall(_ >= 0), "index must be equal or greater than 0!")

  private lazy val invertedIndex: Map[T, Int] = index.map(_.swap)

  /**
   * Checks if there's a trigger configured for changing to the previous tuning.
   *
   * @param trigger The trigger to be checked.
   * @return `true` if the given trigger matches the `previous` trigger, `false` otherwise.
   */
  def hasPreviousWithTrigger(trigger: T): Boolean = previous.contains(trigger)

  /**
   * Checks if there's a trigger configured for changing to the next tuning.
   *
   * @param trigger The trigger to be checked.
   * @return `true` if the given trigger matches the `next` trigger, `false` otherwise.
   */
  def hasNextWithTrigger(trigger: T): Boolean = next.contains(trigger)

  /**
   * Checks if there's a trigger configured for changing to a specific tuning index.
   *
   * @param trigger The trigger to be checked.
   * @return `true` if the given trigger matches any of the configured tuning index triggers, `false` otherwise.
   */
  def hasIndexWithTrigger(trigger: T): Boolean = invertedIndex.contains(trigger)

  /**
   * Retrieves the tuning index of a given trigger.
   *
   * @param trigger The trigger whose tuning index is to be determined.
   * @return The tuning index of the trigger.
   * @throws NoSuchElementException if there is no tuning index configured for the given trigger.
   * @see [[hasIndexWithTrigger]] for avoiding the exception.
   */
  def indexOfTrigger(trigger: T): Int = invertedIndex(trigger)

  /**
   * Checks if a trigger is configured for any tuning change operation (e.g., next, previous, specific index).
   *
   * @param trigger The trigger to be checked.
   * @return `true` if the trigger matches any of the next, previous, or specific index triggers, `false` otherwise.
   */
  def hasTrigger(trigger: T): Boolean = hasNextWithTrigger(trigger) || hasPreviousWithTrigger(trigger) ||
    hasIndexWithTrigger(trigger)

  /**
   * Determines the appropriate [[TuningChange]] operation based on the provided trigger.
   *
   * @param trigger The trigger used to determine the tuning change operation.
   * @return A `TuningChange` object representing the determined operation. If no operation is configured for the
   *         given trigger, [[NoTuningChange]] is returned.
   */
  def tuningChangeForTrigger(trigger: T): TuningChange = {
    if (hasPreviousWithTrigger(trigger)) {
      PreviousTuningChange
    } else if (hasNextWithTrigger(trigger)) {
      NextTuningChange
    } else if (hasIndexWithTrigger(trigger)) {
      IndexTuningChange(indexOfTrigger(trigger))
    } else {
      NoTuningChange
    }
  }
}
