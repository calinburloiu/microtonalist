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

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import scala.util.Try

/**
 * Scale list repository implementation that retrieves and persists scales by using the file system.
 * @param scaleListFormat format implementation responsible for (de)serialization.
 */
class FileScaleListRepo(scaleListFormat: ScaleListFormat) extends ScaleListRepo {
  override def read(uri: URI): ScaleList = {
    val path = pathOf(uri)
    val inputStream = Try {
      new FileInputStream(path.toString)
    }.recover {
      case e: FileNotFoundException => throw new ScaleListNotFoundException(uri, e.getCause)
    }.get

    scaleListFormat.read(inputStream, Some(baseUriOf(uri)))
  }

  override def write(scaleList: ScaleList, uri: URI): Unit = ???
}
