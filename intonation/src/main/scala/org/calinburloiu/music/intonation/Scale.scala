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

class Scale[+I <: Interval](val name: String, val intervals: Seq[I]) {
  require(intervals.nonEmpty, "Expecting a non-empty list of intervals")

  def apply(index: Int): I = {
    checkElementIndex(index, size)
    intervals(index)
  }

  def size: Int = intervals.size

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

  def convertToIntonationStandard(intonationStandard: IntonationStandard): Scale[Interval] = {
    intonationStandard match {
      case CentsIntonationStandard => if (isCentsScale) this else CentsScale(name, intervals.map(_.toCentsInterval))
      case JustIntonationStandard => if (isRatiosScale) this else
        throw new IllegalArgumentException("Cannot convert a scale to just intonation from another intonation " +
          "standard!")
      case EdoIntonationStandard(edo) => if (isEdoScale && intervals.forall(_.asInstanceOf[EdoInterval].edo == edo)) {
        this
      } else {
        EdoScale(name, intervals.map(_.toEdoInterval(edo)))
      }
    }
  }

  private def canEqual(other: Any): Boolean = other.isInstanceOf[Scale[_]]

  override def equals(other: Any): Boolean = other match {
    case that: Scale[_] =>
      (that canEqual this) &&
        intervals == that.intervals &&
        name == that.name
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(intervals, name)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString: String = s"Scale($name, $intervals)"
}

object Scale {

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
      Scale(name, addUnison(intervals, RealInterval.Unison))
    }
  }

  def create(name: String, intervals: Seq[Interval], intonationStandard: IntonationStandard): Scale[Interval] = {
    intonationStandard match {
      case CentsIntonationStandard => CentsScale(name, addUnison(intervals.map(_.toCentsInterval), CentsInterval.Unison))
      case JustIntonationStandard if intervals.forall(_.isInstanceOf[RatioInterval]) =>
        RatiosScale(name, addUnison(intervals.asInstanceOf[Seq[RatioInterval]], RatioInterval.Unison))
      case JustIntonationStandard => throw new IllegalArgumentException("A scale with JustIntonationStandard must " +
        "have all intervals of type RatioInterval")
      case EdoIntonationStandard(edo) => EdoScale(name, addUnison(intervals.map(_.toEdoInterval(edo)), EdoInterval.unisonFor(edo)))
    }
  }

  private def addUnison[I](intervals: Seq[Interval], unison: I): Seq[I] = {
    if (intervals.exists(interval => interval.isUnison)) {
      intervals.asInstanceOf[Seq[I]]
    } else {
      (unison +: intervals).asInstanceOf[Seq[I]]
    }
  }
}


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
  def edo: Int = intervals.head.edo
  require(intervals.forall(_.edo == edo))

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
