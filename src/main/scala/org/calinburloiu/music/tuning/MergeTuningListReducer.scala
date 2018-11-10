package org.calinburloiu.music.tuning

import com.google.common.base.Preconditions._
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.plugin.{PluginConfig, PluginFactory}

import scala.annotation.tailrec

class MergeTuningListReducer extends TuningListReducer(None) with StrictLogging {

  override def apply(partialTuningList: PartialTuningList): TuningList = {
    checkArgument(partialTuningList.tuningModulations.nonEmpty)

    val tuningSize = partialTuningList.tuningModulations.head.tuning.size
    val reducedTuningModulations =
        collect(Vector.empty[TuningModulation], partialTuningList.tuningModulations, tuningSize)
    val maybeTunings = reducedTuningModulations.map { tuningModulation =>
      val enrichedPartialTuning = Seq(
        tuningModulation.tuning,
        tuningModulation.fillTuning,
        partialTuningList.globalFillTuning
      ).reduce(_ enrich _)

      enrichedPartialTuning.resolve(tuningModulation.tuningName)
    }

    if (maybeTunings.forall(_.nonEmpty)) {
      TuningList(maybeTunings.map(_.get))
    } else {
      throw new MergeTuningListReducerException(
        s"Some tunings are not complete: $maybeTunings")
    }
  }

  @tailrec
  private[this] def collect(
      acc: Seq[TuningModulation],
      tuningModulations: Seq[TuningModulation],
      tuningSize: Int): Seq[TuningModulation] = {
    if (tuningModulations.isEmpty) {
      acc
    } else {
      val (mergedTuningModulation, tuningModulationsLeft) =
          merge(emptyTuningModulation(tuningSize), tuningModulations)

      collect(acc :+ mergedTuningModulation, tuningModulationsLeft, tuningSize)
    }
  }

  private[this] def emptyTuningModulation(size: Int) = {
    val emptyPartialTuning = PartialTuning.empty(size)
    TuningModulation("", emptyPartialTuning, emptyPartialTuning)
  }

  @tailrec
  private[this] def merge(
      acc: TuningModulation,
      tuningModulations: Seq[TuningModulation]): (TuningModulation, Seq[TuningModulation]) = {
    tuningModulations.headOption match {
      case Some(nextTuningModulation) =>
        acc.tuning merge nextTuningModulation.tuning match {
          case Some(mergedTuning) =>
            val mergedName = mergeName(acc.tuningName, nextTuningModulation.tuningName)
            val enrichedFillTuning = acc.fillTuning enrich nextTuningModulation.fillTuning
            val newAcc = TuningModulation(mergedName, mergedTuning, enrichedFillTuning)
            merge(newAcc, tuningModulations.tail)

          case None => (acc, tuningModulations)
        }

      case None => (acc, tuningModulations)
    }
  }

  private[this] def mergeName(leftName: String, rightName: String): String = {
    if (leftName.isEmpty) {
      rightName
    } else {
      s"$leftName | $rightName"
    }
  }
}

object MergeTuningListReducer {
  val pluginId: String = "merge"
}

class MergeTuningListReducerFactory extends PluginFactory[MergeTuningListReducer] {

  override val pluginId: String = MergeTuningListReducer.pluginId

  override val configClass: Option[Class[_ <: PluginConfig]] = None

  override val defaultConfig: Option[PluginConfig] = None

  override def create(config: Option[PluginConfig]): MergeTuningListReducer = config match {
    case None => new MergeTuningListReducer
    case otherConfig => throw new IllegalArgumentException(
      s"Expecting a specific MergeTuningListReducer, but got ${otherConfig.getClass.getName}")
  }
}

class MergeTuningListReducerException(message: String, cause: Throwable = null)
  extends TuningListReducerException(message, cause)
