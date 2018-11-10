package org.calinburloiu.music.tuning

import com.google.common.base.Preconditions._

import scala.annotation.tailrec

case class PartialTuning(
  override val deviations: Seq[Option[Double]]
) extends TuningBase[Option[Double]] {

  def apply(index: Int): Option[Double] = {
    checkElementIndex(index, size)

    deviations(index)
  }

  override def size: Int = deviations.size

  override def iterator: Iterator[Option[Double]] = deviations.iterator

  def isComplete: Boolean = deviations.forall(_.nonEmpty)

  def resolve(name: String): Option[Tuning] = if (isComplete)
    Some(Tuning(name, deviations.map(_.get)))
  else
    None

  /** Fills each pitch class with empty deviations from `this` with corresponding non-empty
    * deviations from `that`.
    * */
  def enrich(that: PartialTuning): PartialTuning = {
    checkArgument(this.size == that.size,
      "Expecting equally sized operand, got one with size %s", that.size)

    val resultDeviations = (this.deviations zip that.deviations).map {
      case (thisDeviation, thatDeviation) => (thisDeviation ++ thatDeviation).headOption
    }

    PartialTuning(resultDeviations)
  }

  def merge(that: PartialTuning): Option[PartialTuning] = {
    checkArgument(this.size == that.size,
      "Expecting equally sized operand, got one with size %s", that.size)

    @tailrec
    def accMerge(acc: Array[Option[Double]], index: Int): Option[PartialTuning] = {
      if (index == size) {
        Some(PartialTuning(acc))
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

  val emptyPianoKeyboard: PartialTuning = empty(PianoKeyboardTuningUtils.tuningSize)

  def apply(headDeviation: Option[Double], tailDeviations: Option[Double]*): PartialTuning =
    PartialTuning(headDeviation +: tailDeviations)

  def empty(size: Int): PartialTuning = PartialTuning(Seq.fill(size)(None))
}
