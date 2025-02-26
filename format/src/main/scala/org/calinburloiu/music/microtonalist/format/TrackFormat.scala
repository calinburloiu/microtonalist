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

import org.calinburloiu.music.microtonalist.tuner.TrackSpecs

import java.io.{InputStream, OutputStream}
import java.net.URI
import scala.concurrent.Future

trait TrackFormat {

  def readTracks(inputStream: InputStream, baseUri: Option[URI] = None): TrackSpecs

  def readTracksAsync(inputStream: InputStream, baseUri: Option[URI] = None): Future[TrackSpecs]

  def writeTracks(trackSpecs: TrackSpecs, outputStream: OutputStream): Unit

  def writeTracksAsync(trackSpecs: TrackSpecs, outputStream: OutputStream): Future[Unit]
}

class InvalidTrackFormatException(message: String, cause: Throwable = null) extends RuntimeException(message, cause)
