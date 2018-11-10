package org.calinburloiu.music.tuning

import com.google.common.base.Preconditions._

case class Tuning(
  name: String,
  override val deviations: Seq[Double]
) extends TuningBase[Double] {

  def apply(index: Int): Double = {
    checkElementIndex(index, size)
    deviations(index)
  }

  override def size: Int = deviations.size

  override def iterator: Iterator[Double] = deviations.iterator

  override def toString: String = {
    val superString = super.toString
    s"$name = $superString"
  }
}

object Tuning {

  val equalTemperament = Tuning("Equal Temperament", Array(
    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
  ))

  def apply(name: String, headDeviation: Double, tailDeviations: Double*): Tuning =
    Tuning(name, headDeviation +: tailDeviations)
}
