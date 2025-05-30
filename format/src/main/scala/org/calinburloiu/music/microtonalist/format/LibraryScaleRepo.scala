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
import org.calinburloiu.music.intonation.{Interval, Scale}

import java.net.URI
import scala.concurrent.Future

/**
 * Special scale repository implementation that accesses scales from user's configured private Microtonalist Library.
 *
 * The user can configure a base URL for the library, `libraryBaseUrl`, which can be a file system path or a remote HTTP
 * URL. Scales can then be imported by using a special URL with format `microtonalist:///<path-in-library>`, where
 * `<path-in-library>` is relative to the configured base URL.
 *
 * For example, if the user configures `file:///Users/john/Music/microtonalist/lib/` as the base URL for the library,
 * the Microtonalist Library URL `microtonalist:///scales/lydian.scl` used in a scale import will actually point to
 * `/Users/john/Music/microtonalist/lib/scales/lydian.scl`.
 *
 * @param libraryBaseUrl base URL for Microtonalist Library
 * @param fileScaleRepo  a [[FileScaleRepo]] instance
 * @param httpScaleRepo  an [[HttpScaleRepo]] instance
 */
class LibraryScaleRepo(libraryBaseUrl: URI,
                       fileScaleRepo: FileScaleRepo,
                       httpScaleRepo: HttpScaleRepo) extends ScaleRepo {
  private val repoSelector: RepoSelector[ScaleRepo] = new DefaultRepoSelector(
    Some(fileScaleRepo), Some(httpScaleRepo), None)

  override def read(uri: URI, context: Option[ScaleFormatContext]): Scale[Interval] = {
    val resolvedUrl = resolveLibraryUrl(uri, libraryBaseUrl)
    repoSelector.selectRepoOrThrow(resolvedUrl).read(resolvedUrl, context)
  }

  override def readAsync(uri: URI, context: Option[ScaleFormatContext]): Future[Scale[Interval]] = {
    val resolvedUri = resolveLibraryUrl(uri, libraryBaseUrl)
    repoSelector.selectRepoOrThrow(resolvedUri).readAsync(resolvedUri, context)
  }

  override def write(scale: Scale[Interval],
                     uri: URI,
                     mediaType: Option[MediaType],
                     context: Option[ScaleFormatContext]): Unit = {
    val resolvedUri = resolveLibraryUrl(uri, libraryBaseUrl)
    repoSelector.selectRepoOrThrow(resolvedUri).write(scale, resolvedUri, mediaType, context)
  }

  override def writeAsync(scale: Scale[Interval],
                          uri: URI,
                          mediaType: Option[MediaType],
                          context: Option[ScaleFormatContext]): Future[Unit] = {
    val resolvedUri = resolveLibraryUrl(uri, libraryBaseUrl)
    repoSelector.selectRepoOrThrow(resolvedUri).writeAsync(scale, resolvedUri, mediaType, context)
  }
}
