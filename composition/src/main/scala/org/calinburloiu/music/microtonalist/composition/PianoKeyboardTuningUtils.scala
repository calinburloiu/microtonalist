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

package org.calinburloiu.music.microtonalist.composition

// TODO Consider including this into Tuning classes
object PianoKeyboardTuningUtils {

  val tuningSize: Int = 12

  val noteNames: Seq[String] = Seq("C", "C♯/D♭", "D", "D♯/E♭", "E", "F", "F♯/G♭", "G", "G♯/A♭", "A", "A♯/B♭", "B")

  val minDeviation: Int = -64
  val maxDeviation: Int = 63

  private[PianoKeyboardTuningUtils] abstract class TuningBaseExtension[U](tuningBase: Tuning[U]) {

    private[this] def assertPianoKeyboard(): Unit = require(tuningBase.size == 12,
      s"Expecting 12 deviation values, but found ${tuningBase.size}")

    private[this] def pianoKeyboardTuningDeviation(index: Int): U = {
      assertPianoKeyboard()
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
      assertPianoKeyboard()

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
      assertPianoKeyboard()

      val deviationsAsString = tuningBase.deviations.map(fromDeviationToString)

      val notesWithDeviations = (PianoKeyboardTuningUtils.noteNames zip deviationsAsString).map {
        case (noteName, deviation) => s"$noteName = ${deviation.trim}"
      }.mkString(", ")

      s""""${tuningBase.name}" ($notesWithDeviations)"""
    }

    protected def fromDeviationToString(deviation: U): String
  }

  implicit class TuningExtension(tuning: OctaveTuning) extends TuningBaseExtension(tuning) {

    override def toPianoKeyboardString: String = {
      val asciiPiano = super.toPianoKeyboardString
      s"\"${tuning.name}\":\n$asciiPiano"
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
