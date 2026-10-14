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

import org.calinburloiu.music.microtonalist.tuner.*
import play.api.libs.json.*

import java.io.{InputStream, OutputStream, PrintWriter}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

/**
 * A tracks file format implementation used for serialization/deserialization in JSON format.
 *
 * @param jsonPreprocessor A preprocessor instance that can replace JSON references.
 * @param synchronousAwaitTimeout Duration used as the timeout for synchronous operations.
 */
class JsonTrackFormat(jsonPreprocessor: JsonPreprocessor,
                      synchronousAwaitTimeout: FiniteDuration = 1 minute) extends TrackFormat {

  def readTrack(json: JsValue, baseUri: Option[URI]): TrackSpec = {
    val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

    import JsonTrackFormat.trackSpecFormat
    preprocessedJson.validate[TrackSpec] match {
      case JsSuccess(trackSpec, _) => trackSpec
      case error: JsError => throw new InvalidTrackFormatException(JsError.toJson(error).toString)
    }
  }

  override def readTracks(inputStream: InputStream, baseUri: Option[URI]): RigSpec = {
    Await.result(readTracksAsync(inputStream, baseUri), synchronousAwaitTimeout)
  }

  def readTracks(json: JsValue, baseUri: Option[URI]): RigSpec = {
    val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

    import JsonTrackFormat.rigSpecFormat
    preprocessedJson.validate[RigSpec] match {
      case JsSuccess(rigSpec, _) => rigSpec
      case error: JsError => throw new InvalidTrackFormatException(JsError.toJson(error).toString)
    }
  }

  override def readTracksAsync(inputStream: InputStream, baseUri: Option[URI]): Future[RigSpec] = Future {
    val json = Json.parse(inputStream)

    readTracks(json, baseUri)
  }

  def writeTrackAsJsValue(trackSpec: TrackSpec): JsValue = {
    import JsonTrackFormat.trackSpecFormat
    Json.toJson(trackSpec)
  }

  override def writeTracks(rigSpec: RigSpec, outputStream: OutputStream): Unit = {
    Await.result(writeTracksAsync(rigSpec, outputStream), synchronousAwaitTimeout)
  }

  def writeTracksAsJsValue(rigSpec: RigSpec): JsValue = {
    import JsonTrackFormat.rigSpecFormat
    Json.toJson(rigSpec)
  }

  override def writeTracksAsync(rigSpec: RigSpec, outputStream: OutputStream): Future[Unit] = Future {
    val json = writeTracksAsJsValue(rigSpec)
    val writer = new PrintWriter(outputStream)

    writer.write(json.toString)
  }
}

private object JsonTrackFormat {
  private implicit val trackInputSpecFormat: Format[TrackInputSpec] = JsonTrackInputSpecPluginFormat.format
  private implicit val tuningChangerFormat: Format[TuningChanger] = JsonTuningChangerPluginFormat.format
  private implicit val tunerFormat: Format[Tuner] = JsonTunerPluginFormat.format
  private implicit val trackOutputSpecFormat: Format[TrackOutputSpec] = JsonTrackOutputSpecPluginFormat.format

  private implicit val trackSpecFormat: Format[TrackSpec] = Json.using[Json.WithDefaultValues].format[TrackSpec]
  private implicit val rigSpecFormat: Format[RigSpec] = Json.format[RigSpec]
}
