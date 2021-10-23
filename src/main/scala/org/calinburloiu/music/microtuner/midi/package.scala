package org.calinburloiu.music.microtuner

import scala.language.implicitConversions

package object midi {
  implicit class MidiNote(val number: Int) extends AnyVal {
    MidiRequirements.requireUnsigned7BitValue("MIDI note number", number)

    def pitchClassNumber: Int = number % 12
  }
}
