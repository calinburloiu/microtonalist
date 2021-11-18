/*
 * Copyright 2021 Calin-Andrei Burloiu
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

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.language.implicitConversions

package object midi {
  implicit class MidiNote(val number: Int) extends AnyVal {
    /**
     * Call this method after creating an instance.
     *
     * Context: Scala value classes do not allow constructor validation.
     */
    def assert(): Unit = MidiRequirements.requireUnsigned7BitValue("MidiNote#number", number)

    def pitchClassNumber: Int = number % 12

    def freq: Double = Math.pow(2, (pitchClassNumber - 69) / 12.0) * 440.0
  }

  def mapShortMessageChannel(shortMessage: ShortMessage, map: Int => Int): ShortMessage = {
    new ShortMessage(shortMessage.getCommand, map(shortMessage.getChannel), shortMessage.getData1, shortMessage.getData2)
  }

  def mapShortMessageChannel(message: MidiMessage, map: Int => Int): MidiMessage = message match {
    case shortMessage: ShortMessage => mapShortMessageChannel(shortMessage, map)
    case _ => message
  }

  def clampValue(value: Int, min: Int, max: Int): Int = Math.max(min, Math.min(max, value))
}
