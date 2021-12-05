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

package org.calinburloiu.music

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.language.implicitConversions

package object scmidi {
  /** Concert pitch frequency in Hz for central A4. */
  val ConcertPitchFreq: Double = 440.0

  implicit class MidiNote(val number: Int) extends AnyVal {
    /**
     * Call this method after creating an instance.
     *
     * Context: Scala value classes do not allow constructor validation.
     */
    def assertValid(): Unit = MidiRequirements.requireUnsigned7BitValue("MidiNote#number", number)

    def pitchClass: PitchClass = PitchClass.fromInt(number % 12)

    def octave: Int = number / 12 - 1

    def freq: Double = ConcertPitchFreq * Math.pow(2, (number - MidiNote.ConcertPitch) / 12.0)
  }

  object MidiNote {
    val Lowest: Int = 0
    val Highest: Int = 127

    val C4: Int = 60
    val CSharp4: Int = 61
    val DFlat4: Int = 61
    val D4: Int = 62
    val DSharp4: Int = 63
    val EFlat4: Int = 63
    val E4: Int = 64
    val F4: Int = 65
    val FSharp4: Int = 66
    val GFlat4: Int = 66
    val G4: Int = 67
    val GSharp4: Int = 68
    val AFlat4: Int = 68
    val A4: Int = 69
    val ASharp4: Int = 70
    val BFlat4: Int = 70
    val B4: Int = 71
    val C5: Int = 72

    val ConcertPitch: Int = 69

    def apply(pitchClass: PitchClass, octave: Int): MidiNote = {
      require(octave >= -1 && (octave < 9 || octave == 9 && pitchClass <= 7),
        "octave must be in range -1 to 10, but octave 10 only goes until G")

      12 * (octave + 1) + pitchClass
    }

    implicit def toInt(midiNote: MidiNote): Int = midiNote.number
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
