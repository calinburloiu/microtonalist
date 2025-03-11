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
  logger.info(s"Using Microtonalist library base URI: $libraryUri")

  private val repoSelector: RepoSelector[ScaleRepo] = new DefaultRepoSelector(
    Some(fileScaleRepo), Some(httpScaleRepo), None)

  override def read(uri: URI, context: Option[ScaleFormatContext] = None): Scale[Interval] = {
    val resolvedUri = resolveUri(uri)
    repoSelector.selectRepoOrThrow(resolvedUri).read(resolvedUri)
  }

  override def readAsync(uri: URI, context: Option[ScaleFormatContext] = None): Future[Scale[Interval]] = {
    val resolvedUri = resolveUri(uri)
    repoSelector.selectRepoOrThrow(resolvedUri).readAsync(resolvedUri)
  }

  override def write(scale: Scale[Interval],
                     uri: URI,
                     mediaType: Option[MediaType],
                     context: Option[ScaleFormatContext] = None): Unit = {
    val resolvedUri = resolveUri(uri)
    repoSelector.selectRepoOrThrow(resolvedUri).write(scale, resolvedUri, mediaType)
  }

  override def writeAsync(scale: Scale[Interval],
                          uri: URI,
                          mediaType: Option[MediaType],
                          context: Option[ScaleFormatContext] = None): Future[Unit] = {
    val resolvedUri = resolveUri(uri)
    repoSelector.selectRepoOrThrow(resolvedUri).writeAsync(scale, resolvedUri, mediaType)
  }

  private def resolveUri(uri: URI) = {
    require(uri.isAbsolute && uri.getScheme == UriScheme.MicrotonalistLibrary,
      "URI must be absolute and have microtonalist scheme!")

    // Making the path relative to root. E.g. "/path/to/file" => "path/to/file"
    val rootUri = uri.resolve("/")
    val relativeToRootUri = rootUri.relativize(uri)
    libraryUri.resolve(relativeToRootUri)
  }
}
