/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.tuning

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.plugin.{PluginConfig, PluginFactory}

class DirectTuningReducer extends TuningReducer(None) with StrictLogging {

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
      throw new DirectTuningReducerException(
        s"Some tunings are not complete: $maybeTunings")
    }
  }
}

object DirectTuningReducer {
  val pluginId = "direct"
}

class DirectTuningListReducerFactory extends PluginFactory[DirectTuningReducer] {

  override val pluginId: String = DirectTuningReducer.pluginId

  override val configClass: Option[Class[_ <: PluginConfig]] = None

  override val defaultConfig: Option[PluginConfig] = None

  override def create(config: Option[PluginConfig] = None): DirectTuningReducer = config match {
    case None => new DirectTuningReducer
    case otherConfig => throw new IllegalArgumentException(
      s"Expecting a specific DirectTuningListReducer, but got ${otherConfig.getClass.getName}")
  }
}

class DirectTuningReducerException(message: String, cause: Throwable = null)
    extends TuningReducerException(message, cause)
