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
import scala.util.Try

package object format {

  /**
   * Converts the given [[URI]] to a [[Path]].
   *
   * @throws IllegalArgumentException if the URI does not represent a file path
   */
  def filePathOf(uri: URI): Path = {
    require(!uri.isAbsolute || uri.getScheme == UriScheme.File)

    if (uri.isAbsolute) Paths.get(uri) else Paths.get(uri.getPath)
  }
}
