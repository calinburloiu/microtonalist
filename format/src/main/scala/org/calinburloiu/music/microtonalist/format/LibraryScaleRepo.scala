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
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.{Interval, Scale}

import java.net.URI
import scala.concurrent.Future

/**
 * Special scale repository implementation that accesses scales from user's configured private Microtonalist Library.
 *
 * The user can configure a base URI for the library, `libraryUri`, which can be a file system path or a remote HTTP
 * URL. Scales can then be imported by using a special URI with format `microtonalist:///<path-in-library>`, where
 * `<path-in-library>` is relative to the configured base URI.
 *
 * For example, if the user configures `file:///Users/john/Music/microtonalist/lib/` as the base URI for the library,
 * the Microtonalist Library URI `microtonalist:///scales/lydian.scl` used in a scale import will actually point to
 * `/Users/john/Music/microtonalist/lib/scales/lydian.scl`.
 *
 * @param libraryUri    base URI for Microtonalist Library
 * @param fileScaleRepo a [[FileScaleRepo]] instance
 * @param httpScaleRepo an [[HttpScaleRepo]] instance
 */
class LibraryScaleRepo(libraryUri: URI,
                       fileScaleRepo: FileScaleRepo,
                       httpScaleRepo: HttpScaleRepo) extends ScaleRepo with StrictLogging {

  import LibraryScaleRepo._

  logger.info(s"Using Microtonalist library base URI: $libraryUri")

  private val scaleRepo: ScaleRepo = new ComposedScaleRepo {
    override def getScaleRepo(uri: URI): Option[ScaleRepo] = {
      uri.getScheme match {
        case UriScheme.File => Some(fileScaleRepo)
        case UriScheme.Http | UriScheme.Https => Some(httpScaleRepo)
        case _ => None
      }
    }
  }

  override def read(uri: URI, context: Option[ScaleFormatContext] = None): Scale[Interval] =
    scaleRepo.read(resolveUri(uri))

  override def readAsync(uri: URI, context: Option[ScaleFormatContext] = None): Future[Scale[Interval]] =
    scaleRepo.readAsync(resolveUri(uri))

  override def write(scale: Scale[Interval],
                     uri: URI,
                     mediaType: Option[MediaType],
                     context: Option[ScaleFormatContext] = None): Unit =
    scaleRepo.write(scale, resolveUri(uri), mediaType)

  override def writeAsync(scale: Scale[Interval],
                          uri: URI,
                          mediaType: Option[MediaType],
                          context: Option[ScaleFormatContext] = None): Future[Unit] =
    scaleRepo.writeAsync(scale, resolveUri(uri), mediaType)

  private def resolveUri(uri: URI) = {
    require(uri.isAbsolute && uri.getScheme == UriScheme.MicrotonalistLibrary,
      "URI must be absolute and have microtonalist scheme!")

    // Making the path relative to root. E.g. "/path/to/file" => "path/to/file"
    val relativePath = makePathRelativeToRoot(uri)
    libraryUri.resolve(relativePath)
  }
}

object LibraryScaleRepo {
  private def makePathRelativeToRoot(uri: URI): String = {
    val path = uri.getPath
    require(path != null && path.startsWith("/"), "microtonalist scheme URI must point to a file")

    path.substring(1)
  }
}
