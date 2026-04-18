/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.scmidi.message

/**
 * MIDI value validation and requirement utility.
 */
object MidiRequirements {
  /** The minimum signed 14-bit value (-8192). */
  val MinSigned14BitValue: Int = -(1 << 13)
  /** The maximum signed 14-bit value (8191). */
  val MaxSigned14BitValue: Int = (1 << 13) - 1

  /** Requires that the given channel is between 0 and 15. */
  def requireChannel(channel: Int): Unit =
    require((channel & 0xFFFFFFF0) == 0, s"channel must be between 0 and 15; got $channel")

  /** Requires that the given value is an unsigned 7-bit integer (0 to 127). */
  def requireUnsigned7BitValue(name: String, value: Int): Unit =
    require((value & 0xFFFFFF80) == 0, s"$name must be between 0 and 127; got $value")

  /** Requires that the given value is a signed 14-bit integer (-8192 to 8191). */
  def requireSigned14BitValue(name: String, value: Int): Unit = {
    require(
      value >= MinSigned14BitValue && value <= MaxSigned14BitValue,
      s"$name must be between $MinSigned14BitValue and $MaxSigned14BitValue; got $value"
    )
  }
}
