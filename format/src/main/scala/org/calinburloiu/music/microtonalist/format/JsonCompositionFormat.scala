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

import org.calinburloiu.music.intonation.{Interval, IntonationStandard, RealInterval, Scale}
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
  override def read(inputStream: InputStream, baseUri: Option[URI] = None): Composition =
    Await.result(readAsync(inputStream, baseUri), synchronousAwaitTimeout)

  override def readAsync(inputStream: InputStream, baseUri: Option[URI]): Future[Composition] =
    readRepr(inputStream, baseUri)
      .loadDeferredData(scaleRepo, baseUri)
      .map(fromReprToDomain)

  override def write(composition: Composition, outputStream: OutputStream): Unit = ???

  override def writeAsync(composition: Composition, outputStream: OutputStream): Future[Unit] = ???

  private def readRepr(inputStream: InputStream, baseUri: Option[URI]): CompositionRepr = {
    import JsonCompositionFormat._

    val json = Json.parse(inputStream)
    val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

    preprocessedJson.validate[CompositionFormatContext].flatMap { context =>
      implicit val intervalReads: Reads[Interval] = JsonIntervalFormat.readsFor(context.intonationStandard)
      implicit val scaleReads: Reads[Scale[Interval]] = jsonScaleFormat.scaleReadsWith(
        Some(ScaleFormatContext(name = Some(""), Some(context.intonationStandard)))
      )
      implicit val scaleDeferrableReads: Reads[DeferrableRead[Scale[Interval], Import]] =
        DeferrableRead.reads(scaleReads, importFormat)
      implicit val tuningSpecReprReads: Reads[TuningSpecRepr] =
        Json.using[Json.WithDefaultValues].reads[TuningSpecRepr]
      implicit val compositionReprReads: Reads[CompositionRepr] =
        Json.using[Json.WithDefaultValues].reads[CompositionRepr]

      preprocessedJson.validate[CompositionRepr].map { compositionRepr =>
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
    val mapQuarterTonesLow = compositionRepr.config
      .getOrElse(CompositionConfigRepr.Default).mapQuarterTonesLow
    val defaultTuningMapper = AutoTuningMapper(mapQuarterTonesLow)

    def convertTuningSpec(tuningSpecRepr: TuningSpecRepr): TuningSpec = {
      val transposition = tuningSpecRepr.transposition.getOrElse(context.intonationStandard.unison)

      val tuningMapper = tuningSpecRepr.tuningMapper.getOrElse(defaultTuningMapper)
      val scaleMapping = ScaleMapping(tuningSpecRepr.scale.value, tuningMapper)

      TuningSpec(transposition, scaleMapping)
    }

    val name = compositionRepr.name.getOrElse("")
    val tuningRef = StandardTuningRef(PitchClass.fromInt(compositionRepr.tuningReference.basePitchClass))

    val tuningSpecs = compositionRepr.tunings.map(convertTuningSpec)

    val tuningReducer = compositionRepr.tuningReducer.getOrElse(TuningReducer.Default)

    val globalFill = compositionRepr.globalFill.map { globalFillRepr => convertTuningSpec(globalFillRepr) }

    Composition(name, context.intonationStandard, tuningRef, tuningSpecs, tuningReducer, globalFill)
  }
}

object JsonCompositionFormat {
  // TODO #31 Read this from JSON
  private val tolerance: Double = DefaultCentsTolerance

  private[JsonCompositionFormat] implicit val intonationStandardReads: Reads[IntonationStandard] =
    IntonationStandardComponentFormat.componentJsonFormat
  private[JsonCompositionFormat] implicit val contextReads: Reads[CompositionFormatContext] =
    Json.using[Json.WithDefaultValues].reads[CompositionFormatContext]

  private[JsonCompositionFormat] implicit val importFormat: Format[Import] = Json.format[Import]

  private[JsonCompositionFormat] implicit val compositionBaseReprReads: Reads[OriginRepr] = Json.reads[OriginRepr]
  private[JsonCompositionFormat] implicit val compositionConfigReprReads: Reads[CompositionConfigRepr] =
    Json.using[Json.WithDefaultValues].reads[CompositionConfigRepr]
  private[JsonCompositionFormat] implicit val tuningMapperPlayJsonFormat: Format[TuningMapper] =
    TuningMapperPlayJsonFormat
  private[JsonCompositionFormat] implicit val tuningReducerPlayJsonFormat: Format[TuningReducer] =
    TuningReducerPlayJsonFormat

  private[format] object TuningMapperPlayJsonFormat extends ComponentPlayJsonFormat[TuningMapper] {

    import ComponentPlayJsonFormat._

    private implicit val autoReprPlayJsonFormat: Format[AutoTuningMapperRepr] =
      Json.using[Json.WithDefaultValues].format[AutoTuningMapperRepr]
    private val autoPlayJsonFormat: Format[AutoTuningMapper] = Format(
      autoReprPlayJsonFormat.map { repr =>
        AutoTuningMapper(shouldMapQuarterTonesLow = repr.mapQuarterTonesLow,
          quarterToneTolerance = repr.halfTolerance.getOrElse(DefaultQuarterToneTolerance), tolerance = tolerance)
      },
      Writes { mapper: AutoTuningMapper =>
        val repr = AutoTuningMapperRepr(mapper.shouldMapQuarterTonesLow, halfTolerance = Some(mapper
          .quarterToneTolerance))
        autoReprPlayJsonFormat.writes(repr)
      }
    )

    override val subComponentSpecs: Seq[SubComponentSpec[_ <: TuningMapper]] = Seq(
      SubComponentSpec("auto", classOf[AutoTuningMapper], Some(autoPlayJsonFormat),
        Some(() => AutoTuningMapper(shouldMapQuarterTonesLow = false)))
    )
  }

  private[format] object TuningReducerPlayJsonFormat extends ComponentPlayJsonFormat[TuningReducer] {

    import ComponentPlayJsonFormat._

    override val subComponentSpecs: Seq[SubComponentSpec[_ <: TuningReducer]] = Seq(
      SubComponentSpec("direct", classOf[DirectTuningReducer], None, Some(() => DirectTuningReducer())),
      SubComponentSpec("merge", classOf[MergeTuningReducer], None, Some(() => MergeTuningReducer())),
    )
  }
}
