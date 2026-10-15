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

package org.calinburloiu.music.microtonalist.rig

import java.net.URI
import scala.concurrent.Future

/**
 * Repository pattern trait used for retrieving or persisting rigs, stored in tracks files identified by URI.
 * Implementations are responsible for abstracting reading and writing from a particular data source like file, Web or
 * cloud service.
 *
 * @see `RigFormat`, in `format`, for details about tracks files.
 * @see [[RigSpec]] for details about track specifications.
 */
trait RigRepo {

  /**
   * Retrieves track specifications from the specified URI.
   *
   * @param uri The URI from which the track specifications should be read.
   * @return The track specifications read from the given URI.
   */
  def readRig(uri: URI): RigSpec

  /**
   * Asynchronously retrieves track specifications from the specified URI.
   *
   * @param uri The URI from which the track specifications should be read.
   * @return A Future containing the track specifications read from the given URI.
   */
  def readRigAsync(uri: URI): Future[RigSpec]

  /**
   * Persists the given track specifications to the specified URI.
   *
   * @param rigSpec The track specifications to be written.
   * @param uri The URI where the track specifications should be saved.
   */
  def writeRig(rigSpec: RigSpec, uri: URI): Unit

  /**
   * Asynchronously persists the given track specifications to the specified URI.
   *
   * @param rigSpec The track specifications to be written.
   * @param uri The URI where the track specifications should be saved.
   * @return A Future representing the completion of the write operation.
   */
  def writeRigAsync(rigSpec: RigSpec, uri: URI): Future[Unit]
}

/**
 * Base exception thrown when an error occurred in a [[RigRepo]] instance.
 */
class RigRepoException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/**
 * Exception thrown if the requested tracks file could not be found at the given URI.
 */
class RigNotFoundException(uri: URI, cause: Throwable = null)
  extends RigRepoException(s"Track with $uri was not found!", cause)

/**
 * Exception thrown if the request for the tracks file at the given URI was invalid.
 */
class BadRigRequestException(uri: URI, message: Option[String] = None, cause: Throwable = null)
  extends RigRepoException(message.getOrElse(s"Bad request for tracks with $uri!"), cause)

/**
 * Exception thrown if the rig could not be read from the given source URI.
 */
class RigReadFailureException(val uri: URI, message: String, cause: Throwable = null)
  extends RigRepoException(message, cause)
