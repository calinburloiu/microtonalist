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
  def isTriggering: Boolean

  /**
   * Tells whether this operation object has the potential to trigger a tuning change but may not necessarily do so.
   * See [[isTriggering]] which tells for certain.
   *
   * @return whether this operation object has the potential to trigger a tuning change, or not.
   */
  def mayTrigger: Boolean = isTriggering
}

/**
 * A tuning change operation that can trigger an actual tuning change.
 */
sealed trait EffectiveTuningChange extends TuningChange {
  override def isTriggering: Boolean = true
}

/**
 * Trait for all tuning change operations that cannot produce an effect on the tuning.
 */
sealed trait IneffectiveTuningChange extends TuningChange {
  override def isTriggering: Boolean = false
}

/**
 * Describes an operation that does not change the tuning (NO-OP).
 */
case object NoTuningChange extends IneffectiveTuningChange

/**
 * Describes an operation that does not trigger a tuning change, but the MIDI message that caused the production of
 * this operation via
 * [[org.calinburloiu.music.microtonalist.tuner.TuningChanger#decide(javax.sound.midi.MidiMessage)]] it part of a
 * series/pattern that may eventually trigger an effective tuning change.
 *
 * For example, if a piano pedal is used as tuning change trigger, depressing it will emit a continuous
 * stream of CC messages, but only for one of them a tuning change is triggered, for the rest this operation is emitted.
 */
case object MayTriggerTuningChange extends IneffectiveTuningChange {
  override def mayTrigger: Boolean = true
}

/**
 * Describes an operation that changes to the previous tuning from the tuning sequence. If the current tuning is the
 * first one, it wraps around to the last tuning.
 */
case object PreviousTuningChange extends EffectiveTuningChange


/**
 * Describes an operation that changes to the next tuning from the tuning sequence. If the current tuning is the last
 * one, it wraps around to the first tuning.
 */
case object NextTuningChange extends EffectiveTuningChange


/**
 * Describes an operation that changes to a specific tuning index from the tuning sequence.
 *
 * @param index the index of the tuning in the tuning sequence to switch to. Must be >= 0.
 * @throws IllegalArgumentException if the index is less than 0.
 */
case class IndexTuningChange(index: Int) extends EffectiveTuningChange {
  require(index >= 0, "Tuning index must be equal or greater than 0!")
}
