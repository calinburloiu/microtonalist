/*
 * Copyright 2024 Calin-Andrei Burloiu
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

package org.calinburloiu.music

import java.net.URI
import java.nio.file.Paths
import scala.util.Try

package object microtonalist {

  /**
   * Parses a string as a URI. The string can either be an absolute URI or a local path.
   *
   * @param uriString string to parse
   * @return URI for the given string
   */
  def parseUri(uriString: String): Option[URI] =
    Try(new URI(uriString)).toOption.filter(_.isAbsolute) orElse Try(Paths.get(uriString)).toOption.map(_.toUri)
}
