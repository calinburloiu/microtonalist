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

import org.calinburloiu.music.microtonalist.rig.RigSpec

import java.io.{InputStream, OutputStream}
import java.net.URI
import scala.concurrent.Future

// TODO #365 Rename the extension and the JSON schemas of the tracks file after the rig.

/**
 * Trait to be extended by implementations that perform (de)serialization of rig files.
 *
 * A rig file specifies the tracks that configure MIDI instruments to play with microtones.
 *
 * @see [[RigSpec]], which defines the specification of a rig.
 */
trait RigFormat {

  /**
   * Reads a rig specification from the provided input stream and optional base URI.
   *
   * @param inputStream Input stream containing the rig data to be read.
   * @param baseUri Optional base URI used for resolving relative references in the rig data.
   * @return The rig specification parsed from the input stream.
   */
  def readRig(inputStream: InputStream, baseUri: Option[URI] = None): RigSpec

  /**
   * Reads a rig specification asynchronously from the provided input stream and optional base URI.
   *
   * @param inputStream Input stream containing the rig data to be read.
   * @param baseUri Optional base URI used for resolving relative references in the rig data.
   * @return A future containing the rig specification parsed from the input stream.
   */
  def readRigAsync(inputStream: InputStream, baseUri: Option[URI] = None): Future[RigSpec]

  /**
   * Writes the given rig specification to the provided output stream.
   *
   * @param rigSpec Rig specification to be written.
   * @param outputStream Output stream to which the rig specification will be written.
   */
  def writeRig(rigSpec: RigSpec, outputStream: OutputStream): Unit

  /**
   * Writes the given rig specification asynchronously to the provided output stream.
   *
   * @param rigSpec Rig specification to be written.
   * @param outputStream Output stream to which the rig specification will be written.
   * @return A future indicating the completion of the write operation.
   */
  def writeRigAsync(rigSpec: RigSpec, outputStream: OutputStream): Future[Unit]
}

/**
 * Exception thrown while reading an invalid rig file from an input stream.
 */
class InvalidRigFormatException(message: String, cause: Throwable = null) extends RuntimeException(message, cause)
