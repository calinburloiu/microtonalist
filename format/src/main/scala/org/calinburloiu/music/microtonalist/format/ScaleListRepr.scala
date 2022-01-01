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

package org.calinburloiu.music.microtonalist.format

import org.calinburloiu.music.intonation.{Interval, Scale}
import org.calinburloiu.music.microtonalist.core.{TuningMapper, TuningReducer}

import java.net.URI

/**
 * Class used as a representation for the JSON format of a scale list.
 */
case class ScaleListRepr(name: Option[String],
                         tuningReference: OriginRepr,
                         modulations: Seq[ModulationRepr],
                         tuningReducer: Option[TuningReducer] = None,
                         globalFill: DeferrableRead[Scale[Interval], Import],
                         globalFillTuningMapper: Option[TuningMapper] = None,
                         config: Option[ScaleListConfigRepr]) {

  def loadDeferredData(scaleRepo: ScaleRepo, baseUri: Option[URI]): Unit = {
    def scaleLoader(placeholder: Import): Scale[Interval] = {
      val uri = placeholder.ref
      val resolvedUri = baseUri.map(_.resolve(uri)).getOrElse(uri)
      scaleRepo.read(resolvedUri)
    }

    modulations.foreach { modulation =>
      modulation.scale.load(scaleLoader)

      modulation.extension.foreach { extension =>
        extension.load(scaleLoader)
      }
    }

    globalFill.load(scaleLoader)
  }
}

// TODO #38 Rename ref to import
case class Import(ref: URI)

case class OriginRepr(basePitchClass: Int)

case class ModulationRepr(transposition: Option[Interval] = None,
                          scale: DeferrableRead[Scale[Interval], Import],
                          tuningMapper: Option[TuningMapper],
                          extension: Option[DeferrableRead[Scale[Interval], Import]])

case class ScaleListConfigRepr(mapQuarterTonesLow: Boolean = false)

object ScaleListConfigRepr {

  val Default: ScaleListConfigRepr = ScaleListConfigRepr()
}

case class AutoTuningMapperRepr(mapQuarterTonesLow: Boolean = false,
                                halfTolerance: Option[Double] = None)
