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

import java.net.URI
import scala.concurrent.Future

/**
 * Repository pattern trait used for retrieving or persisting scale lists identified by URI. Implementations are
 * responsible for abstracting reading and writing from a particular data source like file, Web or cloud service.
 */
trait ScaleListRepo {
  /**
   * Retrieves a scale list.
   *
   * @param uri universal resource identifier (URI) for the scale list
   * @return the requested scale list
   */
  def read(uri: URI): ScaleList

  /**
   * Retrieves a scale list asynchronously.
   *
   * @param uri universal resource identifier (URI) for the scale list
   * @return a [[Future]] of the requested scale list
   */
  def readAsync(uri: URI): Future[ScaleList]

  /**
   * Persists a scale list.
   *
   * @param scaleList scale list to be persisted
   * @param uri       universal resource identifier (URI) for the scale list
   */
  def write(scaleList: ScaleList, uri: URI): Unit

  /**
   * Persists a scale list asynchronously.
   *
   * @param scaleList scale list to be persisted
   * @param uri       universal resource identifier (URI) for the scale list
   * @return a Future for tracking when the operation finished
   */
  def writeAsync(scaleList: ScaleList, uri: URI): Future[Unit]
}

/**
 * Base exception thrown when an error occurred in a [[ScaleListRepo]] instance.
 */
class ScaleListRepoException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/**
 * Exception thrown if the requested scale list could not be found.
 */
class ScaleListNotFoundException(uri: URI, cause: Throwable = null)
  extends ScaleListRepoException(s"A scale list with $uri was not found", cause)


/**
 * Exception thrown if the the scale list request was invalid.
 */
class BadScaleListRequestException(uri: URI, message: Option[String] = None, cause: Throwable = null)
  extends ScaleListRepoException(message.getOrElse(s"Bad scale list request for $uri"), cause)

/**
 * Exception thrown if the scale list could not be read from the given source URI.
 */
class ScaleListReadFailureException(val uri: URI, message: String, cause: Throwable = null)
  extends ScaleListRepoException(message, cause)
