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

package org.calinburloiu.music.microtonalist

import play.api.libs.functional.syntax.toApplicativeOps
import play.api.libs.json.Reads.{max, min}
import play.api.libs.json.{Format, Reads, Writes, __}

import java.net.URI
import java.nio.file.{Path, Paths}

package object format {

  val JsonError_Uint7: String = "error.expected.uint7"
  val JsonError_Uint7Positive: String = "error.expected.uint7.positive"

  /**
   * Converts the given [[URI]] to a [[Path]].
   *
   * @throws IllegalArgumentException if the URI does not represent a file path
   */
  def filePathOf(uri: URI): Path = {
    require(!uri.isAbsolute || uri.getScheme == UriScheme.File)

    if (uri.isAbsolute) Paths.get(uri) else Paths.get(uri.getPath)
  }

  /**
   * When a resource is loaded its URI will be used as the base URI for all resources referred inside it. That base
   * URI may be overridden to further simplify the URIs referred inside it.
   *
   * E.g. A composition resource is loaded from file:///Users/john/Music/composition.mtlist. All scales referenced
   * inside it will use its URI as base URI. So a scale from file:///Users/john/Music/scales/rast.jscl can be referenced
   * as simply "scales/rast.jscl". But this relative URI can be further simplified by defining an override URI
   * "scales/". Then, the same scale may be referenced as simply "rast.jscl".
   *
   * @param baseUri         The base URI of the resource being loaded.
   * @param overrideBaseUri The override base URI to be used for internal resources of the resource being loaded.
   * @return an override base URI resolved against the initial base URI.
   */
  def resolveBaseUriWithOverride(baseUri: Option[URI], overrideBaseUri: Option[URI]): Option[URI] = {
    (baseUri, overrideBaseUri) match {
      case (Some(baseUriValue), Some(overrideBaseUriValue)) => Some(baseUriValue.resolve(overrideBaseUriValue))
      case (Some(baseUriValue), None) => Some(baseUriValue)
      case (None, Some(overrideBaseUriValue)) => Some(overrideBaseUriValue)
      case (None, None) => None
    }
  }

  /**
   * Maps a URI with `microtonalist` scheme to the actual URI as configured via `libraryBaseUri`
   *
   * @param uri     URI of resource from the library.
   * @param baseUri Base URI of the library.
   * @return the actual URI.
   */
  def resolveLibraryUri(uri: URI, baseUri: URI): URI = {
    require(uri.isAbsolute && uri.getScheme == UriScheme.MicrotonalistLibrary,
      "URI must be absolute and have microtonalist scheme!")

    // Making the path relative to root. E.g. "/path/to/file" => "path/to/file"
    val rootUri = uri.resolve("/")
    val relativeToRootUri = rootUri.relativize(uri)
    baseUri.resolve(relativeToRootUri)
  }

  lazy val uint7Format: Format[Int] = {
    val reads = __.read[Int](min(0) keepAnd max(127)) orElse Reads.failed(JsonError_Uint7)
    Format(reads, Writes.IntWrites)
  }

  lazy val uint7PositiveFormat: Format[Int] = {
    val reads = __.read[Int](min(1) keepAnd max(128)) orElse Reads.failed(JsonError_Uint7Positive)
    Format(reads, Writes.IntWrites)
  }
}
