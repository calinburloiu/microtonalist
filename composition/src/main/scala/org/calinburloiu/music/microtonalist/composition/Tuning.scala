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

import com.google.common.base.Preconditions.checkElementIndex
import com.google.common.math.DoubleMath
import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.music.scmidi.PitchClass

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq

/**
 * An incomplete tuning of a scale, that has missing deviations for some keys. Check [[Tuning]] for more details.
 *
 * Partial tunings are typically merged into a final tuning.
 *
 * @param offsetOptions `Some` tuning offset in cents for each key or `None` if the key is missing an offset value.
 */
case class Tuning(name: String, offsetOptions: Seq[Option[Double]]) extends Iterable[Option[Double]] with
  LazyLogging {
  require(offsetOptions.size == 12,
    s"There should be exactly 12 deviations corresponding to the 12 pitch classes, but found ${offsetOptions.size}!")

  import Tuning._


  /**
   * Retrieves the deviation in cents for the specified pitch class number.
   * If no deviation is defined for the provided pitch class number, returns a default value of 0.0.
   *
   * @param pitchClassNumber The 0-based index of the pitch class number whose deviation is to be retrieved.
   * @return the deviation in cents for the specified pitch class number, or 0.0 if no deviation is defined.
   */
  def apply(pitchClassNumber: Int): Double = {
    checkElementIndex(pitchClassNumber, size)
    offsetOptions(pitchClassNumber).getOrElse(0.0)
  }

  /**
   * Retrieves the deviation in cents for the specified pitch class number as an optional value, returning [[Some]]
   * value if there is a tuning defined for that pitch class or [[None]] if there isn't.
   *
   * @param pitchClassNumber The 0-based index of the pitch class number whose deviation is to be retrieved.
   * @return a [[Some]] containing the deviation in cents for the specified pitch class number, or `None` if no
   *         deviation is defined.
   */
  def get(pitchClassNumber: Int): Option[Double] = {
    checkElementIndex(pitchClassNumber, size)
    offsetOptions(pitchClassNumber)
  }

  def c: Double = apply(0)

  def cSharp: Double = apply(1)

  def dFlat: Double = cSharp

  def d: Double = apply(2)

  def dSharp: Double = apply(3)

  def eFlat: Double = dSharp

  def e: Double = apply(4)

  def f: Double = apply(5)

  def fSharp: Double = apply(6)

  def gFlat: Double = fSharp

  def g: Double = apply(7)

  def gSharp: Double = apply(8)

  def aFlat: Double = gSharp

  def a: Double = apply(9)

  def aSharp: Double = apply(10)

  def bFlat: Double = aSharp

  def b: Double = apply(11)

  /**
   * @return a sequence of tuning offsets in cents for all pitch classes, with missing values defaulted to 0.0.
   */
  def offsets: Seq[Double] = offsetOptions.map(_.getOrElse(0.0))

  /**
   * @return the size of the incomplete tuning
   */
  override def size: Int = offsetOptions.size

  override def iterator: Iterator[Option[Double]] = offsetOptions.iterator

  /**
   * @return `true` if deviations are available for all keys or `false` otherwise
   */
  def isComplete: Boolean = offsetOptions.forall(_.nonEmpty)

  /**
   * @return the number of completed pitch classes which have a deviation defined
   */
  def completedCount: Int = offsetOptions.map(d => if (d.isDefined) 1 else 0).sum

  /**
   * Creates an [[Tuning]] from this partial tuning.
   *
   * If it is incomplete (see [[isComplete]]), then, pitch classes without a value are mapped to the standard 12-EDO
   * tuning.
   *
   * @return a new [[Tuning]].
   * @see [[isComplete]]
   */
  def resolve: Tuning = Tuning(name, offsetOptions.map { v => Some(v.getOrElse(0.0)) })

  /**
   * Fills each key with empty deviations from `this` with corresponding non-empty
   * deviations from `that`.
   */
  def fill(that: Tuning): Tuning = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    val resultDeviations = (this.offsetOptions zip that.offsetOptions).map {
      case (thisDeviation, thatDeviation) => thisDeviation.orElse(thatDeviation)
    }

    Tuning(name, resultDeviations)
  }

  /**
   * Overwrites each key from `this` with with corresponding non-empty deviations from `that`.
   */
  def overwrite(that: Tuning): Tuning = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    val resultDeviations = (this.offsetOptions zip that.offsetOptions).map {
      case (thisDeviation, thatDeviation) => (thisDeviation ++ thatDeviation).lastOption
    }

    Tuning(name, resultDeviations)
  }

  /**
   * Merges `this` partial tuning with another into a new partial tuning by complementing the corresponding keys.
   *
   * The following cases apply:
   *
   *   - If one has a deviation and the other does not for a key, the deviation of the former is used.
   *   - If none have a deviation, the resulting key will continue to be empty.
   *   - If both have the same deviation for a key (equal within the given tolerance), that key will use that deviation.
   *   - If both have deviations for a key, and they are not equal, it is said that there is a ''conflict'' and
   *     `None` is returned.
   *
   * @param that      other partial tuning used for merging
   * @param tolerance maximum error tolerance in cents when comparing two correspondent deviations for equality
   * @return `Some` new partial tuning if the merge was successful, or `None` if there was a conflict.
   */
  def merge(that: Tuning, tolerance: Double = DefaultCentsTolerance): Option[Tuning] = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    def mergeName(leftName: String, rightName: String): String = {
      if (leftName.isEmpty) {
        rightName
      } else if (rightName.isEmpty) {
        leftName
      } else {
        s"$leftName + $rightName"
      }
    }

    @tailrec
    def accMerge(acc: Array[Option[Double]], index: Int): Option[Tuning] = {
      if (index == size) {
        Some(Tuning(mergeName(this.name, that.name), ArraySeq.unsafeWrapArray(acc)))
      } else {
        (this.offsetOptions(index), that.offsetOptions(index)) match {
          case (None, None) =>
            acc(index) = None
            accMerge(acc, index + 1)

          case (None, Some(dev2)) =>
            acc(index) = Some(dev2)
            accMerge(acc, index + 1)

          case (Some(dev1), None) =>
            acc(index) = Some(dev1)
            accMerge(acc, index + 1)

          case (Some(dev1), Some(dev2)) if DoubleMath.fuzzyEquals(dev1, dev2, tolerance) =>
            acc(index) = Some(dev1)
            accMerge(acc, index + 1)

          // Conflict, stop!
          case _ =>
            logger.debug(s"Conflict for pitch class ${PitchClass.fromNumber(index)} in PartialTunings $this and $that")
            None
        }
      }
    }

    val emptyAcc = new Array[Option[Double]](size)

    accMerge(emptyAcc, 0)
  }

  /**
   * Checks if this [[Tuning]] has the deviations equal within an error tolerance with the given
   * [[Tuning]]. Other properties are ignored in the comparison.
   *
   * @param that           The partial tuning to compare with.
   * @param centsTolerance Error tolerance in cents.
   * @return true if the partial tunings are almost equal, or false otherwise.
   */
  def almostEquals(that: Tuning, centsTolerance: Double): Boolean = {
    (this.offsetOptions zip that.offsetOptions).forall {
      case (None, None) => true
      case (Some(d1), Some(d2)) => DoubleMath.fuzzyEquals(d1, d2, centsTolerance)
      case _ => false
    }
  }

  override def toString: String = {
    val deviationsAsString = offsetOptions.map(fromDeviationToString)

    val notesWithDeviations = (PitchClass.noteNames zip deviationsAsString).map {
      case (noteName, deviationString) => s"$noteName = ${deviationString.trim}"
    }.mkString(", ")

    s""""$name" ($notesWithDeviations)"""
  }

  def toPianoKeyboardString: String = {
    def padDeviation(deviation: Option[Double]) = fromDeviationToString(deviation).padTo(12, ' ')

    val missingKeySpace = " " * 6

    val blackKeysString =
      Seq(Some(get(1)), Some(get(3)), None, None, Some(get(6)), Some(get(8)), Some(get(10))).map {
        case Some(deviation) => padDeviation(deviation)
        case None => missingKeySpace
      }.mkString("")
    val whiteKeysString = Seq(get(0), get(2), get(4), get(5), get(7), get(9), get(10)).map(padDeviation).mkString("")

    s"$missingKeySpace$blackKeysString\n$whiteKeysString"
  }

  def unfilledPitchClassesString: String = {
    val pitches = offsetOptions.zipWithIndex.map {
      case (None, index) =>
        Some(PitchClass.nameOf(index))
      case _ => None
    }.filter(_.nonEmpty).map(_.get)
    s"\"$name\": ${pitches.mkString(", ")}"
  }
}

object Tuning {

  /**
   * Represents the size of the tuning, referring to the number of pitch classes in an octave.
   */
  val Size: Int = 12

  /**
   * A [[Tuning]] with 12 keys and no deviations completed.
   */
  val Empty: Tuning = empty(Size)

  /**
   * A [[Tuning]] with 12 keys and all 0 deviations for the standard 12-tone equal temperament.
   */
  val Standard: Tuning = fill(0, Size)

  def apply(deviations: Seq[Option[Double]]): Tuning = Tuning("", deviations)

  /**
   * Creates a named [[Tuning]] for the 12 pitch classes in an octave.
   */
  def apply(name: String = "",
            c: Option[Double] = None,
            cSharpOrDFlat: Option[Double] = None,
            d: Option[Double] = None,
            dSharpOrEFlat: Option[Double] = None,
            e: Option[Double] = None,
            f: Option[Double] = None,
            fSharpOrGFlat: Option[Double] = None,
            g: Option[Double] = None,
            gSharpOrAFlat: Option[Double] = None,
            a: Option[Double] = None,
            aSharpOrBFlat: Option[Double] = None,
            b: Option[Double] = None): Tuning = {
    val deviations = Seq(c, cSharpOrDFlat, d, dSharpOrEFlat, e, f, fSharpOrGFlat, g,
      gSharpOrAFlat, a, aSharpOrBFlat, b)
    Tuning(name, deviations)
  }

  /**
   * Creates a [[Tuning]] for the 12 pitch classes in an octave with an empty name.
   */
  def apply(c: Option[Double],
            cSharpOrDFlat: Option[Double],
            d: Option[Double],
            dSharpOrEFlat: Option[Double],
            e: Option[Double],
            f: Option[Double],
            fSharpOrGFlat: Option[Double],
            g: Option[Double],
            gSharpOrAFlat: Option[Double],
            a: Option[Double],
            aSharpOrBFlat: Option[Double],
            b: Option[Double]): Tuning = {
    apply("", c, cSharpOrDFlat, d, dSharpOrEFlat, e, f, fSharpOrGFlat, g, gSharpOrAFlat, a, aSharpOrBFlat, b)
  }

  /**
   * Creates a named [[Tuning]] for the 12 pitch classes in an octave when each tuning value is defined.
   */
  def apply(name: String,
            c: Double,
            cSharpOrDFlat: Double,
            d: Double,
            dSharpOrEFlat: Double,
            e: Double,
            f: Double,
            fSharpOrGFlat: Double,
            g: Double,
            gSharpOrAFlat: Double,
            a: Double,
            aSharpOrBFlat: Double,
            b: Double): Tuning = {
    Tuning(name, Seq(Some(c), Some(cSharpOrDFlat), Some(d), Some(dSharpOrEFlat), Some(e), Some(f),
      Some(fSharpOrGFlat), Some(g), Some(gSharpOrAFlat), Some(a), Some(aSharpOrBFlat), Some(b)))
  }

  def fromOffsets(name: String, offsets: Seq[Double]): Tuning = Tuning(name, offsets.map(Some(_)))

  /**
   * Creates a [[Tuning]] which has no deviation in each of its keys.
   *
   * @param size the number of keys in the partial tuning
   * @return a new partial tuning
   */
  def empty(size: Int): Tuning = Tuning(Seq.fill(size)(None))

  def fill(deviation: Double, size: Int): Tuning = Tuning(Seq.fill(size)(Some(deviation)))

  def fromDeviationToString(deviation: Double): String = f"$deviation%+06.2f"

  def fromDeviationToString(deviation: Option[Double]): String = deviation.fold("  --  ")(fromDeviationToString)
}
