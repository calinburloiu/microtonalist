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

class DefaultScaleRepo(fileScaleRepo: FileScaleRepo,
                       httpScaleRepo: HttpScaleRepo,
                       microtonalistLibraryScaleRepo: MicrotonalistLibraryScaleRepo) extends ComposedScaleRepo {
  override def getScaleRepo(uri: URI): Option[ScaleRepo] = uri.getScheme match {
    case null | UriScheme.File => Some(fileScaleRepo)
    case UriScheme.Http | UriScheme.Https => Some(httpScaleRepo)
    case UriScheme.MicrotonalistLibrary => Some(microtonalistLibraryScaleRepo)
    case _ => None
  }
}
