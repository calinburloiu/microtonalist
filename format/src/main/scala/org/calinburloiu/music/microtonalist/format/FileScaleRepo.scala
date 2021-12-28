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

import com.google.common.net.MediaType
import org.calinburloiu.music.intonation.{Interval, Scale}

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import scala.util.Try

/**
 * Scale repository implementation that retrieves and persists scales by using the file system.
 *
 * @param scaleFormatRegistry registry responsible for choosing the scale format
 */
class FileScaleRepo(scaleFormatRegistry: ScaleFormatRegistry) extends ScaleRepo {
  override def read(uri: URI): Scale[Interval] = {
    val scalePath = pathOf(uri)
    val inputStream = Try {
      new FileInputStream(scalePath.toString)
    }.recover {
      case e: FileNotFoundException => throw new ScaleNotFoundException(uri, e.getCause)
    }.get

    val scaleFormat = scaleFormatRegistry.get(uri, None)
      .getOrElse(throw new BadScaleRequestException(uri, None))

    scaleFormat.read(inputStream, Some(baseUriOf(uri)))
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = ???
}
