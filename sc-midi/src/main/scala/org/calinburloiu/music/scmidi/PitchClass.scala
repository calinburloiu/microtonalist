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

package org.calinburloiu.music.scmidi

import scala.language.implicitConversions
import scala.util.Try

case class PitchClass private(number: Int) extends AnyVal {
  /**
   * Call this method after creating an instance.
   *
   * Context: Scala value classes do not allow constructor validation.
   */
  def assertValid(): this.type = {
    require(number >= 0 && number < 12, "0 <= pitchClass < 12")
    this
  }

  override def toString: String = PitchClass.nameOf(number)
}

object PitchClass {
  val C: PitchClass = fromNumber(0)
  val CSharp: PitchClass = fromNumber(1)
  val DFlat: PitchClass = fromNumber(1)
  val D: PitchClass = fromNumber(2)
  val DSharp: PitchClass = fromNumber(3)
  val EFlat: PitchClass = fromNumber(3)
  val E: PitchClass = fromNumber(4)
  val F: PitchClass = fromNumber(5)
  val FSharp: PitchClass = fromNumber(6)
  val GFlat: PitchClass = fromNumber(6)
  val G: PitchClass = fromNumber(7)
  val GSharp: PitchClass = fromNumber(8)
  val AFlat: PitchClass = fromNumber(8)
  val A: PitchClass = fromNumber(9)
  val ASharp: PitchClass = fromNumber(10)
  val BFlat: PitchClass = fromNumber(10)
  val B: PitchClass = fromNumber(11)

  val values: Seq[PitchClass] = Seq(C, CSharp, D, DSharp, E, F, FSharp, G, GSharp, A, ASharp, B)

  private val outputNoteNames: Seq[String] =
    Seq("C", "C♯/D♭", "D", "D♯/E♭", "E", "F", "F♯/G♭", "G", "G♯/A♭", "A", "A♯/B♭", "B")
  private val noteNameToNumber: Map[String, Int] = outputNoteNames.zipWithIndex.toMap ++ Map(
    ("B♯", 0), ("C♯", 1), ("D♭", 1), ("D♯", 3), ("E♭", 3), ("F♭", 4), ("E♯", 5), ("F♯", 6), ("G♭", 6),
    ("G♯", 8), ("A♭", 8), ("A♯", 10), ("B♭", 10), ("C♭", 11),
    ("D♭/C♯", 1), ("E♭/D♯", 3), ("G♭/F♯", 6), ("A♭/G♯", 8), ("B♭/A♯", 10)
  )

  def fromNumber(n: Int): PitchClass = {
    val result = PitchClass(n)
    result.assertValid()
    result
  }

  implicit def toNumber(pitchClass: PitchClass): Int = pitchClass.number

  def fromName(name: String): Option[PitchClass] = {
    val canonicalName = name.replace('#', '♯').replaceAll("([A-Ga-g])(b)", "$1♭").toUpperCase
    noteNameToNumber.get(canonicalName).map(fromNumber)
  }

  def parse(string: String): Option[PitchClass] = {
    val parsedFromNumberString = for (
      number <- Try(Integer.parseInt(string)).toOption;
      pitchClass <- Try(fromNumber(number)).toOption
    ) yield pitchClass

    parsedFromNumberString orElse fromName(string)
  }

  def nameOf(n: Int): String = outputNoteNames(n)
}
