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

trait ScaleListRepo {
  def read(uri: URI): ScaleList

  def write(scaleList: ScaleList, uri: URI): Unit
}

/**
 * Exception thrown if the requested scale list could not be found.
 */
class ScaleListNotFoundException(uri: URI, cause: Throwable = null)
  extends RuntimeException(s"A scale list with $uri was not found", cause)


/**
 * Exception thrown if the the scale list request was invalid.
 */
class BadScaleListRequestException(uri: URI, message: Option[String] = None, cause: Throwable = null)
  extends RuntimeException(message.getOrElse(s"Bad scale list request for $uri"), cause)

class ScaleListReadFailureException(val uri: URI, message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)
