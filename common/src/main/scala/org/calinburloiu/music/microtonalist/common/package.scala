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

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.util.Try

package object common {

  /**
   * Parses a string as a URL. The string can either be an absolute URL or a local path.
   *
   * @param urlString string to parse
   * @return an option [[URI]] for the given string
   */
  def parseUrlOrPath(urlString: String): Option[URI] =
    Try(new URI(urlString)).toOption.filter(_.isAbsolute) orElse Try(Paths.get(urlString)).toOption.map { path =>
      mapPathToUri(path, urlString)
    }

  /**
   * The convention in Microtonalist is that a directory path URI must end in "/", but the Java Path API tends to
   * eliminate that final slash. This method is a workaround for that.
   */
  private def mapPathToUri(path: Path, uriString: String): URI = {
    var result = path.toUri
    if ((Files.isDirectory(path) || PlatformUtils.isWindows && uriString.endsWith("\\") || uriString.endsWith("/"))
      && !result.toString.endsWith("/")) {
      result = new URI(result.toString + "/")
    }

    result
  }
}
