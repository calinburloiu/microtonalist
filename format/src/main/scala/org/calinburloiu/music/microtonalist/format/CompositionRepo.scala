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

import org.calinburloiu.music.microtonalist.composition.Composition

import java.net.URI
import scala.concurrent.Future

/**
 * Repository pattern trait used for retrieving or persisting Microtonalist compositions identified by URI.
 * Implementations are responsible for abstracting reading and writing from a particular data source like file, Web
 * or cloud service.
 */
trait CompositionRepo {
  /**
   * Retrieves a composition.
   *
   * @param uri universal resource identifier (URI) for the composition
   * @return the requested composition
   */
  def read(uri: URI): Composition

  /**
   * Retrieves a composition asynchronously.
   *
   * @param uri universal resource identifier (URI) for the composition
   * @return a [[Future]] of the requested composition
   */
  def readAsync(uri: URI): Future[Composition]

  /**
   * Persists a composition.
   *
   * @param composition composition to be persisted
   * @param uri         universal resource identifier (URI) for the composition
   */
  def write(composition: Composition, uri: URI): Unit

  /**
   * Persists a composition asynchronously.
   *
   * @param composition composition to be persisted
   * @param uri         universal resource identifier (URI) for the composition
   * @return a Future for tracking when the operation finished
   */
  def writeAsync(composition: Composition, uri: URI): Future[Unit]
}

/**
 * Base exception thrown when an error occurred in a [[CompositionRepo]] instance.
 */
class CompositionRepoException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/**
 * Exception thrown if the requested composition could not be found.
 */
class CompositionNotFoundException(uri: URI, cause: Throwable = null)
  extends CompositionRepoException(s"A composition with $uri was not found", cause)


/**
 * Exception thrown if the composition request was invalid.
 */
class BadCompositionRequestException(uri: URI, message: Option[String] = None, cause: Throwable = null)
  extends CompositionRepoException(message.getOrElse(s"Bad composition request for $uri"), cause)

/**
 * Exception thrown if the composition could not be read from the given source URI.
 */
class CompositionReadFailureException(val uri: URI, message: String, cause: Throwable = null)
  extends CompositionRepoException(message, cause)
