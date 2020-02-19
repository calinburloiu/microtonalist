package org.calinburloiu.music.microtuner

object MidiTuningFormat extends Enumeration {
  protected case class Val(messageGenerator: MidiTuningMessageGenerator) extends super.Val
  import scala.language.implicitConversions
  implicit def valueToPlanetVal(x: Value): Val = x.asInstanceOf[Val]

  val NonRealTime1BOctave: Val = Val(NonRealTime1BOctaveMidiTuningMessageGenerator)
}
