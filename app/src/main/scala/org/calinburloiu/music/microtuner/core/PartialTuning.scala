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

package org.calinburloiu.music.microtuner.core

import com.google.common.base.Preconditions.checkElementIndex
import com.google.common.math.DoubleMath
import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.music.microtuner.core.PianoKeyboardTuningUtils._
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
case class PartialTuning(override val deviations: Seq[Option[Double]],
                         override val name: String = "") extends Tuning[Option[Double]] with LazyLogging {
  /**
   * Returns the `Some` deviation in cents for a particular key 0-based index or `None` if there isn't one available.
   */
  def apply(index: Int): Option[Double] = {
    checkElementIndex(index, size)
    deviations(index)
  }

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
   * Attempts to create a [[OctaveTuning]] from this partial tuning if is complete (see [[isComplete]]).
   *
   * @return maybe a new [[OctaveTuning]]
   * @see [[isComplete]]
   */
  def resolve: Option[OctaveTuning] = if (isComplete)
    Some(OctaveTuning(name, deviations.map(_.get)))
  else
    None

  /**
   * Fills each key with empty deviations from `this` with corresponding non-empty
   * deviations from `that`.
   */
  def fill(that: PartialTuning): PartialTuning = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    val resultDeviations = (this.deviations zip that.deviations).map {
      case (thisDeviation, thatDeviation) => (thisDeviation ++ thatDeviation).headOption
    }

    PartialTuning(resultDeviations, name)
  }

  /**
   * Overwrites each key from `this` with with corresponding non-empty deviations from `that`.
   */
  def overwrite(that: PartialTuning): PartialTuning = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    val resultDeviations = (this.deviations zip that.deviations).map {
      case (thisDeviation, thatDeviation) => (thisDeviation ++ thatDeviation).lastOption
    }

    PartialTuning(resultDeviations, name)
  }

  /**
   * Merges `this` partial tuning with another into a new partial tuning by completing the corresponding keys.
   *
   * If one has a deviation and the other does not for a key, the deviation of the former is used. If none have a
   * deviation, the resulting key will continue to be empty. If both have a deviation for a key, the deviation of
   * `this` for that key is kept.
   *
   * @param that      other partial tuning used for merging
   * @param tolerance maximum error tolerance in cents when comparing two correspondent deviations for equality
   * @return a new partial tuning
   */
  def merge(that: PartialTuning, tolerance: Double): Option[PartialTuning] = {
    require(this.size == that.size, s"Expecting equally sized operand, got one with size ${that.size}")

    def mergeName(leftName: String, rightName: String): String = {
      if (leftName.isEmpty) {
        rightName
      } else {
        s"$leftName | $rightName"
      }
    }

    @tailrec
    def accMerge(acc: Array[Option[Double]], index: Int): Option[PartialTuning] = {
      if (index == size) {
        Some(PartialTuning(ArraySeq.unsafeWrapArray(acc), mergeName(this.name, that.name)))
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
            logger.debug(s"Conflict for pitch class ${PitchClass.fromInt(index)} in PartialTunings ${this
              .toNoteNamesString} and ${that.toNoteNamesString}")
            None
        }
      }
    }

    val emptyAcc = new Array[Option[Double]](size)

    accMerge(emptyAcc, 0)
  }

  // TODO #31
  def equalsWithTolerance(that: PartialTuning): Boolean = ???
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
    PartialTuning(deviations, name)
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
}
