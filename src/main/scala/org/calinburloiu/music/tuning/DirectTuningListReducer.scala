package org.calinburloiu.music.tuning

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.plugin.{PluginConfig, PluginFactory}

class DirectTuningListReducer extends TuningListReducer(None) with StrictLogging {

  override def apply(partialTuningList: PartialTuningList): TuningList = {
    val maybeTunings = partialTuningList.tuningModulations.map { modulation =>
      val partialTuning = Seq(
        modulation.tuning,
        modulation.fillTuning,
        partialTuningList.globalFillTuning
      ).reduce(_ enrich _)
      partialTuning.resolve(modulation.tuningName)
    }

    if (maybeTunings.forall(_.nonEmpty)) {
      TuningList(maybeTunings.map(_.get))
    } else {
      throw new DirectTuningListReducerException(
        s"Some tunings are not complete: $maybeTunings")
    }
  }
}

object DirectTuningListReducer {
  val pluginId = "direct"
}

class DirectTuningListReducerFactory extends PluginFactory[DirectTuningListReducer] {

  override val pluginId: String = DirectTuningListReducer.pluginId

  override val configClass: Option[Class[_ <: PluginConfig]] = None

  override val defaultConfig: Option[PluginConfig] = None

  override def create(config: Option[PluginConfig] = None): DirectTuningListReducer = config match {
    case None => new DirectTuningListReducer
    case otherConfig => throw new IllegalArgumentException(
      s"Expecting a specific DirectTuningListReducer, but got ${otherConfig.getClass.getName}")
  }
}

class DirectTuningListReducerException(message: String, cause: Throwable = null)
    extends TuningListReducerException(message, cause)
