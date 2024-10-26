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

import org.calinburloiu.music.intonation.{CentsIntonationStandard, Interval, IntonationStandard, Scale}
import org.calinburloiu.music.microtonalist.core.{CompositionMetadata, TuningMapper, TuningReducer}
import play.api.libs.json.JsObject

import java.net.URI
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Class used as a representation for the JSON format of a Microtonalist composition file.
 */
case class CompositionRepr(metadata: Option[CompositionMetadata],
                           tuningReference: OriginRepr,
                           tunings: Seq[TuningSpecRepr],
                           tuningReducer: Option[TuningReducer] = None,
                           globalFill: Option[TuningSpecRepr],
                           config: Option[CompositionConfigRepr]) {

  var context: CompositionFormatContext = CompositionFormatContext()

  def loadDeferredData(scaleRepo: ScaleRepo, baseUri: Option[URI]): Future[this.type] = {
    val actualBaseUri = (baseUri, context.baseUri) match {
      case (Some(baseUriValue), Some(contextBaseUriValue)) => Some(baseUriValue.resolve(contextBaseUriValue))
      case (Some(baseUriValue), None) => Some(baseUriValue)
      case (None, Some(contextBaseUriValue)) => Some(contextBaseUriValue)
      case (None, None) => None
    }
    val localScaleCache = mutable.Map[URI, Future[Scale[Interval]]]()

    def scaleLoader(uri: URI): Future[Scale[Interval]] = {
      val resolvedUri = actualBaseUri.map(_.resolve(uri)).getOrElse(uri)

      localScaleCache.get(resolvedUri) match {
        case None =>
          val scale = scaleRepo.readAsync(resolvedUri)
          localScaleCache.addOne((resolvedUri, scale))
          scale
        case Some(scale) => scale
      }

    }

    val futures: mutable.ArrayBuffer[Future[Any]] = mutable.ArrayBuffer()
    for (tuningSpec <- tunings; scale <- tuningSpec.scale) {
      futures += scale.load(scaleLoader)
    }

    for (globalFillValue <- globalFill; scale <- globalFillValue.scale) {
      futures += scale.load(scaleLoader)
    }

    Future.sequence(futures).map(_ => this)
  }
}

case class CompositionDefinitions(scales: Map[String, DeferrableRead[Scale[Interval], URI]] = Map())

case class CompositionFormatContext(intonationStandard: IntonationStandard = CentsIntonationStandard,
                                    baseUri: Option[URI] = None,
                                    settings: Map[String, Map[String, JsObject]] = Map())

// TODO #62 Use a tuning reference component format

@deprecated("To be replaced with tuning reference component format")
case class OriginRepr(basePitchClass: Int)

case class TuningSpecRepr(name: Option[String],
                          transposition: Option[Interval],
                          scale: Option[DeferrableRead[Scale[Interval], URI]],
                          tuningMapper: Option[TuningMapper])

// TODO #61
@deprecated("To be replaced to config with settings")
case class CompositionConfigRepr(shouldMapQuarterTonesLow: Boolean = false)

object CompositionConfigRepr {

  val Default: CompositionConfigRepr = CompositionConfigRepr()
}

// TODO #61
@deprecated("To be replaced with TuningMapper component JSON format")
case class AutoTuningMapperReprOld(shouldMapQuarterTonesLow: Boolean = false,
                                   quarterToneTolerance: Option[Double] = None)
