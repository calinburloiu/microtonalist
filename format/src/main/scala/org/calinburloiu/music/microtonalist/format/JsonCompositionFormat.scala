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
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.*

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

  import JsonCompositionFormat.*

  override def read(inputStream: InputStream, baseUri: Option[URI] = None): Composition =
    Await.result(readAsync(inputStream, baseUri), synchronousAwaitTimeout)

  override def readAsync(inputStream: InputStream, baseUri: Option[URI]): Future[Composition] =
    readRepr(inputStream, baseUri)
      .loadDeferredData(scaleRepo)
      .map(fromReprToDomain)

  override def write(composition: Composition, outputStream: OutputStream): Unit = ???

  override def writeAsync(composition: Composition, outputStream: OutputStream): Future[Unit] = ???

  private def readRepr(inputStream: InputStream, compositionUri: Option[URI]): CompositionRepr = {
    val json = Json.parse(inputStream)
    val context = new CompositionFormatContext

    (json \ "baseUri").validateOpt[URI].flatMap { overrideBaseUri =>
      val baseUri = resolveBaseUriWithOverride(compositionUri, overrideBaseUri)
      val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

      context.uri = compositionUri
      context.baseUri = baseUri
      context.preprocessedJson = preprocessedJson

      (context.preprocessedJson \ "settings").validateOpt[JsObject].map(_.getOrElse(JsObject.empty))
    }.flatMap { settings =>
      context.settings = settings

      implicit val intonationStandardReads: Reads[IntonationStandard] = JsonIntonationStandardPluginFormat
        .readsWithRootGlobalSettings(settings)
      (context.preprocessedJson \ "intonationStandard").validateOpt[IntonationStandard]
        .map(_.getOrElse(CentsIntonationStandard))
    }.flatMap { intonationStandard =>
      context.intonationStandard = intonationStandard

      implicit val tuningReferenceReads: Reads[TuningReference] = JsonTuningReferencePluginFormat(intonationStandard)
        .readsWithRootGlobalSettings(context.settings)
      implicit val tuningSpecReprReads: Reads[TuningSpecRepr] =
        tuningSpecReprReadsFor(context.intonationStandard, context.settings)
      implicit val tuningReducerReads: Reads[TuningReducer] = JsonTuningReducerPluginFormat
        .readsWithRootGlobalSettings(context.settings)
      implicit val localFillSpecReprReads: Reads[LocalFillSpecRepr] = Json.using[Json.WithDefaultValues]
        .reads[LocalFillSpecRepr]
      implicit val fillSpecReprReads: Reads[FillSpecRepr] = Json.using[Json.WithDefaultValues].reads[FillSpecRepr]
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
   * Converts the objects used for JSON representation into domain model objects.
   */
  private def fromReprToDomain(compositionRepr: CompositionRepr): Composition = {
    val context = compositionRepr.context

    def convertTuningSpec(tuningSpecRepr: TuningSpecRepr): TuningSpec = {
      val transposition = tuningSpecRepr.transposition
      val scale = tuningSpecRepr.scale.value
      val tuningMapper = tuningSpecRepr.tuningMapper

      TuningSpec(transposition, scale, tuningMapper)
    }

    def convertFillSpec(fillSpecRepr: FillSpecRepr): FillSpec = {
      val globalFillTuningSpec = fillSpecRepr.global.map { globalFillRepr => convertTuningSpec(globalFillRepr) }

      val localFillSpec = LocalFillSpec(
        fillSpecRepr.local.foreFillEnabled,
        fillSpecRepr.local.backFillEnabled,
        fillSpecRepr.local.memoryFillEnabled,
      )

      FillSpec(localFillSpec, globalFillTuningSpec)
    }

    val tuningReference = compositionRepr.tuningReference
    val tuningSpecs = compositionRepr.tunings.map(convertTuningSpec)
    val tuningReducer = compositionRepr.tuningReducer.getOrElse(TuningReducer.Default)
    val fillSpec = convertFillSpec(compositionRepr.fill)

    Composition(
      context.uri,
      intonationStandard = context.intonationStandard,
      tuningReference = tuningReference,
      tuningSpecs = tuningSpecs,
      tuningReducer = tuningReducer,
      fill = fillSpec,
      metadata = compositionRepr.metadata,
      tracksUriOverride = compositionRepr.tracksUri
    )
  }

  private def tuningSpecReprReadsFor(intonationStandard: IntonationStandard,
                                     rootGlobalSettings: JsObject): Reads[TuningSpecRepr] = {
    (__ \ "name").readNullable[String].flatMap { maybeName =>
      val name = maybeName.getOrElse(DefaultScaleName)
      val scaleFormatContext = Some(ScaleFormatContext(Some(name), Some(intonationStandard)))

      def createDefaultDeferredScale() = AlreadyRead[Scale[Interval], URI](
        Scale.createUnisonScale(name, intonationStandard)
      )

      def createDefaultTuningMapper() = {
        if (JsonTuningMapperPluginFormat.hasGlobalSettingsForDefaultType(rootGlobalSettings)) {
          JsonTuningMapperPluginFormat.readDefaultPlugin(rootGlobalSettings)
        } else {
          JsSuccess(AutoTuningMapper.Default)
        }
      }

      implicit val intervalReads: Reads[Interval] = JsonIntervalFormat.readsFor(intonationStandard)
      implicit val scaleReads: Reads[Scale[Interval]] = jsonScaleFormat.scaleReadsWith(scaleFormatContext)
      implicit val scaleDeferrableReads: Reads[DeferrableRead[Scale[Interval], URI]] =
        DeferrableRead.reads(scaleReads, Reads.uriReads)
      implicit val tuningMapperReads: Reads[TuningMapper] = JsonTuningMapperPluginFormat
        .readsWithRootGlobalSettings(rootGlobalSettings)

      val tuningMapperPath = __ \ "tuningMapper"

      //@formatter:off
      (
        (__ \ "transposition").readWithDefault[Interval](intonationStandard.unison) and
        (__ \ "scale").readWithDefault[DeferrableRead[Scale[Interval], URI]](createDefaultDeferredScale()) and
        tuningMapperPath.readNullable[TuningMapper].flatMapResult {
          case Some(tuningMapper) => JsSuccess(tuningMapper)
          case None => createDefaultTuningMapper().repath(tuningMapperPath)
        }
      )(TuningSpecRepr.apply)
      //@formatter:on
    }
  }
}

object JsonCompositionFormat {
  private val DefaultScaleName: String = ""

  private implicit val metadataReads: Reads[CompositionMetadata] =
    Json.reads[CompositionMetadata]
}
