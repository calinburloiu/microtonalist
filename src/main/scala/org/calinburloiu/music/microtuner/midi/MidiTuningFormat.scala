package org.calinburloiu.music.microtuner.midi

import enumeratum.{Enum, EnumEntry}

import scala.collection.immutable

sealed abstract class MidiTuningFormat(val messageGenerator: MidiTuningMessageGenerator) extends EnumEntry

object MidiTuningFormat extends Enum[MidiTuningFormat] {
  override val values: immutable.IndexedSeq[MidiTuningFormat] = findValues

  case object NonRealTime1BOctave extends MidiTuningFormat(MidiTuningMessageGenerator.NonRealTime1BOctave)
}
