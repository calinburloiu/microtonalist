/*
 * Copyright 2025 Calin-Andrei Burloiu
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
import org.calinburloiu.music.microtonalist.composition.*
import play.api.libs.json.{JsNull, JsObject, JsValue}

import java.net.URI
import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Class used as a representation for the JSON format of a Microtonalist composition file.
 */
case class CompositionRepr(metadata: Option[CompositionMetadata],
                           tuningReference: TuningReference,
                           tunings: Seq[TuningSpecRepr],
                           tuningReducer: Option[TuningReducer] = None,
                           fill: FillSpecRepr = FillSpecRepr(),
                           tracksUri: Option[URI] = None) {

  var context: CompositionFormatContext = uninitialized

  def loadDeferredData(scaleRepo: ScaleRepo): Future[this.type] = {
    val localScaleCache = mutable.Map[URI, Future[Scale[Interval]]]()

    def scaleLoader(uri: URI): Future[Scale[Interval]] = {
      val resolvedUri = context.baseUri.map(_.resolve(uri)).getOrElse(uri)

      localScaleCache.get(resolvedUri) match {
        case None =>
          val scale = scaleRepo.readAsync(resolvedUri)
          localScaleCache.addOne((resolvedUri, scale))
          scale
        case Some(scale) => scale
      }

    }

    val futures: mutable.ArrayBuffer[Future[Any]] = mutable.ArrayBuffer()
    for (tuningSpec <- tunings) {
      futures += tuningSpec.scale.load(scaleLoader)
    }

    for (globalFillValue <- fill.global) {
      futures += globalFillValue.scale.load(scaleLoader)
    }

    Future.sequence(futures).map(_ => this)
  }
}

case class LocalFillSpecRepr(foreFillEnabled: Boolean = true,
                             backFillEnabled: Boolean = true,
                             memoryFillEnabled: Boolean = false) {
  if (!foreFillEnabled || !backFillEnabled || memoryFillEnabled) {
    throw new NotImplementedError("foreFillEnabled, backFillEnabled and memoryFillEnabled are not supported yet!")
  }
}

case class FillSpecRepr(local: LocalFillSpecRepr = LocalFillSpecRepr(),
                        global: Option[TuningSpecRepr] = None)

case class CompositionDefinitions(scales: Map[String, DeferrableRead[Scale[Interval], URI]] = Map())

class CompositionFormatContext {
  var uri: Option[URI] = None
  var baseUri: Option[URI] = None
  var preprocessedJson: JsValue = JsNull
  var intonationStandard: IntonationStandard = CentsIntonationStandard
  var settings: JsObject = JsObject.empty
}

case class TuningSpecRepr(transposition: Interval,
                          scale: DeferrableRead[Scale[Interval], URI],
                          tuningMapper: TuningMapper)
