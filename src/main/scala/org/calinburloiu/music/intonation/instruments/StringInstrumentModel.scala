package org.calinburloiu.music.intonation.instruments

import org.calinburloiu.music.intonation.Interval

class StringInstrumentModel(stringLength: Double) {

  def fretSize(base: Interval, intervalFromBase: Interval): Double = {
    base.toStringLengths.realValue * (1.0 - intervalFromBase.toStringLengths.realValue) * stringLength
  }
}
