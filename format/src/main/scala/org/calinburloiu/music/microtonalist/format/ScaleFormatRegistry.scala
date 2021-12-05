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

/**
 * Registry that provides a mapping between the scale media type or extension and the format class used for
 * deserializing it.
 *
 * @param bindings mapping between the scale media type or extension and the format class
 */
class ScaleFormatRegistry private(val bindings: Seq[(FormatIdentifier, ScaleFormat)]) {

  private[this] val byExtension: Map[String, ScaleFormat] = (for {
    (FormatIdentifier(_, extensions, _), scaleReader) <- bindings
    extension <- extensions
  } yield extension -> scaleReader).toMap

  private[this] val byMediaType: Map[String, ScaleFormat] = (for {
    (FormatIdentifier(_, _, mediaTypes), scaleReader) <- bindings
    mediaType <- mediaTypes
  } yield mediaType -> scaleReader).toMap

  /**
   * Gets the format by file extension.
   *
   * @throws UnsupportedOperationException if no [[ScaleFormat]] is registered for the extension
   */
  def getByExtension(extension: String): ScaleFormat = byExtension.getOrElse(extension, throw
    new UnsupportedOperationException(s"No ScaleReader registered for extension '$extension'"))

  /**
   * Gets the format by media type (MIME type).
   *
   * @throws UnsupportedOperationException if no [[ScaleFormat]] is registered for the media type
   */
  def getByMediaType(mediaType: String): ScaleFormat = byMediaType.getOrElse(mediaType, throw
    new UnsupportedOperationException(s"No ScaleReader registered for media type '$mediaType'"))
}

object ScaleFormatRegistry extends ScaleFormatRegistry(Seq(
  (FormatIdentifier("Scala Application Scale", Set("scl")), ScalaTuningFileFormat),
  (FormatIdentifier("JSON Scale", Set("jscl", "json")), JsonScaleFormat)
))
