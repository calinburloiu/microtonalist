package org.calinburloiu.music.microtuner

import scala.language.implicitConversions

package object midi {
  implicit class MidiNote(val number: Int) extends AnyVal {
    def toPitchClassNumber: Int = number % 12
  }

//  implicit def fromNumberToMidiNote(number: Int): MidiNote = new MidiNote(number)
}
