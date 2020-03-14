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
