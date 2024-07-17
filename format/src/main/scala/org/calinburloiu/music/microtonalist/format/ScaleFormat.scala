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

import org.calinburloiu.music.intonation._

import java.io.{InputStream, OutputStream}
import java.net.URI

/**
 * Trait to be extended by implementations that perform (de)serialization of scales.
 */
trait ScaleFormat {
  val metadata: ScaleFormatMetadata

  /**
   * Reads a scale from an [[InputStream]].
   *
   * @param inputStream Scale source.
   * @param baseUri     Optional base URI to be used when for resolving relative URI references found in the scale
   *                    that is read.
   * @param context     If reading occurs in a context, such as in a composition file, then a context may be set with
   *                    certain properties, that may be omitted from the serialized scale.
   * @return the scale read
   */
  def read(inputStream: InputStream,
           baseUri: Option[URI] = None,
           context: Option[ScaleFormatContext] = None): Scale[Interval]

  /**
   * Writes the given scale to the given [[OutputStream]].
   *
   * @param scale        Scale to write.
   * @param outputStream Target where the scale should be written.
   * @param context      If writing occurs in a context, such as from a composition file, then a context may be set
   *                     with certain properties, that may be omitted from the serialized scale.
   */
  def write(scale: Scale[Interval], outputStream: OutputStream, context: Option[ScaleFormatContext] = None): Unit
}

/**
 * Base exception thrown when a scale being read is invalid.
 */
class InvalidScaleFormatException(message: String, cause: Throwable = null) extends Exception(message, cause)

class MissingContextScaleFormatException extends InvalidScaleFormatException(
  "If name and intonationStandard properties are missing from the scale definition, they must be present in the " +
    "surrounding context!")

class IncompatibleIntervalsScaleFormatException extends InvalidScaleFormatException(
  "The scale intervals are incompatible with the intonation standard"
)
