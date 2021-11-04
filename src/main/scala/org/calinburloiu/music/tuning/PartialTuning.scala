/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.tuning

import com.google.common.base.Preconditions._

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
                         override val name: String = "") extends Tuning[Option[Double]] {
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

  // TODO #2 Name is no longer required
  /**
   * Attempts to create a [[OctaveTuning]] from this partial tuning if is complete (see [[isComplete]]).
   *
   * @param name a human-friendly name used for the new [[OctaveTuning]]
   * @return maybe a new [[OctaveTuning]]
   * @see [[isComplete]]
   */
  def resolve(name: String): Option[OctaveTuning] = if (isComplete)
    Some(OctaveTuning(name, deviations.map(_.get)))
  else
    None

  /**
   * Fills each key with empty deviations from `this` with corresponding non-empty
   * deviations from `that`.
   */
  def enrich(that: PartialTuning): PartialTuning = {
    checkArgument(this.size == that.size,
      "Expecting equally sized operand, got one with size %s", that.size)

    val resultDeviations = (this.deviations zip that.deviations).map {
      case (thisDeviation, thatDeviation) => (thisDeviation ++ thatDeviation).headOption
    }

    PartialTuning(resultDeviations)
  }

  /**
   * Overwrites each key from `this` with with corresponding non-empty deviations from `that`.
   */
  def overwrite(that: PartialTuning): PartialTuning = {
    checkArgument(this.size == that.size,
      "Expecting equally sized operand, got one with size %s", that.size)

    val resultDeviations = (this.deviations zip that.deviations).map {
      case (thisDeviation, thatDeviation) => (thisDeviation ++ thatDeviation).lastOption
    }

    PartialTuning(resultDeviations)
  }

  /**
   * Merges `this` partial tuning with another into a new partial tuning by completing the corresponding keys.
   *
   * If one has a deviation and the other does not for a key, the deviation of the former is used. If none have a
   * deviation, the resulting key will continue to be empty. If both have a deviation for a key, the deviation of
   * `this` for that key is kept.
   *
   * @param that other partial tuning used for merging
   * @return a new partial tuning
   */
  def merge(that: PartialTuning): Option[PartialTuning] = {
    checkArgument(this.size == that.size,
      "Expecting equally sized operand, got one with size %s", that.size)

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

          case (Some(dev1), Some(dev2)) if dev1 == dev2 =>
            acc(index) = Some(dev1)
            accMerge(acc, index + 1)

          // Conflict, stop!
          case _ =>
            None
        }
      }
    }

    val emptyAcc = new Array[Option[Double]](size)

    accMerge(emptyAcc, 0)
  }
}

object PartialTuning {

  /**
   * A [[PartialTuning]] with 12 keys and no deviations completed.
   */
  val EmptyOctave: PartialTuning = empty(PianoKeyboardTuningUtils.tuningSize)

  def apply(headDeviation: Option[Double], tailDeviations: Option[Double]*): PartialTuning =
    PartialTuning(headDeviation +: tailDeviations)

  /**
   * Creates a [[PartialTuning]] which has no deviation in each of its keys.
   *
   * @param size the number of keys in the partial tuning
   * @return a new partial tuning
   */
  def empty(size: Int): PartialTuning = PartialTuning(Seq.fill(size)(None))
}
