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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.core.ScaleList

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

/**
 * Scale list repository implementation that retrieves and persists scales by using the file system.
 *
 * @param scaleListFormat format implementation responsible for (de)serialization.
 */
class FileScaleListRepo(scaleListFormat: ScaleListFormat,
                        synchronousAwaitTimeout: FiniteDuration = 1 minute) extends ScaleListRepo with StrictLogging {
  override def read(uri: URI): ScaleList = Await.result(readAsync(uri), synchronousAwaitTimeout)

  override def readAsync(uri: URI): Future[ScaleList] = {
    val path = pathOf(uri)

    Future {
      new FileInputStream(path.toString)
    }.recover {
      case e: FileNotFoundException => throw new ScaleListNotFoundException(uri, e.getCause)
    }.flatMap { inputStream =>
      logger.info(s"Reading scale list from path \"$path\"...")
      scaleListFormat.readAsync(inputStream, Some(baseUriOf(uri)))
    }.andThen {
      case Success(_) => logger.info(s"Successfully read scale list from path \"$path\"")
      case Failure(exception) => logger.error(s"Failed to read scale list from path \"$path\"!", exception)
    }
  }

  override def write(scaleList: ScaleList, uri: URI): Unit = ???

  override def writeAsync(scaleList: ScaleList, uri: URI): Future[Unit] = ???
}
