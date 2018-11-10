package org.calinburloiu.music.tuning

import com.google.common.base.Preconditions._

object PianoKeyboardTuningUtils {

  val tuningSize: Int = 12

  val noteNames =
    Seq("C", "C#/Db", "D", "D#/Eb", "E", "F", "F#/Gb", "G", "G#/Ab", "A", "A#/Bb", "B")

  val minDeviation: Int = -64
  val maxDeviation: Int = 63

  private[PianoKeyboardTuningUtils] abstract class TuningBaseExtension[U](tuningBase: TuningBase[U]) {

    private[this] def checkIsPianoKeyboard(): Unit = checkArgument(tuningBase.size == 12,
      "Expecting 12 deviation values, but found %s", tuningBase.size)

    private[this] def pianoKeyboardTuningDeviation(index: Int): U = {
      checkIsPianoKeyboard()
      tuningBase.deviations(index)
    }

    def c: U = pianoKeyboardTuningDeviation(0)
    def cSharp: U = pianoKeyboardTuningDeviation(1)
    def dFlat: U = cSharp
    def d: U = pianoKeyboardTuningDeviation(2)
    def dSharp: U = pianoKeyboardTuningDeviation(3)
    def eFlat: U = dSharp
    def e: U = pianoKeyboardTuningDeviation(4)
    def f: U = pianoKeyboardTuningDeviation(5)
    def fSharp: U = pianoKeyboardTuningDeviation(6)
    def gFlat: U = fSharp
    def g: U = pianoKeyboardTuningDeviation(7)
    def gSharp: U = pianoKeyboardTuningDeviation(8)
    def aFlat: U = gSharp
    def a: U = pianoKeyboardTuningDeviation(9)
    def aSharp: U = pianoKeyboardTuningDeviation(10)
    def bFlat: U = aSharp
    def b: U = pianoKeyboardTuningDeviation(11)

    def toPianoKeyboardString: String = {
      checkIsPianoKeyboard()

      def padDeviation(deviation: U) = fromDeviationToString(deviation).padTo(12, ' ')
      val missingKeySpace = " " * 6

      val blackKeysString =
        Seq(Some(cSharp), Some(dSharp), None, None, Some(fSharp), Some(gSharp), Some(aSharp)).map {
          case Some(deviation) => padDeviation(deviation)
          case None => missingKeySpace
        }.mkString("")
      val whiteKeysString = Seq(c, d, e, f, g, a, b).map(padDeviation).mkString("")

      s"$missingKeySpace$blackKeysString\n$whiteKeysString"
    }

    def toNoteNamesString: String = {
      checkIsPianoKeyboard()

      val deviationsAsString = tuningBase.deviations.map(fromDeviationToString)

      (PianoKeyboardTuningUtils.noteNames zip deviationsAsString).map {
        case (noteName, deviation) => s"$noteName: $deviation"
      }.mkString(", ")
    }

    protected def fromDeviationToString(deviation: U): String
  }

  implicit class TuningExtension(tuning: Tuning) extends TuningBaseExtension(tuning) {

    override def toPianoKeyboardString: String = {
      val asciiPiano = super.toPianoKeyboardString
      s"${tuning.name}:\n$asciiPiano"
    }

    override protected def fromDeviationToString(deviation: Double) = f"$deviation%+06.2f"
  }

  implicit class PartialTuningExtension(partialTuning: PartialTuning)
      extends TuningBaseExtension(partialTuning) {

    override protected def fromDeviationToString(deviation: Option[Double]): String =
      deviation.fold("  --  ") { v =>
        f"$v%+06.2f"
      }
  }
}
