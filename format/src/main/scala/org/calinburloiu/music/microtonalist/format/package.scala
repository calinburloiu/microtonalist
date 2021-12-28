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

package org.calinburloiu.music.microtonalist

import java.net.URI
import java.nio.file.{Path, Paths}

package object format {
  /**
   * Removes the trailing path item from the URI after the last slash. If the URI points to a file, for example,
   * it remove the file name from the path and only leaves its folder location.
   *
   * Example: `baseUriOf(new URI("https://example.org/path/to/file.json))` returns
   * `new URI("https://example.org/path/to/)`.
   */
  def baseUriOf(uri: URI): URI = {
    def updateUriPath(uri: URI, newPath: String): URI = {
      new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, newPath, uri.getQuery, uri.getFragment)
    }

    val path = uri.getPath
    if (path == null || path == "") {
      return updateUriPath(uri, "/")
    }

    val lastSlashIndex = path.lastIndexOf('/')
    if (lastSlashIndex == path.length - 1) {
      uri
    } else {
      val basePath = path.substring(0, lastSlashIndex + 1)
      updateUriPath(uri, basePath)
    }
  }

  /**
   * Converts the given [[URI]] to a [[Path]].
   * @throws IllegalArgumentException if the URI does not represent a file path
   */
  def pathOf(uri: URI): Path = {
    require(!uri.isAbsolute || uri.getScheme == UriScheme.File)

    if (uri.isAbsolute) Paths.get(uri) else Paths.get(uri.getPath)
  }
}
