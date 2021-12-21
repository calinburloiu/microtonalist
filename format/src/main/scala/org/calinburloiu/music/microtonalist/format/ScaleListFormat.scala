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

import org.calinburloiu.music.microtonalist.core.ScaleList

import java.io.{InputStream, OutputStream}
import java.net.URI

/**
 * Trait extended for serialization/deserialization of [[ScaleList]]s.
 */
trait ScaleListFormat {

  /**
   * Reads a [[ScaleList]] from an [[InputStream]].
   * @param inputStream stream to read input from
   * @param baseUri an optional base URI for resolving relative URIs present in the input scale list
   * @return the scale list read
   */
  def read(inputStream: InputStream, baseUri: Option[URI] = None): ScaleList

  /**
   * Writes a [[ScaleList]] to [[OutputStream]].
   */
  def write(scaleList: ScaleList, outputStream: OutputStream): Unit
}

/**
 * Exception thrown while reading an invalid scale list from an input stream.
 */
class InvalidScaleListFormatException(message: String, cause: Throwable = null) extends Exception(message, cause)
