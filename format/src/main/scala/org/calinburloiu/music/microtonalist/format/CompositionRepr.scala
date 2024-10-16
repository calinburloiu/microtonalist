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
import org.calinburloiu.music.microtonalist.core.{TuningMapper, TuningReducer}
import play.api.libs.json.JsObject

import java.net.URI
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Class used as a representation for the JSON format of a Microtonalist composition file.
 */
case class CompositionRepr(name: Option[String],
                           tuningReference: OriginRepr,
                           tunings: Seq[TuningSpecRepr],
                           tuningReducer: Option[TuningReducer] = None,
                           globalFill: Option[TuningSpecRepr],
                           config: Option[CompositionConfigRepr]) {

  var context: CompositionFormatContext = CompositionFormatContext()

  def loadDeferredData(scaleRepo: ScaleRepo, baseUri: Option[URI]): Future[this.type] = {
    def scaleLoader(placeholder: Import): Future[Scale[Interval]] = {
      val uri = placeholder.ref
      val resolvedUri = baseUri.map(_.resolve(uri)).getOrElse(uri)

      scaleRepo.readAsync(resolvedUri)
    }

    val futures: ArrayBuffer[Future[Any]] = ArrayBuffer()
    tunings.foreach { tuningSpec =>
      futures += tuningSpec.scale.load(scaleLoader)
    }

    if (globalFill.isDefined) {
      futures += globalFill.get.scale.load(scaleLoader)
    }

    Future.sequence(futures).map(_ => this)
  }
}

case class CompositionDefinitions(scales: Map[String, DeferrableRead[Scale[Interval], Import]] = Map())

case class CompositionFormatContext(intonationStandard: IntonationStandard = CentsIntonationStandard,
                                    baseUri: Option[URI] = None,
                                    settings: Map[String, Map[String, JsObject]] = Map())

// TODO #59
@deprecated("To use a string with a URI directly")
case class Import(ref: URI)

// TODO #62 Use a tuning reference component format

@deprecated("To be replaced with tuning reference component format")
case class OriginRepr(basePitchClass: Int)

case class TuningSpecRepr(transposition: Option[Interval] = None,
                          scale: DeferrableRead[Scale[Interval], Import],
                          tuningMapper: Option[TuningMapper])

// TODO #61
@deprecated("To be replaced to config with settings")
case class CompositionConfigRepr(mapQuarterTonesLow: Boolean = false)

object CompositionConfigRepr {

  val Default: CompositionConfigRepr = CompositionConfigRepr()
}

// TODO #61
@deprecated("To be replaced with TuningMapper component JSON format")
case class AutoTuningMapperRepr(mapQuarterTonesLow: Boolean = false,
                                halfTolerance: Option[Double] = None)
