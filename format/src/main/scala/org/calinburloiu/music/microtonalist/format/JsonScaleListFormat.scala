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

import org.calinburloiu.music.intonation.{Interval, RealInterval, Scale}
import org.calinburloiu.music.microtonalist.core._
import org.calinburloiu.music.scmidi.PitchClass
import play.api.libs.json._

import java.io.{InputStream, OutputStream}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

/**
 * Class used for serialization/deserialization of [[ScaleList]]s in Microtonalist's own JSON format.
 *
 * @param scaleRepo repository for retrieving scales by URI
 */
class JsonScaleListFormat(scaleRepo: ScaleRepo,
                          jsonPreprocessor: JsonPreprocessor,
                          synchronousAwaitTimeout: FiniteDuration = 1 minute) extends ScaleListFormat {
  override def read(inputStream: InputStream, baseUri: Option[URI] = None): ScaleList =
    Await.result(readAsync(inputStream, baseUri), synchronousAwaitTimeout)

  override def readAsync(inputStream: InputStream, baseUri: Option[URI]): Future[ScaleList] =
    readRepr(inputStream, baseUri)
      .loadDeferredData(scaleRepo, baseUri)
      .map(fromReprToDomain)

  override def write(scaleList: ScaleList, outputStream: OutputStream): Unit = ???

  override def writeAsync(scaleList: ScaleList, outputStream: OutputStream): Future[Unit] = ???

  private def readRepr(inputStream: InputStream, baseUri: Option[URI]): ScaleListRepr = {
    import JsonScaleListFormat._

    val json = Json.parse(inputStream)
    val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

    preprocessedJson.validate[ScaleListRepr] match {
      case JsSuccess(scaleList, _) => scaleList
      case error: JsError => throw new InvalidScaleListFormatException(JsError.toJson(error).toString)
    }
  }

  /**
   * Converts the objects used for JSON representation into core / domain model objects.
   */
  private def fromReprToDomain(scaleListRepr: ScaleListRepr): ScaleList = {
    val mapQuarterTonesLow = scaleListRepr.config
      .getOrElse(ScaleListConfigRepr.Default).mapQuarterTonesLow
    val defaultTuningMapper = AutoTuningMapper(mapQuarterTonesLow)

    val name = scaleListRepr.name.getOrElse("")
    val tuningRef = StandardTuningRef(PitchClass.fromInt(scaleListRepr.tuningReference.basePitchClass))

    val modulations = scaleListRepr.modulations.map { modulationRepr =>
      // TODO #4 For better precision we should use for unison the interval type chosen by the user
      val transposition = modulationRepr.transposition.getOrElse(RealInterval.Unison)

      val tuningMapper = modulationRepr.tuningMapper.getOrElse(defaultTuningMapper)
      val scaleMapping = ScaleMapping(modulationRepr.scale.value, tuningMapper)

      val extension = modulationRepr.extension.map { extensionScaleRef =>
        ScaleMapping(extensionScaleRef.value, defaultTuningMapper)
      }

      Modulation(transposition, scaleMapping, extension)
    }

    val tuningReducer = scaleListRepr.tuningReducer.getOrElse(TuningReducer.Default)

    val globalFillTuningMapper = scaleListRepr.globalFillTuningMapper.getOrElse(defaultTuningMapper)
    val globalFill = ScaleMapping(scaleListRepr.globalFill.value, globalFillTuningMapper)

    ScaleList(name, tuningRef, modulations, tuningReducer, globalFill)
  }
}

object JsonScaleListFormat {
  private[JsonScaleListFormat] implicit val importFormat: Format[Import] = Json.format[Import]

  private[JsonScaleListFormat] implicit val intervalReads: Reads[Interval] = JsonScaleFormat.intervalReads
  private[JsonScaleListFormat] implicit val scaleReads: Reads[Scale[Interval]] = JsonScaleFormat.jsonAllScaleReads
  private[JsonScaleListFormat] implicit val scaleDeferrableReads: Reads[DeferrableRead[Scale[Interval], Import]] =
    DeferrableRead.reads(scaleReads, importFormat)
  private[JsonScaleListFormat] implicit val scaleListBaseReprReads: Reads[OriginRepr] = Json.reads[OriginRepr]
  private[JsonScaleListFormat] implicit val scaleListConfigReprReads: Reads[ScaleListConfigRepr] =
    Json.using[Json.WithDefaultValues].reads[ScaleListConfigRepr]
  private[format] implicit val tuningMapperComponentFormat: Format[TuningMapper] =
    new ComponentFormat[TuningMapper](Seq(
      AutoTuningMapperComponentFormatSpec
    ))
  private[format] implicit val tuningReducerComponentFormat: Format[TuningReducer] =
    new ComponentFormat[TuningReducer](Seq(
      DirectTuningReducerComponentFormatSpec,
      MergeTuningReducerComponentFormatSpec
    ))
  private[JsonScaleListFormat] implicit val modulationReprReads: Reads[ModulationRepr] =
    Json.using[Json.WithDefaultValues].reads[ModulationRepr]
  private[JsonScaleListFormat] implicit val scaleListReprReads: Reads[ScaleListRepr] =
    Json.using[Json.WithDefaultValues].reads[ScaleListRepr]
}
