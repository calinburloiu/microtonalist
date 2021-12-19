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

import com.google.common.io.Files
import com.google.common.net.MediaType

import java.net.URI

/**
 * Registry that provides a mapping between the scale media type or extension and the format class used for
 * deserializing it.
 *
 * @param scaleFormats [[ScaleFormat]]s that should be indexed in the registry
 */
class ScaleFormatRegistry(val scaleFormats: Seq[ScaleFormat]) {

  private[this] val extensionMap: Map[String, ScaleFormat] = (for {
    scaleFormat <- scaleFormats
    extension <- scaleFormat.metadata.extensions
  } yield extension -> scaleFormat).toMap

  private[this] val mediaTypeMap: Map[MediaType, ScaleFormat] = (for {
    scaleFormat <- scaleFormats
    mediaType <- scaleFormat.metadata.mediaTypes
  } yield mediaType -> scaleFormat).toMap

  /**
   * Attempts to get the format by media type and if not provided it extracts the file extension from the URI' path
   * and gets the format by that extension.
   * @param uri scale file URI
   * @param mediaType media type for the scale
   */
  def get(uri: URI, mediaType: Option[MediaType]): Option[ScaleFormat] = mediaType match {
    case Some(actualMediaType) => getByMediaType(actualMediaType)
    case None =>
      val extension = Files.getFileExtension(uri.getPath)
      getByExtension(extension)
  }

  /**
   * Gets the format by file extension.
   *
   * @throws UnsupportedOperationException if no [[ScaleFormat]] is registered for the extension
   */
  def getByExtension(extension: String): Option[ScaleFormat] = extensionMap.get(extension)

  /**
   * Gets the format by media type (MIME type).
   *
   * @throws UnsupportedOperationException if no [[ScaleFormat]] is registered for the media type
   */
  def getByMediaType(mediaType: MediaType): Option[ScaleFormat] = {
    def findSequentially(): Option[ScaleFormat] = mediaTypeMap
      .find { case (formatMediaType, _) => mediaType is formatMediaType }
      .map(_._2)

    if (!mediaType.hasWildcard) {
      mediaTypeMap.get(mediaType).orElse(findSequentially())
    } else {
      findSequentially()
    }
  }
}
