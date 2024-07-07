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

import org.calinburloiu.music.intonation.{Interval, Scale}

import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Default scale repository implementation that accesses scales from other repositories based on URI and makes the
 * necessary validations and conversions for them according to the current context.
 *
 * Other repositories are accessed based on URI in the following way:
 *
 *   - Relative URIs and those with `file` scheme use [[FileScaleRepo]]
 *   - URIs with `http`/`https` scheme use [[HttpScaleRepo]]
 *   - URIs with `microtonalist` scheme use [[LibraryScaleRepo]].
 *
 * A scale that was read in the context of a composition file may be modified based on context in the following way:
 *
 *   - The `name` may be changed by the tuning specification
 *   - The scale pitch intervals may be converted from their intonation standard to another one from the context. For
 *     example, a scale in just intonation might be converted to 31-EDO when the composition file has this later
 *     intonation
 *     standard, but this will do a lossy conversion which will generate a warning.
 *
 * @param fileScaleRepo    a [[FileScaleRepo]] instance
 * @param httpScaleRepo    an [[HttpScaleRepo]] instance
 * @param libraryScaleRepo a [[LibraryScaleRepo]] instance
 */
class DefaultScaleRepo(fileScaleRepo: FileScaleRepo,
                       httpScaleRepo: HttpScaleRepo,
                       libraryScaleRepo: LibraryScaleRepo) extends ComposedScaleRepo {
  override def getScaleRepo(uri: URI): Option[ScaleRepo] = uri.getScheme match {
    case null | UriScheme.File => Some(fileScaleRepo)
    case UriScheme.Http | UriScheme.Https => Some(httpScaleRepo)
    case UriScheme.MicrotonalistLibrary => Some(libraryScaleRepo)
    case _ => None
  }

  override def read(uri: URI, context: Option[ScaleFormatContext]): Scale[Interval] = {
    updateScaleFromContext(super.read(uri, context), context)
  }

  override def readAsync(uri: URI, context: Option[ScaleFormatContext]): Future[Scale[Interval]] = {
    super.readAsync(uri, context).map { scale => updateScaleFromContext(scale, context) }
  }

  private def updateScaleFromContext(scale: Scale[Interval], context: Option[ScaleFormatContext]): Scale[Interval] = {
    // TODO #45
    scale
  }
}
