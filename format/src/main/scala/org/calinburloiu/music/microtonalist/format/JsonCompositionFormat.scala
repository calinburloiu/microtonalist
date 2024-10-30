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
import org.calinburloiu.music.microtonalist.core._
import org.calinburloiu.music.scmidi.PitchClass
import play.api.libs.json._

import java.io.{InputStream, OutputStream}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

/**
 * Class used for serialization/deserialization of [[Composition]]s in Microtonalist's own JSON format.
 *
 * @param scaleRepo repository for retrieving scales by URI
 */
class JsonCompositionFormat(scaleRepo: ScaleRepo,
                            jsonPreprocessor: JsonPreprocessor,
                            jsonScaleFormat: JsonScaleFormat,
                            synchronousAwaitTimeout: FiniteDuration = 1 minute) extends CompositionFormat {

  import JsonCompositionFormat._

  override def read(inputStream: InputStream, baseUri: Option[URI] = None): Composition =
    Await.result(readAsync(inputStream, baseUri), synchronousAwaitTimeout)

  override def readAsync(inputStream: InputStream, baseUri: Option[URI]): Future[Composition] =
    readRepr(inputStream, baseUri)
      .loadDeferredData(scaleRepo)
      .map(fromReprToDomain)

  override def write(composition: Composition, outputStream: OutputStream): Unit = ???

  override def writeAsync(composition: Composition, outputStream: OutputStream): Future[Unit] = ???

  private def readRepr(inputStream: InputStream, baseUri: Option[URI]): CompositionRepr = {
    val json = Json.parse(inputStream)
    val context = new CompositionFormatContext

    (json \ "baseUri").validateOpt[URI].flatMap { overrideBaseUri =>
      val actualBaseUri = resolveBaseUriWithOverride(baseUri, overrideBaseUri)
      val preprocessedJson = jsonPreprocessor.preprocess(json, actualBaseUri)

      context.baseUri = actualBaseUri
      context.preprocessedJson = preprocessedJson

      (context.preprocessedJson \ "settings").validateOpt[JsObject].map(_.getOrElse(Json.obj()))
    }.flatMap { settings =>
      context.settings = settings

      implicit val intonationStandardReads: Reads[IntonationStandard] = IntonationStandardFormatComponent
        .jsonFormatComponent.readsWithRootGlobalSettings(settings)
      (context.preprocessedJson \ "intonationStandard").validateOpt[IntonationStandard]
        .map(_.getOrElse(CentsIntonationStandard))
    }.flatMap { intonationStandard =>
      context.intonationStandard = intonationStandard

      implicit val tuningSpecReprReads: Reads[TuningSpecRepr] =
        tuningSpecReprReadsFor(context.intonationStandard, context.settings)
      implicit val compositionReprReads: Reads[CompositionRepr] = Json.using[Json.WithDefaultValues]
        .reads[CompositionRepr]

      context.preprocessedJson.validate[CompositionRepr].map { compositionRepr =>
        // Save the context which is necessary for loading the deferred (async) data
        compositionRepr.context = context
        compositionRepr
      }
    } match {
      case JsSuccess(compositionRepr, _) => compositionRepr
      case error: JsError => throw new InvalidCompositionFormatException(JsError.toJson(error).toString)
    }
  }

  /**
   * Converts the objects used for JSON representation into core / domain model objects.
   */
  private def fromReprToDomain(compositionRepr: CompositionRepr): Composition = {
    val context = compositionRepr.context
    val defaultTuningMapper = TuningMapperFormatComponent.jsonFormatComponent
      .readDefaultComponent(context.settings).getOrElse(AutoTuningMapper.Default)

    def convertTuningSpec(tuningSpecRepr: TuningSpecRepr): TuningSpec = {
      val transposition = tuningSpecRepr.transposition.getOrElse(context.intonationStandard.unison)
      val name = tuningSpecRepr.name.getOrElse(DefaultScaleName)
      val intonationStandard = context.intonationStandard
      val scale = tuningSpecRepr.scale.map(_.value).getOrElse(
        Scale.create(name, Seq(intonationStandard.unison), intonationStandard))
      val tuningMapper = tuningSpecRepr.tuningMapper.getOrElse(defaultTuningMapper)

      TuningSpec(transposition, scale, tuningMapper)
    }

    val tuningRef = StandardTuningRef(PitchClass.fromNumber(compositionRepr.tuningReference.basePitchClass))
    val tuningSpecs = compositionRepr.tunings.map(convertTuningSpec)
    val tuningReducer = compositionRepr.tuningReducer.getOrElse(TuningReducer.Default)
    val globalFill = compositionRepr.globalFill.map { globalFillRepr => convertTuningSpec(globalFillRepr) }

    Composition(
      intonationStandard = context.intonationStandard,
      tuningRef = tuningRef,
      tuningSpecs = tuningSpecs,
      tuningReducer = tuningReducer,
      globalFill = globalFill,
      metadata = compositionRepr.metadata
    )
  }

  private def tuningSpecReprReadsFor(intonationStandard: IntonationStandard,
                                     rootGlobalSettings: JsObject): Reads[TuningSpecRepr] = {
    Reads { jsValue =>
      jsValue.validate[JsObject].flatMap { jsObj => (jsObj \ "name").validateOpt[String] }
    }.flatMap { name =>
      val scaleFormatContext = Some(ScaleFormatContext(name.orElse(Some(DefaultScaleName)), Some(intonationStandard)))

      implicit val intervalReads: Reads[Interval] = JsonIntervalFormat.readsFor(intonationStandard)
      implicit val scaleReads: Reads[Scale[Interval]] = jsonScaleFormat.scaleReadsWith(scaleFormatContext)
      implicit val scaleDeferrableReads: Reads[DeferrableRead[Scale[Interval], URI]] =
        DeferrableRead.reads(scaleReads, Reads.uriReads)
      implicit val tuningMapperComponentReads: Reads[TuningMapper] = TuningMapperFormatComponent
        .jsonFormatComponent.readsWithRootGlobalSettings(rootGlobalSettings)

      Json.using[Json.WithDefaultValues].reads[TuningSpecRepr]
    }
  }
}

object JsonCompositionFormat {
  private val DefaultScaleName: String = ""

  private[JsonCompositionFormat] implicit val tuningRefReprReads: Reads[OriginRepr] = Json.reads[OriginRepr]

  private[JsonCompositionFormat] implicit val tuningReducerPlayJsonFormat: Format[TuningReducer] =
    TuningReducerPlayJsonFormat

  private[JsonCompositionFormat] implicit val metadataReads: Reads[CompositionMetadata] =
    Json.reads[CompositionMetadata]

  private[format] object TuningReducerPlayJsonFormat extends ComponentPlayJsonFormat[TuningReducer] {

    import ComponentPlayJsonFormat._

    override val subComponentSpecs: Seq[SubComponentSpec[_ <: TuningReducer]] = Seq(
      SubComponentSpec("direct", classOf[DirectTuningReducer], None, Some(() => DirectTuningReducer())),
      SubComponentSpec("merge", classOf[MergeTuningReducer], None, Some(() => MergeTuningReducer())),
    )
  }
}
