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
 * A [[TuningChanger]] decision that controls to which [[org.calinburloiu.music.microtonalist.composition.Tuning]]
 * from the tuning list should the [[Tuner]] tune.
 */
sealed trait TuningChange {
  def isChanging: Boolean
}

/**
 * A [[TuningChanger]] decision to not change the tuning.
 */
case object NoTuningChange extends TuningChange {
  override def isChanging: Boolean = false
}

case object PreviousTuningChange extends TuningChange {
  override def isChanging: Boolean = true
}

case object NextTuningChange extends TuningChange {
  override def isChanging: Boolean = true
}

case class IndexTuningChange(index: Int) extends TuningChange {
  require(index >= 0, "Tuning index must be equal or greater than 0!")

  override def isChanging: Boolean = true
}
