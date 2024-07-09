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
class JsonScaleFormat(jsonPreprocessor: JsonPreprocessor,
                      implicit val intonationStandardComponentFormat: Format[IntonationStandard]) extends ScaleFormat {

  import JsonScaleFormat._

  override val metadata: ScaleFormatMetadata = ScaleFormatMetadata(
    "Microtonalist JSON Scale", Set("jscl", "json"), Set(JsonScaleMediaType, MediaType.JSON_UTF_8))

  override def read(inputStream: InputStream,
                    baseUri: Option[URI] = None,
                    context: Option[ScaleFormatContext] = None): Scale[Interval] = {
    val json = Json.parse(inputStream)
    val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

    read(preprocessedJson, context)
  }

  /**
   * Reads a scale from a raw JSON object.
   *
   * @see [[read]]
   */
  def read(inputJson: JsValue,
           context: Option[ScaleFormatContext]): Scale[Interval] = {
    contextFormatWith(context).reads(inputJson) match {
      case JsSuccess(ScaleFormatContext(Some(name), Some(intonationStandard)), _) =>
        readPitches(inputJson, name, intonationStandard)
      case JsSuccess(_, _) => throw new MissingContextScaleFormatException
      case error: JsError => throw new InvalidJsonScaleException(JsError.toJson(error).toString)
    }
  }

  override def write(scale: Scale[Interval],
                     outputStream: OutputStream,
                     context: Option[ScaleFormatContext] = None): Unit = {
    val json = writeAsJsValue(scale)
    val writer = new PrintWriter(outputStream)
    writer.write(json.toString)
  }

  def writeAsJsValue(scale: Scale[Interval], context: Option[ScaleFormatContext] = None): JsValue = {
    val scaleSelfContext = ScaleFormatContext(
      name = if (scale.name.isBlank) None else Some(scale.name),
      intonationStandard = scale.intonationStandard
    )
    val intonationStandard = scale.intonationStandard.orElse(context.flatMap(_.intonationStandard))
      .getOrElse(throw new MissingContextScaleFormatException)
    // Making sure that the intonation standard that we output is still consistent with the intervals
    val intervals = scale.convertToIntonationStandard(intonationStandard).map(_.intervals).getOrElse {
      // TODO #45 Better exception
      throw new IllegalArgumentException("")
    }

    val contextJson = Json.toJson(scaleSelfContext)(contextFormatWith(fallbackContext = context)).asInstanceOf[JsObject]
    val pitchesJson = Json.toJson(intervals)(pitchesFormatFor(intonationStandard)).asInstanceOf[JsObject]

    contextJson ++ pitchesJson
  }

  // TODO #45 Should we leverage this for reading to reduce code duplication?
  def scaleReadsWith(fallbackContext: Option[ScaleFormatContext]): Reads[Scale[Interval]] = {
    Reads { jsValue =>
      contextFormatWith(fallbackContext)
        .reads(jsValue)
        .flatMap {
          case ScaleFormatContext(Some(name), Some(intonationStandard)) =>
            pitchesFormatFor(intonationStandard).reads(jsValue).map { intervals => Scale.create(name, intervals) }
          // TODO #45 Improve the error message and make a constant for it
          case _ => JsError("error.missingContextForScale")
        }
    }
  }

  private def readPitches(inputJson: JsValue, name: String, intonationStandard: IntonationStandard): Scale[Interval] = {
    pitchesFormatFor(intonationStandard).reads(inputJson) match {
      case JsSuccess(intervals, _) => Scale.create(name, intervals)
      case error: JsError => throw new InvalidJsonScaleException(JsError.toJson(error).toString)
    }
  }

  private[format] def contextFormatWith(fallbackContext: Option[ScaleFormatContext]): Format[ScaleFormatContext] = {
    def getFallbackName = fallbackContext.flatMap(_.name)

    def getFallbackIntonationStandard = fallbackContext.flatMap(_.intonationStandard)

    //@formatter:off
    (
      (__ \ "name").formatNullableWithDefault[String](getFallbackName) and
      (__ \ "intonationStandard").formatNullableWithDefault[IntonationStandard](getFallbackIntonationStandard)
    )(
      { (name, intonationStandard) => ScaleFormatContext(name, intonationStandard) },
      { context: ScaleFormatContext => (context.name, context.intonationStandard) }
    )
    //@formatter:on
  }
}

object JsonScaleFormat {

  val JsonScaleMediaType: MediaType = MediaType.parse("application/vnd.microtonalist-json-scale")

  private[format] def pitchIntervalFormatFor(intonationStandard: IntonationStandard): Format[Interval] = {
    val intervalFormat: Format[Interval] = JsonIntervalFormat.formatFor(intonationStandard)
    val intervalReads: Reads[Interval] = intervalFormat
    val intervalWrites: Writes[Interval] = intervalFormat

    Format(intervalReads orElse (__ \ "interval").read[Interval](intervalReads), intervalWrites)
  }

  private[format] def pitchesFormatFor(intonationStandard: IntonationStandard): Format[Seq[Interval]] = {
    val pitchIntervalFormat: Format[Interval] = pitchIntervalFormatFor(intonationStandard)
    implicit val pitchIntervalSeqFormat: Format[Seq[Interval]] = Format(
      Reads.seq[Interval](pitchIntervalFormat), Writes.seq[Interval](pitchIntervalFormat)
    )
    val pitchesPropertyFormat: Format[Seq[Interval]] = (__ \ "pitches").format[Seq[Interval]]

    Format(pitchesPropertyFormat orElse __.format[Seq[Interval]], pitchesPropertyFormat)
  }
}

class InvalidJsonScaleException(message: String, cause: Throwable = null)
  extends InvalidScaleFormatException(message, cause)
