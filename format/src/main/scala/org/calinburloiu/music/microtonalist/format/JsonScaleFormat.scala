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

import com.google.common.net.MediaType
import org.calinburloiu.music.intonation.*
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import java.io.{InputStream, OutputStream, PrintWriter}
import java.net.URI

/**
 * [[ScaleFormat]] implementation that provides support for Microtonalist's own JSON-based scale format.
 *
 * @param jsonPreprocessor a preprocessor instance that can replace JSON references
 */
class JsonScaleFormat(jsonPreprocessor: JsonPreprocessor) extends ScaleFormat {

  import JsonScaleFormat.*

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
   * This overloaded method does not use the preprocessor as the other stream-based one uses.
   *
   * @see [[read]]
   */
  def read(inputJson: JsValue,
           context: Option[ScaleFormatContext]): Scale[Interval] = {
    scaleReadsWith(context).reads(inputJson) match {
      case JsSuccess(scale, _) => scale
      case error: JsError if error.errors.flatMap(_._2).exists(_.message == ErrorMissingContext) =>
        throw new MissingContextScaleFormatException
      case error: JsError => throw new InvalidJsonScaleException(error)
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
      name = if (scale.name.trim.isBlank) None else Some(scale.name),
      intonationStandard = scale.intonationStandard
    )

    val intonationStandard = context.flatMap(_.intonationStandard).orElse(scale.intonationStandard)
      .getOrElse(throw new MissingContextScaleFormatException)
    // Making sure that the intonation standard that we output is still consistent with the intervals
    val intervals = scale.convertToIntonationStandard(intonationStandard).map(_.scale.intervals).getOrElse {
      throw new IncompatibleIntervalsScaleFormatException
    }

    val contextJson = Json.toJson(scaleSelfContext)(contextFormatWith(overrideContext = context)).asInstanceOf[JsObject]
    val pitchesJson = Json.toJson(intervals)(pitchesFormatFor(intonationStandard)).asInstanceOf[JsObject]

    contextJson ++ pitchesJson
  }

  def scaleReadsWith(overrideContext: Option[ScaleFormatContext]): Reads[Scale[Interval]] = {
    Reads { jsValue =>
      contextFormatWith(overrideContext)
        .reads(jsValue)
        .flatMap {
          case ScaleFormatContext(Some(name), Some(intonationStandard)) =>
            pitchesFormatFor(intonationStandard).reads(jsValue).map { intervals =>
              Scale.create(name, intervals, intonationStandard)
            }
          case _ => JsError(ErrorMissingContext)
        }
    }
  }

  private[format] def contextFormatWith(overrideContext: Option[ScaleFormatContext]): Format[ScaleFormatContext] = {
    val overrideName = overrideContext.flatMap(_.name)
    val overrideIntonationStandard = overrideContext.flatMap(_.intonationStandard)

    def checkConversionQuality(from: Option[IntonationStandard]): Unit = (from, overrideIntonationStandard) match {
      case (Some(fromValue), Some(toValue)) if fromValue.conversionQualityTo(toValue) == IntonationConversionQuality
        .Impossible =>
        throw new IncompatibleIntervalsScaleFormatException
      case _ =>
    }

    //@formatter:off
    (
      (__ \ "name").formatNullable[String] and
      (__ \ "intonationStandard").formatNullable[IntonationStandard]
    )(
      { (name, intonationStandard) =>
        checkConversionQuality(intonationStandard)
        ScaleFormatContext(overrideName.orElse(name), overrideIntonationStandard.orElse(intonationStandard))
      },
      { (context: ScaleFormatContext) =>
        checkConversionQuality(context.intonationStandard)
        (overrideName.orElse(context.name), overrideIntonationStandard.orElse(context.intonationStandard))
      }
    )
    //@formatter:on
  }
}

object JsonScaleFormat {

  val JsonScaleMediaType: MediaType = MediaType.parse("application/vnd.microtonalist.scale+json")

  val ErrorMissingContext: String = "error.scale.missingContext"

  private[JsonScaleFormat] implicit val intonationStandardFormat: Format[IntonationStandard] =
    JsonIntonationStandardPluginFormat.format

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
  extends InvalidScaleFormatException(message, cause) {

  def this(jsError: JsError) = this(JsError.toJson(jsError).toString)
}
