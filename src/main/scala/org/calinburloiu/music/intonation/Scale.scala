package org.calinburloiu.music.intonation

import com.google.common.base.Preconditions._

class Scale[+I <: Interval](val name: String, val intervals: Seq[I]) {
  checkArgument(intervals.nonEmpty, "Expecting a non-empty list of intervals".asInstanceOf[Object])

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
}


case class RatiosScale(
  override val name: String,
  override val intervals: Seq[RatioInterval]
) extends Scale[RatioInterval](name, intervals)

object RatiosScale {

  def apply(
      name: String,
      headRatioPitch: RatioInterval,
      tailRatioPitches: RatioInterval*): RatiosScale =
    RatiosScale(name, headRatioPitch +: tailRatioPitches)

  def apply(headRatioPitch: RatioInterval, tailRatioPitches: RatioInterval*): RatiosScale =
    RatiosScale("", headRatioPitch, tailRatioPitches: _*)
}


case class CentsScale(
  override val name: String,
  override val intervals: Seq[CentsInterval]
) extends Scale[CentsInterval](name, intervals)

object CentsScale {

  def apply(
      name: String,
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
