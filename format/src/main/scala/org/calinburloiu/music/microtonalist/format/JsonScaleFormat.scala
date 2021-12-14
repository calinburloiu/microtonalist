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

import org.calinburloiu.music.intonation.{CentsInterval, Interval, RatioInterval, Scale}
import play.api.libs.functional.syntax._
import play.api.libs.json._

import java.io.{InputStream, OutputStream, PrintWriter}

class JsonScaleFormat extends ScaleFormat {
  import JsonScaleFormat._

  override def read(inputStream: InputStream): Scale[Interval] = {
    val inputJson = Json.parse(inputStream)

    read(inputJson)
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

object JsonScaleFormat extends JsonScaleFormat {

  implicit val intervalReads: Reads[Interval] = Reads.StringReads.collect(
    JsonValidationError("error.expecting.ScalaAppScalePitch")
  )(
    Function.unlift(Interval.fromScalaTuningInterval)
  ).orElse {
    Reads.JsNumberReads.map { jsNumber =>
      CentsInterval(jsNumber.value.doubleValue)
    }
  }

  implicit val intervalWrites: Writes[Interval] = Writes {
    case RatioInterval(numerator, denominator) => JsString(s"$numerator/$denominator")
    case interval: Interval => JsNumber(interval.cents)
  }

  val jsonVerboseScaleFormat: Format[Scale[Interval]] = (
    (__ \ "intervals").format[Seq[Interval]] and
    (__ \ "name").formatNullable[String]
  )({ (pitches: Seq[Interval], name: Option[String]) =>
    ScaleFormat.createScale(name.getOrElse(""), pitches)
  }, { scale =>
    (scale.intervals, Some(scale.name))
  })

  val jsonConciseScaleReads: Reads[Scale[Interval]] = __.read[Seq[Interval]]
    .map { pitches => ScaleFormat.createScale("", pitches) }

  val jsonAllScaleReads: Reads[Scale[Interval]] = jsonVerboseScaleFormat orElse jsonConciseScaleReads
}

class InvalidJsonScaleException(message: String, cause: Throwable = null)
  extends InvalidScaleFormatException(message, cause)
