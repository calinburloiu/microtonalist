package org.calinburloiu.music.tuning

import com.google.common.base.Preconditions._

trait TuningBase[U] extends Iterable[U] {
  checkArgument(deviations.nonEmpty,
      "Expecting a non-empty list of deviations".asInstanceOf[Object])

  def apply(index: Int): U

  def deviations: Seq[U]
}
