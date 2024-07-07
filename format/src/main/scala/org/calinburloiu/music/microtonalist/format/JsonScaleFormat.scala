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

  // TODO #45 Remove
  import JsonIntervalFormat.legacyIntervalFormat

  val JsonScaleMediaType: MediaType = MediaType.parse("application/vnd.microtonalist-json-scale")

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

private case class ScalePitchRepr(interval: Interval)

// Note that the intonationStandard property is read before reading this representation.
private case class ScaleRepr(name: Option[String], pitches: Seq[ScalePitchRepr])
