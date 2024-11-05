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
import org.calinburloiu.music.microtonalist.core.{Composition, CompositionNotFoundException, CompositionRepo}

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

/**
 * Composition repository implementation that retrieves and persists scales by using the file system.
 *
 * @param compositionFormat format implementation responsible for (de)serialization.
 */
class FileCompositionRepo(compositionFormat: CompositionFormat,
                          synchronousAwaitTimeout: FiniteDuration = 1 minute) extends CompositionRepo with StrictLogging {
  override def read(uri: URI): Composition = Await.result(readAsync(uri), synchronousAwaitTimeout)

  override def readAsync(uri: URI): Future[Composition] = {
    val path = filePathOf(uri)

    logger.info(s"Reading composition from path \"$path\"...")
    Future {
      new FileInputStream(path.toString)
    }.recover {
      case e: FileNotFoundException => throw new CompositionNotFoundException(uri, e.getCause)
    }.flatMap { inputStream =>
      compositionFormat.readAsync(inputStream, Some(uri))
    }.andThen {
      case Success(_) => logger.info(s"Successfully read composition from path \"$path\"")
      case Failure(exception) => logger.error(s"Failed to read composition from path \"$path\"!", exception)
    }
  }

  override def write(composition: Composition, uri: URI): Unit = ???

  override def writeAsync(composition: Composition, uri: URI): Future[Unit] = ???
}
