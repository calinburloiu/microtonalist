package org.calinburloiu.music.microtuner.midi

import javax.sound.midi.MidiMessage

trait MidiProcessor {
  def processMessage(message: MidiMessage, timeStamp: Long): Seq[MidiMessage]
}
