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

import com.google.common.net.MediaType
import org.calinburloiu.music.intonation._
import play.api.libs.functional.syntax._
import play.api.libs.json._

import java.io.{InputStream, OutputStream, PrintWriter}
import java.net.URI

/**
 * [[ScaleFormat]] implementation that provides support for Microtonalist's own JSON-based scale format.
 *
 * @param jsonPreprocessor a preprocessor instance that can replace JSON references
 */
class JsonScaleFormat(jsonPreprocessor: JsonPreprocessor) extends ScaleFormat {

  import JsonScaleFormat._

  override val metadata: ScaleFormatMetadata = ScaleFormatMetadata(
    "Microtonalist JSON Scale", Set("jscl", "json"), Set(JsonScaleMediaType, MediaType.JSON_UTF_8))

  override def read(inputStream: InputStream,
                    baseUri: Option[URI] = None,
                    context: Option[ScaleReadingContext] = None): Scale[Interval] = {
    val json = Json.parse(inputStream)
    val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

    read(preprocessedJson)
  }

  def read(inputJson: JsValue): Scale[Interval] = inputJson.validate(jsonAllScaleReads) match {
    case JsSuccess(scale: Scale[Interval], _) => scale
    case error: JsError => throw new InvalidJsonScaleException(JsError.toJson(error).toString)
  }

  override def write(scale: Scale[Interval], outputStream: OutputStream): Unit = {
    val json = writeAsJsValue(scale)
    val writer = new PrintWriter(outputStream)
    writer.write(json.toString)
  }

  def writeAsJsValue(scale: Scale[Interval]): JsValue = Json.toJson(scale)(jsonVerboseScaleFormat)
}

object JsonScaleFormat {

  val JsonScaleMediaType: MediaType = MediaType.parse("application/vnd.microtonalist-json-scale")

  val ErrorExpectingIntervalFor: Map[String, String] = Map(
    CentsIntonationStandard.typeName -> "error.expecting.intervalForCentsIntonationStandard",
    JustIntonationStandard.typeName -> "error.expecting.intervalForJustIntonationStandard",
    EdoIntonationStandard.typeName -> "error.expecting.intervalForEdoIntonationStandard"
  )

  private val intervalReads: Reads[Interval] = Reads.StringReads.collect(
    JsonValidationError("error.expecting.HuygensFokkerScalaScalePitch")
  )(
    Function.unlift(Interval.fromScalaTuningInterval)
  ).orElse {
    Reads.JsNumberReads.map { jsNumber =>
      CentsInterval(jsNumber.value.doubleValue)
    }
  }

  private val intervalWrites: Writes[Interval] = Writes {
    case CentsInterval(centsValue) => JsNumber(centsValue)
    case RatioInterval(numerator, denominator) => JsString(s"$numerator/$denominator")
    case edoInterval: EdoInterval if edoInterval.edo % 12 == 0 =>
      val (semitones, deviation) = edoInterval.countRelativeToStandard
      Json.arr(JsNumber(semitones), JsNumber(deviation))
    case EdoInterval(_, count) => JsNumber(count)
    case interval: Interval => JsNumber(interval.cents)
  }

  @deprecated
  private[format] implicit val intervalFormat: Format[Interval] = Format(intervalReads, intervalWrites)

  private def intervalReadsFor(intonationStandard: IntonationStandard): Reads[Interval] = {
    lazy val centsReads: Reads[Interval] = Reads.JsNumberReads.map { jsNumber =>
      CentsInterval(jsNumber.value.doubleValue)
    }
    lazy val ratioReads: Reads[Interval] = Reads.StringReads
      .collect(JsonValidationError(ErrorExpectingIntervalFor(JustIntonationStandard.typeName)))(
        Function.unlift(Interval.fromRatioString)
      )

    intonationStandard match {
      case CentsIntonationStandard => centsReads orElse ratioReads orElse
        Reads.failed(ErrorExpectingIntervalFor(CentsIntonationStandard.typeName))
      case JustIntonationStandard => ratioReads orElse
        Reads.failed(ErrorExpectingIntervalFor(JustIntonationStandard.typeName))
      case EdoIntonationStandard(countPerOctave) => Reads.JsNumberReads.map { jsNumber =>
        EdoInterval(countPerOctave, jsNumber.value.intValue).asInstanceOf[Interval]
      } orElse Reads.JsArrayReads.flatMapResult { jsArr =>
        if (jsArr.value.size == 2) {
          val semitones = jsArr.value.head.asOpt[Int]
          val deviation = jsArr.value(1).asOpt[Int]
          if (semitones.isDefined && deviation.isDefined) {
            JsSuccess(EdoInterval(countPerOctave, (semitones.get, deviation.get)).asInstanceOf[Interval])
          } else {
            JsError()
          }
        } else {
          JsError()
        }
      } orElse ratioReads orElse Reads.failed(ErrorExpectingIntervalFor(EdoIntonationStandard.typeName))
    }
  }

  private[format] def intervalFormatFor(intonationStandard: IntonationStandard): Format[Interval] = Format(
    intervalReadsFor(intonationStandard),
    intervalWrites
  )

  //@formatter:off
  private[format] val jsonVerboseScaleFormat: Format[Scale[Interval]] = (
    (__ \ "intervals").format[Seq[Interval]] and
    (__ \ "name").formatNullable[String]
  ) ({ (pitches: Seq[Interval], name: Option[String]) =>
    Scale.create(name.getOrElse(""), pitches)
  }, { scale =>
    (scale.intervals, Some(scale.name))
  })
  //@formatter:on

  private[format] val jsonConciseScaleReads: Reads[Scale[Interval]] = __.read[Seq[Interval]]
    .map { pitches => Scale.create("", pitches) }

  private[format] val jsonAllScaleReads: Reads[Scale[Interval]] = jsonVerboseScaleFormat orElse jsonConciseScaleReads
}

class InvalidJsonScaleException(message: String, cause: Throwable = null)
  extends InvalidScaleFormatException(message, cause)
