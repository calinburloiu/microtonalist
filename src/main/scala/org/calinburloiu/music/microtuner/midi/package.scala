package org.calinburloiu.music.microtuner

import scala.language.implicitConversions

package object midi {
  implicit class MidiNote(val number: Int) extends AnyVal {
    MidiRequirements.requireUnsigned7BitValue("MIDI note number", number)

    def toPitchClassNumber: Int = number % 12
  }
}
