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

package org.calinburloiu.music.microtonalist.rig.format

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.format.filePathOf
import org.calinburloiu.music.microtonalist.rig.{RigNotFoundException, RigRepo, RigSpec}

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

/**
 * Repository implementation that retrieves and persists track specifications by using the file system.
 *
 * @param rigFormat Format object used for reading and writing rig specifications.
 * @param synchronousAwaitTimeout Time duration to wait for a result in synchronous operations.
 */
class FileRigRepo(rigFormat: RigFormat,
                  synchronousAwaitTimeout: FiniteDuration = 1 minute) extends RigRepo with StrictLogging {

  override def readRig(uri: URI): RigSpec = Await.result(readRigAsync(uri), synchronousAwaitTimeout)

  override def readRigAsync(uri: URI): Future[RigSpec] = {
    val path = filePathOf(uri)

    logger.info(s"Reading tracks from \"$path\"...")
    Future {
      new FileInputStream(path.toFile)
    }.recover {
      case e: FileNotFoundException => throw new RigNotFoundException(uri, e)
    }.flatMap { inputStream =>
      rigFormat.readRigAsync(inputStream, Some(uri))
    }.andThen {
      case Success(_) => logger.info(s"Successfully read tracks from \"$path\".")
      case Failure(exception) => logger.error(s"Failed to read tracks from \"$path\"!", exception)
    }
  }

  override def writeRig(rigSpec: RigSpec, uri: URI): Unit = ???

  override def writeRigAsync(rigSpec: RigSpec, uri: URI): Future[Unit] = ???
}
