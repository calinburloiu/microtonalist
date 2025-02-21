/*
 * Copyright 2025 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.tuner

import com.google.common.base.Preconditions.checkElementIndex
import com.google.common.math.DoubleMath
import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.music.scmidi.PitchClass

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq

/**
 * Describes the tuning of a keyed instrument, typically with a piano keyboard, by specifying offsets in cents
 * for pitch classes. It is allowed to skip tuning values for pitch classes that are not used in the tuning.
 *
 * A tuning can only be specified for the 12 pitch classes: C, C#\Db, ..., B. It cannot specify different values for
 * each instance of a pitch class.
 *
 * @param offsetOptions `Some` tuning offset in cents for each key or `None` if the key is missing an offset value.
 */
case class Tuning(name: String, offsetOptions: Seq[Option[Double]]) extends Iterable[Option[Double]] with
  LazyLogging {
  require(offsetOptions.size == 12,
    s"There should be exactly 12 offsets corresponding to the 12 pitch classes, but found ${offsetOptions.size}!")

  import Tuning._


  /**
   * Retrieves the offset in cents for the specified pitch class number.
   * If no offset is defined for the provided pitch class number, returns a default value of 0.0.
   *
   * @param pitchClassNumber The 0-based index of the pitch class number whose offset is to be retrieved.
   * @return the offset in cents for the specified pitch class number, or 0.0 if no offset is defined.
   */
  def apply(pitchClassNumber: Int): Double = {
    checkElementIndex(pitchClassNumber, size)
    offsetOptions(pitchClassNumber).getOrElse(0.0)
  }

  /**
   * Retrieves the offset in cents for the specified pitch class number as an optional value, returning [[Some]]
   * value if there is a tuning defined for that pitch class or [[None]] if there isn't.
   *
   * @param pitchClassNumber The 0-based index of the pitch class number whose offset is to be retrieved.
   * @return a [[Some]] containing the offset in cents for the specified pitch class number, or `None` if no
   *         offset is defined.
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
   * @return `true` if offsets are available for all keys or `false` otherwise
   */
  def isComplete: Boolean = offsetOptions.forall(_.nonEmpty)

  /**
   * @return the number of completed pitch classes which have an offset defined
   */
  def completedCount: Int = offsetOptions.map(d => if (d.isDefined) 1 else 0).sum

  /**
   * Fills each key with empty offsets from `this` with corresponding non-empty
   * offsets from `that`.
   */
  def fill(that: Tuning): Tuning = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    val resultOffsets = (this.offsetOptions zip that.offsetOptions).map {
      case (thisOffset, thatOffset) => thisOffset.orElse(thatOffset)
    }

    Tuning(name, resultOffsets)
  }

  /**
   * Overwrites each key from `this` with with corresponding non-empty offsets from `that`.
   */
  def overwrite(that: Tuning): Tuning = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    val resultOffsets = (this.offsetOptions zip that.offsetOptions).map {
      case (thisOffset, thatOffset) => (thisOffset ++ thatOffset).lastOption
    }

    Tuning(name, resultOffsets)
  }

  /**
   * Merges `this` tuning with another into a new tuning by complementing the corresponding keys.
   *
   * The following cases apply:
   *
   *   - If one has a tuning offset and the other does not for a key, the offset of the former is used.
   *   - If none have a tuning offset, the resulting key will continue to be empty.
   *   - If both have the same tuning offset for a key (equal within the given tolerance), that key will use that
   *     offset.
   *   - If both have tuning offsets for a key, and they are not equal, it is said that there is a ''conflict'' and
   *     `None` is returned.
   *
   * @param that      other tuning used for merging
   * @param tolerance maximum error tolerance in cents when comparing two correspondent tuning offsets for equality
   * @return `Some` new tuning if the merge was successful, or `None` if there was a conflict.
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
            logger.debug(s"Conflict for pitch class ${PitchClass.fromNumber(index)} in Tunings $this and $that")
            None
        }
      }
    }

    val emptyAcc = new Array[Option[Double]](size)

    accMerge(emptyAcc, 0)
  }

  /**
   * Checks if this [[Tuning]] has the offsets equal within an error tolerance with the given
   * [[Tuning]]. Other properties are ignored in the comparison.
   *
   * @param that           The tuning to compare with.
   * @param centsTolerance Error tolerance in cents.
   * @return true if the tunings are almost equal, or false otherwise.
   */
  def almostEquals(that: Tuning, centsTolerance: Double = DefaultCentsTolerance): Boolean = {
    (this.offsetOptions zip that.offsetOptions).forall {
      case (None, None) => true
      case (Some(d1), Some(d2)) => DoubleMath.fuzzyEquals(d1, d2, centsTolerance)
      case _ => false
    }
  }

  override def toString: String = {
    val offsetsAsString = offsetOptions.map(fromOffsetToString)

    val notesWithOffsets = (PitchClass.noteNames zip offsetsAsString).map {
      case (noteName, offsetString) => s"$noteName = ${offsetString.trim}"
    }.mkString(", ")

    s""""$name" ($notesWithOffsets)"""
  }

  def toPianoKeyboardString: String = {
    def padOffset(offset: Option[Double]) = fromOffsetToString(offset).padTo(12, ' ')

    val missingKeySpace = " " * 6

    val blackKeysString =
      Seq(Some(get(1)), Some(get(3)), None, None, Some(get(6)), Some(get(8)), Some(get(10))).map {
        case Some(offset) => padOffset(offset)
        case None => missingKeySpace
      }.mkString("")
    val whiteKeysString = Seq(get(0), get(2), get(4), get(5), get(7), get(9), get(11)).map(padOffset).mkString("")

    s"\"$name\":\n$missingKeySpace$blackKeysString\n$whiteKeysString"
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
   * A [[Tuning]] with 12 keys and no offsets completed.
   */
  val Empty: Tuning = empty(Size)

  /**
   * A [[Tuning]] with 12 keys and all 0 offsets for the standard 12-tone equal temperament.
   */
  val Standard: Tuning = fill("Standard 12-EDO", 0, Size)

  /**
   * Creates a [[Tuning]] for the 12 pitch classes in an octave with an empty name.
   */
  def apply(offsets: Seq[Option[Double]]): Tuning = Tuning("", offsets)

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
    val offsets = Seq(c, cSharpOrDFlat, d, dSharpOrEFlat, e, f, fSharpOrGFlat, g,
      gSharpOrAFlat, a, aSharpOrBFlat, b)
    Tuning(name, offsets)
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
   * Creates a [[Tuning]] which has no offset in each of its keys.
   *
   * @param size the number of keys in the tuning
   * @return a new tuning
   */
  def empty(size: Int): Tuning = Tuning(Seq.fill(size)(None))

  /**
   * Creates a Tuning instance where all keys in the tuning have the same offset value.
   *
   * @param offset The value to fill each key in the tuning with.
   * @param size   The number of keys in the tuning.
   * @return a new Tuning instance filled with the specified offset value across all keys.
   */
  def fill(name: String, offset: Double, size: Int): Tuning = Tuning(name, Seq.fill(size)(Some(offset)))

  private def fromOffsetToString(offset: Double): String = f"$offset%+06.2f"

  private def fromOffsetToString(offset: Option[Double]): String = offset.fold("  --  ")(fromOffsetToString)
}
