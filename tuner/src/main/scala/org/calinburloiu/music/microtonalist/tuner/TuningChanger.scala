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

  def triggersThru: Boolean

  /**
   * Determines the appropriate [[TuningChange]] operation based on the provided MIDI message.
   *
   * @param message The MIDI message to be processed for deciding the tuning change.
   * @return a [[TuningChange]] operation representing the operation to be performed
   */
  def decide(message: MidiMessage): TuningChange

  /**
   * Resets the internal state of the `TuningChanger` to its default/initial configuration.
   */
  def reset(): Unit
}

object TuningChanger {
  val FamilyName: String = "tuningChanger"
}
