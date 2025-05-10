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

import com.google.common.net.MediaType
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.{Interval, Scale}

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

/**
 * Scale repository implementation that retrieves and persists scales by using the file system.
 *
 * @param scaleFormatRegistry registry responsible for choosing the scale format
 */
class FileScaleRepo(scaleFormatRegistry: ScaleFormatRegistry) extends ScaleRepo with StrictLogging {
  override def read(uri: URI, context: Option[ScaleFormatContext]): Scale[Interval] = {
    val scalePath = filePathOf(uri)
    val inputStream = Try {
      new FileInputStream(scalePath.toString)
    }.recover {
      case e: FileNotFoundException => throw new ScaleNotFoundException(uri, e)
    }.get

    val scaleFormat = scaleFormatRegistry.get(uri, None)
      .getOrElse(throw new BadScaleRequestException(uri, None))

    logger.info(s"Reading scale from path \"$scalePath\"...")
    scaleFormat.read(inputStream, Some(uri), context)
  }

  override def readAsync(uri: URI, context: Option[ScaleFormatContext]): Future[Scale[Interval]] =
    Future {
      read(uri, context)
    }

  override def write(scale: Scale[Interval],
                     uri: URI,
                     mediaType: Option[MediaType],
                     context: Option[ScaleFormatContext]): Unit = ???

  override def writeAsync(scale: Scale[Interval],
                          uri: URI,
                          mediaType: Option[MediaType],
                          context: Option[ScaleFormatContext]): Future[Unit] = ???
}
