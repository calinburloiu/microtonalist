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

package org.calinburloiu.music.intonation

import com.google.common.base.Preconditions.checkElementIndex
import com.google.common.math.DoubleMath

class Scale[+I <: Interval](val name: String, val intervals: Seq[I]) {
  require(intervals.nonEmpty, "Expecting a non-empty list of intervals")
  require(areIntervalsSorted, "Expecting intervals to be sorted in ascending or descending order")

  import Scale._

  def apply(index: Int): I = {
    checkElementIndex(index, size)
    intervals(index)
  }

  /** @return the number of intervals within the scale. */
  def size: Int = intervals.size

  /** @return the interval between the first and the last step. */
  def range: I = {
    val first = intervals.head
    val last = intervals.last
    val result = if (first <= last) last - first else first - last
    result.asInstanceOf[I]
  }

  /**
   * Tells whether the scale is ascending or descending.
   *
   * @return 1 if the scale is ascending, -1 if it's descending, or 0 otherwise (intervals is a set with 1 element).
   */
  def direction: Int = {
    val first = intervals.head
    val last = intervals.last

    if (first == last) 0
    else if (first < last) 1
    else -1
  }

  /**
   * @return the intervals between all pairs of the adjacent absolute [[intervals]].
   */
  def recurrentIntervals: Seq[I] = {
    val result = intervals.sliding(2).map { case Seq(a, b) => (b - a).asInstanceOf[I] }.toSeq
    if (direction >= 0) result else result.map(_.reverse.asInstanceOf[I])
  }

  /**
   * A measure of how soft a scale is which uses the concept of entropy.
   *
   * The softer a scale is, the closer to each other are its [[recurrentIntervals]].
   *
   * The softest scale with a given number if intervals is the one in which all [[recurrentIntervals]] are equal. For
   * example, the whole tone scale is the softest octave-based scale.
   *
   * For computing the entropy each interval of [[recurrentIntervals]] is transformed into a probability by taking
   * its proportion with respect to scale's [[range]].
   *
   * @param logBase the base to be used for the logarithm when computing the entropy.
   * @return the entropy value.
   * @see [[softness]] for an easier to use metric that does not require passing a logarithm base.
   */
  def entropy(logBase: Int): Double = {
    val rangeInCents = range.cents
    val ps = recurrentIntervals.map { interval => interval.cents / rangeInCents }

    -ps.map { p => p * Math.log(p) / Math.log(logBase) }.sum
  }

  /**
   * A measure of how soft a scale is which uses the concept of entropy.
   *
   * The softest scale for a given number of intervals will have a softness of 1. The harder the scale is the closer
   * the softness value is to 0.
   *
   * It uses [[entropy]] with a logarithm base equal to the number of [[recurrentIntervals]].
   *
   * @return the softness value.
   * @see [[entropy]] for details about what a soft/hard scale means and for details about the usage of entropy.
   */
  def softness: Double = entropy(size - 1)

  def transpose(interval: Interval): Scale[Interval] = new Scale(name, intervals.map(_ + interval))

  def rename(newName: String): Scale[I] = new Scale(newName, intervals)

  /**
   * @return [[Some]] [[IntonationStandard]] if all intervals are compatible with it, or [[None]] otherwise.
   */
  def intonationStandard: Option[IntonationStandard] = {
    val perInterval = intervals.map {
      case _: CentsInterval => Some(CentsIntonationStandard)
      case _: RatioInterval => Some(JustIntonationStandard)
      case EdoInterval(edo, _) => Some(EdoIntonationStandard(edo))
      case _ => None
    }
    // intervals cannot be empty, so head will never fail
    val forFirstInterval = perInterval.head

    if (perInterval.tail.forall(_ == forFirstInterval)) {
      forFirstInterval
    } else {
      None
    }
  }

  def isCentsScale: Boolean = intervals.forall(_.isInstanceOf[CentsInterval])

  def isRatiosScale: Boolean = intervals.forall(_.isInstanceOf[RatioInterval])

  def isEdoScale: Boolean = intervals.forall(_.isInstanceOf[EdoInterval])

  /**
   * Attempts to convert the scale such that the new one has its intervals in the given intonation standard.
   *
   * Note that the converted result might be the exact same instance if no conversion was needed and the scale already
   * complies with the given intonation standard.
   *
   * @param newIntonationStandard The given intonation standard to convert to.
   * @return [[Some]] new scale with the intervals converted or [[None]] if the conversion is not possible.
   */
  def convertToIntonationStandard(newIntonationStandard: IntonationStandard): Option[ScaleConversionResult] = {
    newIntonationStandard match {
      case _: IntonationStandard if intonationStandard.contains(newIntonationStandard) =>
        Some(ScaleConversionResult(this, NoConversion))
      case CentsIntonationStandard =>
        Some(ScaleConversionResult(
          CentsScale(name, intervals.map(_.toCentsInterval)),
          LosslessConversion
        ))
      case JustIntonationStandard => None
      case EdoIntonationStandard(thatEdo) =>
        val quality = intonationStandard match {
          case Some(EdoIntonationStandard(thisEdo)) if thatEdo % thisEdo == 0 => LosslessConversion
          case _ => LossyConversion
        }
        Some(ScaleConversionResult(EdoScale(name, intervals.map(_.toEdoInterval(thatEdo))), quality))
    }
  }

  /**
   * @return the index of the unison interval if there is one, or `-1` otherwise.
   */
  def indexOfUnison: Int = {
    for (i <- 0 until size) {
      if (intervals(i).isUnison) {
        return i
      }
    }

    -1
  }

  private def canEqual(other: Any): Boolean = other.isInstanceOf[Scale[_]]

  override def equals(other: Any): Boolean = other match {
    case that: Scale[_] =>
      (that canEqual this) &&
        intervals == that.intervals &&
        name == that.name
    case _ => false
  }

  /**
   * Checks if this scale has the intervals equal within an error tolerance with the given scale. Other properties
   * are ignored in the comparison.
   *
   * @param that           The scale to compare with.
   * @param centsTolerance Error tolerance in cents.
   * @return true if the scales are almost equal, or false otherwise.
   */
  def almostEquals(that: Scale[Interval], centsTolerance: Double = 0.02): Boolean = {
    (this.intervals zip that.intervals).forall { case (i1, i2) =>
      DoubleMath.fuzzyEquals(i1.cents, i2.cents, centsTolerance)
    }
  }

  override def hashCode(): Int = {
    val state = Seq(intervals, name)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString: String = s"Scale($name, $intervals)"

  private def areIntervalsSorted: Boolean = {
    var isAscending = true
    var isDescending = true

    for (Seq(a, b) <- intervals.sliding(2)) {
      isAscending = isAscending && a <= b
      isDescending = isDescending && a >= b
    }

    isAscending || isDescending
  }
}

object Scale {

  sealed trait ConversionQuality

  case object NoConversion extends ConversionQuality

  case object LosslessConversion extends ConversionQuality

  case object LossyConversion extends ConversionQuality

  def apply[I <: Interval](name: String, pitches: Seq[I]): Scale[I] = new Scale(name, pitches)

  def apply[I <: Interval](name: String, headPitch: I, tailPitches: I*): Scale[I] =
    new Scale(name, headPitch +: tailPitches)

  def apply[I <: Interval](headPitch: I, tailPitches: I*): Scale[I] =
    Scale("", headPitch, tailPitches: _*)

  /**
   * Creates the correct [[Scale]] implementation by taking pitches [[Interval]] implementation into account.
   *
   * @param name      name of scale to be created
   * @param intervals scale pitches
   * @return the created scale
   */
  def create(name: String, intervals: Seq[Interval]): Scale[Interval] = {
    def checkAllSameEdo: Boolean = {
      if (intervals.nonEmpty && intervals.head.isInstanceOf[EdoInterval]) {
        val edo = intervals.head.asInstanceOf[EdoInterval].edo
        intervals.tail.forall {
          case interval: EdoInterval => interval.edo == edo
          case _ => false
        }
      } else {
        false
      }
    }

    if (intervals.isEmpty) {
      Scale(name, CentsInterval(0.0))
    } else if (intervals.forall(_.isInstanceOf[CentsInterval])) {
      create(name, intervals, CentsIntonationStandard)
    } else if (intervals.forall(_.isInstanceOf[RatioInterval])) {
      create(name, intervals, JustIntonationStandard)
    } else if (checkAllSameEdo) {
      val edo = intervals.head.asInstanceOf[EdoInterval].edo
      create(name, intervals, EdoIntonationStandard(edo))
    } else {
      Scale(name, processIntervals(intervals, RealInterval.Unison))
    }
  }

  def create(name: String, intervals: Seq[Interval], intonationStandard: IntonationStandard): Scale[Interval] = {
    intonationStandard match {
      case CentsIntonationStandard => CentsScale(name, processIntervals(intervals.map(_.toCentsInterval),
        CentsInterval.Unison))
      case JustIntonationStandard if intervals.forall(_.isInstanceOf[RatioInterval]) =>
        RatiosScale(name, processIntervals(intervals.asInstanceOf[Seq[RatioInterval]], RatioInterval.Unison))
      case JustIntonationStandard => throw new IllegalArgumentException("A scale with JustIntonationStandard must " +
        "have all intervals of type RatioInterval")
      case EdoIntonationStandard(edo) => EdoScale(name, processIntervals(intervals.map(_.toEdoInterval(edo)),
        EdoInterval.unisonFor(edo)))
    }
  }

  private def processIntervals[I <: Interval](intervals: Seq[Interval], unison: I): Seq[I] = {
    val intervalsWithUnison = if (!intervals.exists(interval => interval.isUnison)) unison +: intervals else intervals
    intervalsWithUnison.sorted.asInstanceOf[Seq[I]]
  }
}


case class ScaleConversionResult(scale: Scale[Interval], conversionQuality: Scale.ConversionQuality)


case class RatiosScale(override val name: String,
                       override val intervals: Seq[RatioInterval]) extends Scale[RatioInterval](name, intervals) {

  def transpose(interval: RatioInterval): RatiosScale = copy(intervals = intervals.map(_ + interval))

  override def transpose(interval: Interval): Scale[Interval] = interval match {
    case ratioInterval: RatioInterval => transpose(ratioInterval)
    case _ => super.transpose(interval)
  }

  override def rename(newName: String): RatiosScale = copy(name = newName)

  override def intonationStandard: Option[IntonationStandard] = Some(JustIntonationStandard)

  override def isCentsScale: Boolean = false

  override def isRatiosScale: Boolean = true

  override def isEdoScale: Boolean = false
}

object RatiosScale {

  def apply(name: String,
            headRatioPitch: RatioInterval,
            tailRatioPitches: RatioInterval*): RatiosScale =
    RatiosScale(name, headRatioPitch +: tailRatioPitches)

  def apply(headRatioPitch: RatioInterval, tailRatioPitches: RatioInterval*): RatiosScale =
    RatiosScale("", headRatioPitch, tailRatioPitches: _*)
}


case class CentsScale(override val name: String,
                      override val intervals: Seq[CentsInterval]) extends Scale[CentsInterval](name, intervals) {

  def transpose(interval: CentsInterval): CentsScale = copy(intervals = intervals.map(_ + interval))

  override def transpose(interval: Interval): Scale[Interval] = interval match {
    case centsInterval: CentsInterval => transpose(centsInterval)
    case _ => super.transpose(interval)
  }

  override def rename(newName: String): CentsScale = copy(name = newName)

  override def intonationStandard: Option[IntonationStandard] = Some(CentsIntonationStandard)

  override def isCentsScale: Boolean = true

  override def isRatiosScale: Boolean = false

  override def isEdoScale: Boolean = false
}

object CentsScale {

  def apply(name: String,
            headCentsInterval: CentsInterval,
            tailCentsIntervals: CentsInterval*): CentsScale =
    CentsScale(name, headCentsInterval +: tailCentsIntervals)

  def apply(headCentsInterval: CentsInterval, tailCentsIntervals: CentsInterval*): CentsScale =
    CentsScale("", headCentsInterval, tailCentsIntervals: _*)

  def apply(name: String, headCentValue: Double, tailCentValues: Double*): CentsScale =
    CentsScale(name, (headCentValue +: tailCentValues).map(CentsInterval.apply))

  def apply(headCentValue: Double, tailCentValues: Double*): CentsScale =
    CentsScale("", headCentValue, tailCentValues: _*)
}

case class EdoScale(override val name: String,
                    override val intervals: Seq[EdoInterval]) extends Scale[EdoInterval](name, intervals) {
  require(intervals.forall(_.edo == edo))

  def edo: Int = intervals.head.edo

  def transpose(interval: EdoInterval): EdoScale = {
    require(interval.edo == edo)
    copy(intervals = intervals.map(_ + interval))
  }

  override def rename(newName: String): EdoScale = copy(name = newName)

  override def intonationStandard: Option[IntonationStandard] = Some(EdoIntonationStandard(edo))

  override def isCentsScale: Boolean = false

  override def isRatiosScale: Boolean = false

  override def isEdoScale: Boolean = true
}

object EdoScale {

  def apply(name: String,
            headEdoInterval: EdoInterval,
            tailEdoIntervals: EdoInterval*): EdoScale =
    EdoScale(name, headEdoInterval +: tailEdoIntervals)

  def apply(headEdoInterval: EdoInterval, tailEdoIntervals: EdoInterval*): EdoScale =
    EdoScale("", headEdoInterval, tailEdoIntervals: _*)

  def apply(name: String, edo: Int, headCount: Int, tailCounts: Int*): EdoScale =
    EdoScale(name, (headCount +: tailCounts).map(EdoInterval(edo, _)))

  def apply(edo: Int, headCount: Int, tailCounts: Int*): EdoScale =
    EdoScale("", edo, headCount, tailCounts: _*)

  def apply(name: String,
            edo: Int, headCountRelativeToStandard: (Int, Int),
            tailCountRelativeToStandard: (Int, Int)*): EdoScale =
    EdoScale(name, (headCountRelativeToStandard +: tailCountRelativeToStandard).map(EdoInterval(edo, _)))

  def apply(edo: Int, headCountRelativeToStandard: (Int, Int), tailCountRelativeToStandard: (Int, Int)*): EdoScale =
    EdoScale("", (headCountRelativeToStandard +: tailCountRelativeToStandard).map(EdoInterval(edo, _)))
}
