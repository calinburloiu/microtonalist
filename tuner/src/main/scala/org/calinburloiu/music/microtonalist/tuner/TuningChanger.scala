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

import org.calinburloiu.music.microtonalist.common.Plugin

import javax.annotation.concurrent.NotThreadSafe
import javax.sound.midi.MidiMessage

/**
 * `TuningChanger` is an abstract class representing a pluggable component for determining a [[TuningChange]]
 * operation based on incoming MIDI messages. It is part of the `"tuningChanger"` plugin family and is responsible for
 * deciding how the tuning of an instrument should be modified.
 */
@NotThreadSafe
abstract class TuningChanger extends Plugin {
  override val familyName: String = TuningChanger.FamilyName

  /**
   * Determines the appropriate [[TuningChange]] operation based on the provided MIDI message.
   *
   * @param message The MIDI message to be processed for deciding the tuning change.
   * @return a [[TuningChange]] operation representing the operation to be performed
   */
  def decide(message: MidiMessage): TuningChange

  /**
   * Determines whether the given MIDI message has the potential to be a trigger for a tuning change.
   *
   * Note that the final decision belongs to the [[decide]]. There may be situations when for a MIDI message this
   * method returns true, but for [[decide]] it returns [[NoTuningChange]]. This typically happens when a stream of
   * messages should follow a certain pattern and only when the pattern is matched the effective [[TuningChange]] is
   * triggered. For example, if a piano pedal is used as tuning change trigger, depressing it will emit a continuous
   * stream of CC messages, but only for one of them a tuning change is triggered. This method is important for
   * `TuningChangeProcessor#triggersThru`.
   *
   * @param message The MIDI message to be evaluated as a potential trigger for a tuning change.
   * @return true if the trigger has the potential to trigger a tuning change, or false otherwise.
   */
  def mayTrigger(message: MidiMessage): Boolean

  /**
   * Resets the internal state of the `TuningChanger` to its default/initial configuration.
   */
  def reset(): Unit
}

object TuningChanger {
  val FamilyName: String = "tuningChanger"
}
