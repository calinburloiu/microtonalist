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

import java.net.URI

/**
 * Default scale list repository implementation that accesses scale lists from other repositories based on URI:
 *
 *   - Relative URIs and those with `file` scheme use [[FileScaleListRepo]]
 *   - URIs with `http`/`https` scheme use [[HttpScaleListRepo]]
 *
 * @param fileScaleListRepo    a [[FileScaleRepo]] instance
 * @param httpScaleListRepo    an [[HttpScaleRepo]] instance
 */
class DefaultScaleListRepo(fileScaleListRepo: FileScaleListRepo,
                           httpScaleListRepo: HttpScaleListRepo) extends ComposedScaleListRepo {
  override def getScaleListRepo(uri: URI): Option[ScaleListRepo] = uri.getScheme match {
    case null | UriScheme.File => Some(fileScaleListRepo)
    case UriScheme.Http | UriScheme.Https => Some(httpScaleListRepo)
    case _ => None
  }
}
