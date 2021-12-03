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

package org.calinburloiu.music.microtuner.format

import org.calinburloiu.music.intonation.{Interval, PitchClass, Scale}
import org.calinburloiu.music.microtuner.core._
import play.api.libs.json._

import java.io.{InputStream, OutputStream}

/**
 * Class used for serialization/deserialization of [[ScaleList]]s in JSON format.
 *
 * @param scaleLibrary repository for retrieving scales by URI
 */
class JsonScaleListFormat(scaleLibrary: ScaleLibrary) extends ScaleListFormat {

  private implicit val scaleLibraryImpl: ScaleLibrary = scaleLibrary

  /**
   * Reads a [[ScaleList]] from input stream.
   */
  override def read(inputStream: InputStream): ScaleList = {
    val repr = readRepr(inputStream).resolve

    fromReprToDomain(repr)
  }

  /**
   * Writes a [[ScaleList]] to output stream.
   */
  override def write(scaleList: ScaleList): OutputStream = ???

  private def readRepr(inputStream: InputStream): ScaleListRepr = {
    import JsonScaleListFormat._

    val json = Json.parse(inputStream)

    json.validate[ScaleListRepr] match {
      case JsSuccess(scaleList, _) => scaleList
      case error: JsError => throw new InvalidScaleListFormatException(JsError.toJson(error).toString)
    }
  }

  private def fromReprToDomain(scaleListRepr: ScaleListRepr): ScaleList = {
    val mapQuarterTonesLow = scaleListRepr.config
      .getOrElse(ScaleListConfigRepr.Default).mapQuarterTonesLow
    val defaultTuningMapper = AutoTuningMapper(mapQuarterTonesLow = false)

    val name = scaleListRepr.name.getOrElse("")
    val tuningRef = StandardTuningRef(PitchClass.fromInt(scaleListRepr.tuningReference.basePitchClass))

    val modulations = scaleListRepr.modulations.map { modulationRepr =>
      val transposition = modulationRepr.transposition.getOrElse(Interval.Unison)

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

  // TODO #31 Read this from JSON
  private val tolerance: Double = DefaultCentsTolerance

  private[JsonScaleListFormat] implicit val intervalReads: Reads[Interval] = JsonScaleFormat.intervalReads
  private[JsonScaleListFormat] implicit val scaleReads: Reads[Scale[Interval]] = JsonScaleFormat.jsonScaleReads
  private[JsonScaleListFormat] implicit val scaleRefReads: Reads[Ref[Scale[Interval]]] = Ref.refReads[Scale[Interval]]
  private[JsonScaleListFormat] implicit val scaleListBaseReprReads: Reads[OriginRepr] = Json.reads[OriginRepr]
  private[JsonScaleListFormat] implicit val scaleListConfigReprReads: Reads[ScaleListConfigRepr] =
    Json.using[Json.WithDefaultValues].reads[ScaleListConfigRepr]
  private[JsonScaleListFormat] implicit val tuningMapperPlayJsonFormat: Format[TuningMapper] =
    TuningMapperPlayJsonFormat
  private[JsonScaleListFormat] implicit val tuningReducerPlayJsonFormat: Format[TuningReducer] =
    TuningReducerPlayJsonFormat
  private[JsonScaleListFormat] implicit val modulationReprReads: Reads[ModulationRepr] =
    Json.using[Json.WithDefaultValues].reads[ModulationRepr]
  private[JsonScaleListFormat] implicit val scaleListReprReads: Reads[ScaleListRepr] =
    Json.using[Json.WithDefaultValues].reads[ScaleListRepr]

  private[format] object TuningMapperPlayJsonFormat extends ComponentPlayJsonFormat[TuningMapper] {
    import ComponentPlayJsonFormat._

    private implicit val autoReprPlayJsonFormat: Format[AutoTuningMapperRepr] =
      Json.using[Json.WithDefaultValues].format[AutoTuningMapperRepr]
    private val autoPlayJsonFormat: Format[AutoTuningMapper] = Format(
      autoReprPlayJsonFormat.map { repr =>
        AutoTuningMapper(mapQuarterTonesLow = repr.mapQuarterTonesLow,
          halfTolerance = repr.halfTolerance.getOrElse(tolerance), tolerance = tolerance)
      },
      Writes { mapper: AutoTuningMapper =>
        val repr = AutoTuningMapperRepr(mapper.mapQuarterTonesLow, halfTolerance = Some(mapper.halfTolerance))
        Json.writes[AutoTuningMapperRepr].writes(repr)
      }
    )

    override val subComponentSpecs: Seq[SubComponentSpec[_ <: TuningMapper]] = Seq(
      SubComponentSpec("auto", classOf[AutoTuningMapper], Some(autoPlayJsonFormat),
        Some(() => AutoTuningMapper(mapQuarterTonesLow = false)))
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
