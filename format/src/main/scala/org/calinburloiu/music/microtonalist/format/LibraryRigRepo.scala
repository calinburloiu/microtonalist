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

import org.calinburloiu.music.microtonalist.rig.{RigRepo, RigSpec}

import java.net.URI
import scala.concurrent.Future

/**
 * Special track specification repository implementation that accesses scales from user's configured private
 * Microtonalist Library.
 *
 * The user can configure a base URI for the library, `libraryBaseUrl`, which can be a file system path or a remote HTTP
 * URL. Scales can then be imported by using a special URI with format `microtonalist:///<path-in-library>`, where
 * `<path-in-library>` is relative to the configured base URI.
 *
 * For example, if the user configures `file:///Users/john/Music/microtonalist/lib/` as the base URI for the library,
 * the Microtonalist Library URI `microtonalist:///tracks/default.mtlist.tracks` used as a tracks file URI will actually
 * point to `/Users/john/Music/microtonalist/lib/tracks/default.mtlist.tracks`.
 *
 * @param libraryBaseUrl base URI for Microtonalist Library
 * @param fileRigRepo a [[FileRigRepo]] instance
 * @param httpRigRepo an [[HttpRigRepo]] instance
 */
class LibraryRigRepo(libraryBaseUrl: URI,
                     fileRigRepo: FileRigRepo,
                     httpRigRepo: HttpRigRepo) extends RigRepo {

  private val repoSelector: RepoSelector[RigRepo] = new DefaultRepoSelector(
    Some(fileRigRepo), Some(httpRigRepo), None)

  override def readRig(uri: URI): RigSpec = {
    val resolvedUri = resolveLibraryUrl(uri, libraryBaseUrl)
    repoSelector.selectRepoOrThrow(resolvedUri).readRig(resolvedUri)
  }

  override def readRigAsync(uri: URI): Future[RigSpec] = {
    val resolvedUri = resolveLibraryUrl(uri, libraryBaseUrl)
    repoSelector.selectRepoOrThrow(resolvedUri).readRigAsync(resolvedUri)
  }

  override def writeRig(rigSpec: RigSpec, uri: URI): Unit = {
    val resolvedUri = resolveLibraryUrl(uri, libraryBaseUrl)
    repoSelector.selectRepoOrThrow(resolvedUri).writeRig(rigSpec, resolvedUri)
  }

  override def writeRigAsync(rigSpec: RigSpec, uri: URI): Future[Unit] = {
    val resolvedUri = resolveLibraryUrl(uri, libraryBaseUrl)
    repoSelector.selectRepoOrThrow(resolvedUri).writeRigAsync(rigSpec, resolvedUri)
  }
}
