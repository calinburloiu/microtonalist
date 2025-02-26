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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.tuner.{TrackRepo, TrackSpecs, TracksNotFoundException}

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

class FileTrackRepo(trackFormat: TrackFormat,
                    synchronousAwaitTimeout: FiniteDuration = 1 minute) extends TrackRepo with StrictLogging {

  override def readTracks(uri: URI): TrackSpecs = Await.result(readTracksAsync(uri), synchronousAwaitTimeout)

  override def readTracksAsync(uri: URI): Future[TrackSpecs] = {
    val path = filePathOf(uri)

    logger.info(s"Reading tracks from \"$path\"...")
    Future {
      new FileInputStream(path.toFile)
    }.recover {
      case e: FileNotFoundException => throw new TracksNotFoundException(uri, e)
    }.flatMap { inputStream =>
      trackFormat.readTracksAsync(inputStream, Some(uri))
    }.andThen {
      case Success(_) => logger.info(s"Successfully read tracks from \"$path\".")
      case Failure(exception) => logger.error(s"Failed to read tracks from \"$path\"!", exception)
    }
  }

  override def writeTracks(trackSpecs: TrackSpecs, uri: URI): Unit = ???

  override def writeTracksAsync(trackSpecs: TrackSpecs, uri: URI): Future[Unit] = ???
}
