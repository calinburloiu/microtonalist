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
 * An object describing an operation that controls to which [[org.calinburloiu.music.microtonalist.composition.Tuning]]
 * from the tuning sequence should the [[Tuner]] tune.
 *
 * @see [[TuningChanger]] which returns such an object as a decision based on the input MIDI received.
 */
sealed trait TuningChange {
  /**
   * @return whether this operation object is an effective tuning change which produces an effect, or not.
   * @see [[NoTuningChange]] as an example of operation with no effect.
   */
  def isChanging: Boolean
}

/**
 * Describes an operation that does not change the tuning (NO-OP).
 */
case object NoTuningChange extends TuningChange {
  override def isChanging: Boolean = false
}

/**
 * Describes an operation that changes to the previous tuning from the tuning sequence.
 */
case object PreviousTuningChange extends TuningChange {
  override def isChanging: Boolean = true
}


/**
 * Describes an operation that changes to the next tuning from the tuning sequence.
 */
case object NextTuningChange extends TuningChange {
  override def isChanging: Boolean = true
}


/**
 * Describes an operation that changes to a specific tuning index from the tuning sequence.
 *
 * @param index the index of the tuning in the tuning sequence to switch to. Must be >= 0.
 * @throws IllegalArgumentException if the index is less than 0.
 */
case class IndexTuningChange(index: Int) extends TuningChange {
  require(index >= 0, "Tuning index must be equal or greater than 0!")

  override def isChanging: Boolean = true
}
