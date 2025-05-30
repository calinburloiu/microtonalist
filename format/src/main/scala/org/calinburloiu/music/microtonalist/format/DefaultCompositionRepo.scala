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

import org.calinburloiu.music.microtonalist.composition.Composition

import java.net.URI
import scala.concurrent.Future

/**
 * Default composition repository implementation that accesses compositions from other repositories based on URI:
 *
 *   - Relative URIs and those with `file` scheme use [[FileCompositionRepo]]
 *   - URIs with `http`/`https` scheme use [[HttpCompositionRepo]]
 *
 * @param fileCompositionRepo a [[FileScaleRepo]] instance
 * @param httpCompositionRepo an [[HttpScaleRepo]] instance
 */
class DefaultCompositionRepo(fileCompositionRepo: Option[FileCompositionRepo],
                             httpCompositionRepo: Option[HttpCompositionRepo]) extends CompositionRepo {

  private val compositionRepoSelector: RepoSelector[CompositionRepo] = new DefaultRepoSelector(
    fileCompositionRepo, httpCompositionRepo, None, uri => new BadCompositionRequestException(uri))

  override def read(uri: URI): Composition = compositionRepoSelector.selectRepoOrThrow(uri).read(uri)

  override def readAsync(uri: URI): Future[Composition] = compositionRepoSelector.selectRepoOrThrow(uri).readAsync(uri)

  override def write(composition: Composition, uri: URI): Unit = compositionRepoSelector.selectRepoOrThrow(uri)
    .write(composition, uri)

  override def writeAsync(composition: Composition, uri: URI): Future[Unit] =
    compositionRepoSelector.selectRepoOrThrow(uri).writeAsync(composition, uri)
}
