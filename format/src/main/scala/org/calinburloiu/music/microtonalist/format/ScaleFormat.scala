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
   * @param inputStream scale source
   * @param baseUri     optional base URI to be used when for resolving relative URI references found in the scale
   *                    that is read
   * @return the scale read
   */
  def read(inputStream: InputStream, baseUri: Option[URI] = None): Scale[Interval]

  /**
   * Writes the given scale to the given [[OutputStream]].
   *
   * @param scale        scale to write
   * @param outputStream target where the scale should be written
   */
  def write(scale: Scale[Interval], outputStream: OutputStream): Unit
}

/**
 * Base exception thrown when a scale being read is invalid.
 */
class InvalidScaleFormatException(message: String, cause: Throwable = null) extends Exception(message, cause)
