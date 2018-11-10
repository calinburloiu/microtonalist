package org.calinburloiu.music.tuning

import com.google.common.base.Preconditions
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.Interval
import org.calinburloiu.music.microtuner.{Modulation, OriginOld, ScaleList}

import scala.annotation.tailrec

case class TuningList(
  tunings: Seq[Tuning]
) extends Iterable[Tuning] {

  def apply(index: Int): Tuning = {
    Preconditions.checkElementIndex(index, size)

    tunings(index)
  }

  override def size: Int = tunings.size

  override def iterator: Iterator[Tuning] = tunings.iterator
}

object TuningList extends StrictLogging {

  def fromScaleList(scaleList: ScaleList): TuningList = {
    val globalFillScale = scaleList.globalFill.scale
    val globalFillTuning = scaleList.globalFill.tuningMapper(scaleList.origin.basePitchClass, globalFillScale)

    val tuningModulations = createTuningModulations(Interval.UNISON, Vector.empty,
      scaleList.modulations, scaleList.origin)

    val partialTuningList = PartialTuningList(globalFillTuning, tuningModulations)
    scaleList.tuningListReducer(partialTuningList)
  }

  @tailrec
  private[this] def createTuningModulations(
      cumulativeTransposition: Interval,
      tuningModulationsAcc: Seq[TuningModulation],
      modulations: Seq[Modulation],
      origin: OriginOld): Seq[TuningModulation] = {
    if (modulations.isEmpty) {
      tuningModulationsAcc
    } else {
      val crtTransposition = modulations.head.transposition
      // TODO Do we need to normalize?
      val newCumulativeTransposition = if (cumulativeTransposition.normalize.isUnison) {
        crtTransposition.normalize
      } else {
        (cumulativeTransposition + crtTransposition).normalize
      }
      val tuningModulation = createTuningModulation(
        newCumulativeTransposition, modulations.head, origin)

      createTuningModulations(newCumulativeTransposition, tuningModulationsAcc :+ tuningModulation,
        modulations.tail, origin)
    }
  }

  private[this] def createTuningModulation(
      cumulativeTransposition: Interval,
      modulation: Modulation,
      origin: OriginOld): TuningModulation = {
    val scaleName = modulation.scaleMapping.scale.name

    val extensionTuning = modulation.extension.map(_.tuning(origin, cumulativeTransposition))
      .getOrElse(PartialTuning.emptyPianoKeyboard)

    val tuning = modulation.scaleMapping.tuning(origin, cumulativeTransposition).enrich(extensionTuning)

    val fillTuning = modulation.fill.map(_.tuning(origin, cumulativeTransposition))
      .getOrElse(PartialTuning.emptyPianoKeyboard)

    TuningModulation(scaleName, tuning, fillTuning)
  }
}
