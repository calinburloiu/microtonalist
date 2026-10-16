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

import org.calinburloiu.music.microtonalist.rig.*
import org.calinburloiu.music.microtonalist.tuner.Tuner
import org.calinburloiu.music.microtonalist.tuner.changer.TuningChanger
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
 * @param tunerFormat The format of a track's tuner, which knows every tuner type that a rig file may hold.
 * @param tuningChangerFormat The format of a track's tuning changers, which knows every tuning changer type that a rig
 *   file may hold.
 * @param synchronousAwaitTimeout Duration used as the timeout for synchronous operations.
 */
class JsonRigFormat(jsonPreprocessor: JsonPreprocessor,
                    tunerFormat: Format[Tuner],
                    tuningChangerFormat: Format[TuningChanger],
                    synchronousAwaitTimeout: FiniteDuration = 1 minute) extends RigFormat {

  import JsonRigFormat.{trackInputSpecFormat, trackOutputSpecFormat}

  private implicit val trackSpecFormat: Format[TrackSpec] = {
    implicit val implicitTunerFormat: Format[Tuner] = tunerFormat
    implicit val implicitTuningChangerFormat: Format[TuningChanger] = tuningChangerFormat

    Json.using[Json.WithDefaultValues].format[TrackSpec]
  }
  private implicit val rigSpecFormat: Format[RigSpec] = Json.format[RigSpec]

  def readTrack(json: JsValue, baseUri: Option[URI]): TrackSpec = {
    val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

    preprocessedJson.validate[TrackSpec] match {
      case JsSuccess(trackSpec, _) => trackSpec
      case error: JsError => throw new InvalidRigFormatException(JsError.toJson(error).toString)
    }
  }

  override def readRig(inputStream: InputStream, baseUri: Option[URI]): RigSpec = {
    Await.result(readRigAsync(inputStream, baseUri), synchronousAwaitTimeout)
  }

  def readRig(json: JsValue, baseUri: Option[URI]): RigSpec = {
    val preprocessedJson = jsonPreprocessor.preprocess(json, baseUri)

    preprocessedJson.validate[RigSpec] match {
      case JsSuccess(rigSpec, _) => rigSpec
      case error: JsError => throw new InvalidRigFormatException(JsError.toJson(error).toString)
    }
  }

  override def readRigAsync(inputStream: InputStream, baseUri: Option[URI]): Future[RigSpec] = Future {
    val json = Json.parse(inputStream)

    readRig(json, baseUri)
  }

  def writeTrackAsJsValue(trackSpec: TrackSpec): JsValue = {
    Json.toJson(trackSpec)
  }

  override def writeRig(rigSpec: RigSpec, outputStream: OutputStream): Unit = {
    Await.result(writeRigAsync(rigSpec, outputStream), synchronousAwaitTimeout)
  }

  def writeRigAsJsValue(rigSpec: RigSpec): JsValue = {
    Json.toJson(rigSpec)
  }

  override def writeRigAsync(rigSpec: RigSpec, outputStream: OutputStream): Future[Unit] = Future {
    val json = writeRigAsJsValue(rigSpec)
    val writer = new PrintWriter(outputStream)

    writer.write(json.toString)
  }
}

private object JsonRigFormat {
  private implicit val trackInputSpecFormat: Format[TrackInputSpec] = JsonTrackInputSpecPluginFormat.format
  private implicit val trackOutputSpecFormat: Format[TrackOutputSpec] = JsonTrackOutputSpecPluginFormat.format
}
