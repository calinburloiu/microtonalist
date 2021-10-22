package org.calinburloiu.music.microtuner.midi

case class PitchBendSensitivity(semitones: Int, cents: Int = 0) {
  require(semitones >= 0 && semitones < 128, "semitones should be an unsigned 7-bit value")
  require(cents >= 0 && cents < 128, "cents should be an unsigned 7-bit value")

  val totalCents: Int = 100 * semitones + cents
}
