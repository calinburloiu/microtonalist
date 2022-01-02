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

import java.net.URI
import scala.concurrent.Future

// TODO #38 Break inheritance from RefResolver

/**
 * Repository pattern trait used for retrieving or persisting scales identified by URI. Implementations are
 * responsible for abstracting reading and writing from a particular data source like file, Web or cloud service.
 */
trait ScaleRepo extends RefResolver[Scale[Interval]] {

  /**
   * Retrieves a scale.
   *
   * @param uri universal resource identifier (URI) for the scale
   * @return the requested scale
   */
  def read(uri: URI): Scale[Interval]

  /**
   * Retrieves a scale asynchronously.
   *
   * @param uri universal resource identifier (URI) for the scale
   * @return a [[Future]] of the requested scale
   */
  def readAsync(uri: URI): Future[Scale[Interval]]

  /**
   * Persists a scale.
   *
   * @param scale     scale to be persisted
   * @param uri       universal resource identifier (URI) for the scale
   * @param mediaType the media type that identifies the format of the scale. If not provided, the extension might be
   *                  used for identification.
   */
  def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit

  /**
   * Persists a scale asynchronously.
   *
   * @param scale     scale to be persisted
   * @param uri       universal resource identifier (URI) for the scale
   * @param mediaType the media type that identifies the format of the scale. If not provided, the extension might be
   *                  used for identification.
   * @return a [[Future]] for tracking when the operation finished
   */
  def writeAsync(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Future[Unit]
}

/**
 * Base exception thrown when an error occurred in a [[ScaleRepo]] instance.
 */
class ScaleRepoException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/**
 * Exception thrown if the requested scale could not be found.
 */
class ScaleNotFoundException(val uri: URI, cause: Throwable = null)
  extends ScaleRepoException(s"No scale was found at $uri", cause)

/**
 * Exception thrown if the the scale request was invalid.
 */
class BadScaleRequestException(val uri: URI, val mediaType: Option[MediaType] = None,
                               message: Option[String] = None, cause: Throwable = null)
  extends ScaleRepoException(
    message.getOrElse(s"Bad scale request for $uri and ${mediaType.getOrElse("any")} media type"), cause)

/**
 * Exception thrown if the scale could not be read from the given source URI.
 */
class ScaleReadFailureException(val uri: URI, message: String, cause: Throwable = null)
  extends ScaleRepoException(message, cause)
