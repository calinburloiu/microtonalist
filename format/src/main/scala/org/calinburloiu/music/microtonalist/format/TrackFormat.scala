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

/**
 * Trait to be extended by implementations that perform (de)serialization of tracks files.
 *
 * Tracks files define specifications for tracks that configure MIDI instruments to play with microtones.
 *
 * @see [[TrackSpecs]], which defines the specification of tracks.
 */
trait TrackFormat {

  /**
   * Reads track specifications from the provided input stream and optional base URI.
   *
   * @param inputStream Input stream containing the tracks data to be read.
   * @param baseUri     Optional base URI used for resolving relative references in the tracks data.
   * @return The track specifications parsed from the input stream.
   */
  def readTracks(inputStream: InputStream, baseUri: Option[URI] = None): TrackSpecs

  /**
   * Reads track specifications asynchronously from the provided input stream and optional base URI.
   *
   * @param inputStream Input stream containing the tracks data to be read.
   * @param baseUri     Optional base URI used for resolving relative references in the tracks data.
   * @return A future containing the track specifications parsed from the input stream.
   */
  def readTracksAsync(inputStream: InputStream, baseUri: Option[URI] = None): Future[TrackSpecs]

  /**
   * Writes the given track specifications to the provided output stream.
   *
   * @param trackSpecs   Track specifications to be written.
   * @param outputStream Output stream to which the track specifications will be written.
   */
  def writeTracks(trackSpecs: TrackSpecs, outputStream: OutputStream): Unit

  /**
   * Writes the given track specifications asynchronously to the provided output stream.
   *
   * @param trackSpecs   Track specifications to be written.
   * @param outputStream Output stream to which the track specifications will be written.
   * @return A future indicating the completion of the write operation.
   */
  def writeTracksAsync(trackSpecs: TrackSpecs, outputStream: OutputStream): Future[Unit]
}

/**
 * Exception thrown while reading an invalid tracks file from an input stream.
 */
class InvalidTrackFormatException(message: String, cause: Throwable = null) extends RuntimeException(message, cause)
