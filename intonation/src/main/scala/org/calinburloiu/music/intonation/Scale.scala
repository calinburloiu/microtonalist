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

  def transpose(interval: Interval): Scale[Interval] = {
    val newPitches = intervals.map(_ + interval)

    new Scale(name, newPitches)
  }

  def canEqual(other: Any): Boolean = other.isInstanceOf[Scale[_]]

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
   * Creates the correct [[Scale]] implementation by taking pitches [[Interval]] implementation into accound.
   *
   * @param name    name of scale to be created
   * @param pitches scale pitches
   * @return the created scale
   */
   def create(name: String, pitches: Seq[Interval]): Scale[Interval] = {
    val hasUnison = pitches.headOption.exists(interval => interval.isUnison)

    // TODO #4 Handle the case when pitches are EdoIntervals
    if (pitches.isEmpty) {
      Scale(name, CentsInterval(0.0))
    } else if (pitches.forall(_.isInstanceOf[CentsInterval])) {
      val resultPitches = if (hasUnison) pitches else CentsInterval.Unison +: pitches
      CentsScale(name, resultPitches.map(_.asInstanceOf[CentsInterval]))
    } else if (pitches.forall(_.isInstanceOf[RatioInterval])) {
      val resultPitches = if (hasUnison) pitches else RatioInterval.Unison +: pitches
      RatiosScale(name, resultPitches.map(_.asInstanceOf[RatioInterval]))
    } else {
      val resultPitches = if (hasUnison) pitches else RealInterval.Unison +: pitches
      Scale(name, resultPitches)
    }
  }
}


case class RatiosScale(override val name: String,
                       override val intervals: Seq[RatioInterval]) extends Scale[RatioInterval](name, intervals)

object RatiosScale {

  def apply(name: String,
            headRatioPitch: RatioInterval,
            tailRatioPitches: RatioInterval*): RatiosScale =
    RatiosScale(name, headRatioPitch +: tailRatioPitches)

  def apply(headRatioPitch: RatioInterval, tailRatioPitches: RatioInterval*): RatiosScale =
    RatiosScale("", headRatioPitch, tailRatioPitches: _*)
}


case class CentsScale(override val name: String,
                      override val intervals: Seq[CentsInterval]) extends Scale[CentsInterval](name, intervals)

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
                    override val intervals: Seq[EdoInterval]) extends Scale[EdoInterval](name, intervals)

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
