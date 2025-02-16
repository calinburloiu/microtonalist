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
 * An incomplete tuning of a scale, that has missing deviations for some keys. Check [[OctaveTuning]] for more details.
 *
 * Partial tunings are typically merged into a final tuning.
 *
 * @param deviations `Some` deviation in cents for each key or `None` is the key is missing a deviation value
 */
case class PartialTuning(name: String, deviations: Seq[Option[Double]]) extends Iterable[Option[Double]] with
  LazyLogging {
  require(deviations.size == 12,
    s"There should be exactly 12 deviations corresponding to the 12 pitch classes, but found ${deviations.size}!")

  import PartialTuning._


  /**
   * Retrieves the deviation in cents for the specified pitch class number.
   * If no deviation is defined for the provided pitch class number, returns a default value of 0.0.
   *
   * @param pitchClassNumber The 0-based index of the pitch class number whose deviation is to be retrieved.
   * @return the deviation in cents for the specified pitch class number, or 0.0 if no deviation is defined.
   */
  def apply(pitchClassNumber: Int): Double = {
    checkElementIndex(pitchClassNumber, size)
    deviations(pitchClassNumber).getOrElse(0.0)
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
    deviations(pitchClassNumber)
  }

  def c: Option[Double] = get(0)

  def cSharp: Option[Double] = get(1)

  def dFlat: Option[Double] = cSharp

  def d: Option[Double] = get(2)

  def dSharp: Option[Double] = get(3)

  def eFlat: Option[Double] = dSharp

  def e: Option[Double] = get(4)

  def f: Option[Double] = get(5)

  def fSharp: Option[Double] = get(6)

  def gFlat: Option[Double] = fSharp

  def g: Option[Double] = get(7)

  def gSharp: Option[Double] = get(8)

  def aFlat: Option[Double] = gSharp

  def a: Option[Double] = get(9)

  def aSharp: Option[Double] = get(10)

  def bFlat: Option[Double] = aSharp

  def b: Option[Double] = get(11)

  /**
   * @return the size of the incomplete tuning
   */
  override def size: Int = deviations.size

  override def iterator: Iterator[Option[Double]] = deviations.iterator

  /**
   * @return `true` if deviations are available for all keys or `false` otherwise
   */
  def isComplete: Boolean = deviations.forall(_.nonEmpty)

  /**
   * @return the number of completed pitch classes which have a deviation defined
   */
  def completedCount: Int = deviations.map(d => if (d.isDefined) 1 else 0).sum

  /**
   * Creates an [[OctaveTuning]] from this partial tuning.
   *
   * If it is incomplete (see [[isComplete]]), then, pitch classes without a value are mapped to the standard 12-EDO
   * tuning.
   *
   * @return a new [[OctaveTuning]].
   * @see [[isComplete]]
   */
  def resolve: OctaveTuning = OctaveTuning(name, deviations.map(_.getOrElse(0.0)))

  /**
   * Fills each key with empty deviations from `this` with corresponding non-empty
   * deviations from `that`.
   */
  def fill(that: PartialTuning): PartialTuning = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    val resultDeviations = (this.deviations zip that.deviations).map {
      case (thisDeviation, thatDeviation) => thisDeviation.orElse(thatDeviation)
    }

    PartialTuning(name, resultDeviations)
  }

  /**
   * Overwrites each key from `this` with with corresponding non-empty deviations from `that`.
   */
  def overwrite(that: PartialTuning): PartialTuning = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    val resultDeviations = (this.deviations zip that.deviations).map {
      case (thisDeviation, thatDeviation) => (thisDeviation ++ thatDeviation).lastOption
    }

    PartialTuning(name, resultDeviations)
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
  def merge(that: PartialTuning, tolerance: Double = DefaultCentsTolerance): Option[PartialTuning] = {
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
    def accMerge(acc: Array[Option[Double]], index: Int): Option[PartialTuning] = {
      if (index == size) {
        Some(PartialTuning(mergeName(this.name, that.name), ArraySeq.unsafeWrapArray(acc)))
      } else {
        (this.deviations(index), that.deviations(index)) match {
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
   * Checks if this [[PartialTuning]] has the deviations equal within an error tolerance with the given
   * [[PartialTuning]]. Other properties are ignored in the comparison.
   *
   * @param that           The partial tuning to compare with.
   * @param centsTolerance Error tolerance in cents.
   * @return true if the partial tunings are almost equal, or false otherwise.
   */
  def almostEquals(that: PartialTuning, centsTolerance: Double): Boolean = {
    (this.deviations zip that.deviations).forall {
      case (None, None) => true
      case (Some(d1), Some(d2)) => DoubleMath.fuzzyEquals(d1, d2, centsTolerance)
      case _ => false
    }
  }

  override def toString: String = {
    val deviationsAsString = deviations.map(fromDeviationToString)

    val notesWithDeviations = (PitchClass.noteNames zip deviationsAsString).map {
      case (noteName, deviationString) => s"$noteName = ${deviationString.trim}"
    }.mkString(", ")

    s""""$name" ($notesWithDeviations)"""
  }

  def toPianoKeyboardString: String = {
    def padDeviation(deviation: Option[Double]) = fromDeviationToString(deviation).padTo(12, ' ')

    val missingKeySpace = " " * 6

    val blackKeysString =
      Seq(Some(cSharp), Some(dSharp), None, None, Some(fSharp), Some(gSharp), Some(aSharp)).map {
        case Some(deviation) => padDeviation(deviation)
        case None => missingKeySpace
      }.mkString("")
    val whiteKeysString = Seq(c, d, e, f, g, a, b).map(padDeviation).mkString("")

    s"$missingKeySpace$blackKeysString\n$whiteKeysString"
  }

  def unfilledPitchClassesString: String = {
    val pitches = deviations.zipWithIndex.map {
      case (None, index) =>
        Some(PitchClass.nameOf(index))
      case _ => None
    }.filter(_.nonEmpty).map(_.get)
    s"\"$name\": ${pitches.mkString(", ")}"
  }
}

object PartialTuning {
  /**
   * A [[PartialTuning]] with 12 keys and no deviations completed.
   */
  val EmptyOctave: PartialTuning = empty(PianoKeyboardTuningUtils.tuningSize)

  /**
   * A [[PartialTuning]] with 12 keys and all 0 deviations for the standard 12-tone equal temperament.
   */
  val StandardTuningOctave: PartialTuning = fill(0, PianoKeyboardTuningUtils.tuningSize)

  def apply(deviations: Seq[Option[Double]]): PartialTuning = PartialTuning("", deviations)

  /**
   * Creates a named `PartialTuning` for the 12 pitch classes in an octave.
   */
  def apply(name: String,
            c: Option[Double],
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
            b: Option[Double]): PartialTuning = {
    val deviations = Seq(c, cSharpOrDFlat, d, dSharpOrEFlat, e, f, fSharpOrGFlat, g,
      gSharpOrAFlat, a, aSharpOrBFlat, b)
    PartialTuning(name, deviations)
  }

  /**
   * Creates a `PartialTuning` for the 12 pitch classes in an octave.
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
            b: Option[Double]): PartialTuning = {
    apply("", c, cSharpOrDFlat, d, dSharpOrEFlat, e, f, fSharpOrGFlat, g, gSharpOrAFlat, a, aSharpOrBFlat, b)
  }

  /**
   * Creates a [[PartialTuning]] which has no deviation in each of its keys.
   *
   * @param size the number of keys in the partial tuning
   * @return a new partial tuning
   */
  def empty(size: Int): PartialTuning = PartialTuning(Seq.fill(size)(None))

  def fill(deviation: Double, size: Int): PartialTuning = PartialTuning(Seq.fill(size)(Some(deviation)))

  def fromDeviationToString(deviation: Double): String = f"$deviation%+06.2f"

  def fromDeviationToString(deviation: Option[Double]): String = deviation.fold("  --  ")(fromDeviationToString)
}
