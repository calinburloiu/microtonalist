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

import org.calinburloiu.music.microtonalist.rig.{BadRigRequestException, RigRepo, RigSpec}

import java.net.URI
import scala.concurrent.Future

/**
 * Default track specification repository implementation that accesses tracks from other repositories based on URI.
 *
 * Other repositories are accessed based on URI in the following way:
 *
 *   - Relative URIs and those with `file` scheme use [[FileRigRepo]]. [[RigRepo]]s don't have a base URI, that's why it
 *     was chosen to interpret relative URIs as files. Callers are advised to always resolve relative URI based on the
 *     base URI before making calls to the repo. In this way relative URI can be based on any scheme.
 *   - URIs with `http`/`https` scheme use [[HttpRigRepo]].
 *   - URIs with `microtonalist` scheme use [[LibraryRigRepo]].
 *
 * @param fileRigRepo a [[FileRigRepo]] instance
 * @param httpRigRepo an [[HttpRigRepo]] instance
 * @param libraryRigRepo a [[LibraryRigRepo]] instance
 */
class DefaultRigRepo(fileRigRepo: Option[FileRigRepo],
                     httpRigRepo: Option[HttpRigRepo],
                     libraryRigRepo: Option[LibraryRigRepo]) extends RigRepo {
  private val rigRepoSelector: RepoSelector[RigRepo] = new DefaultRepoSelector(
    fileRigRepo, httpRigRepo, libraryRigRepo,
    uri => new BadRigRequestException(uri, Some(s"Unsupported URI scheme: ${uri.getScheme}")))

  override def readRig(uri: URI): RigSpec = rigRepoSelector.selectRepoOrThrow(uri).readRig(uri)

  override def readRigAsync(uri: URI): Future[RigSpec] = rigRepoSelector.selectRepoOrThrow(uri)
    .readRigAsync(uri)

  override def writeRig(rigSpec: RigSpec, uri: URI): Unit = rigRepoSelector.selectRepoOrThrow(uri)
    .writeRig(rigSpec, uri)

  override def writeRigAsync(rigSpec: RigSpec, uri: URI): Future[Unit] =
    rigRepoSelector.selectRepoOrThrow(uri).writeRigAsync(rigSpec, uri)
}
