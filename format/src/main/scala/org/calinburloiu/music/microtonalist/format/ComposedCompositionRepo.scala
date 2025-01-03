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

import org.calinburloiu.music.microtonalist.composition.Composition

import java.net.URI
import scala.concurrent.Future

/**
 * Composition repository trait to be extended for choosing different repository implementations based on URI (see
 * [[ComposedCompositionRepo#getCompositionRepo]]).
 */
trait ComposedCompositionRepo extends CompositionRepo {
  /**
   * @param uri URI used for identifying the composition
   * @return a [[CompositionRepo]] implementation based on the given URI
   */
  def getCompositionRepo(uri: URI): Option[CompositionRepo]

  override def read(uri: URI): Composition = getCompositionRepoOrThrow(uri).read(uri)

  override def readAsync(uri: URI): Future[Composition] = getCompositionRepoOrThrow(uri).readAsync(uri)

  override def write(composition: Composition, uri: URI): Unit = getCompositionRepoOrThrow(uri).write(composition, uri)

  override def writeAsync(composition: Composition, uri: URI): Future[Unit] =
    getCompositionRepoOrThrow(uri).writeAsync(composition, uri)

  /**
   * Variant of [[getCompositionRepo]] that throws if a repository implementation is not available.
   */
  protected def getCompositionRepoOrThrow(uri: URI): CompositionRepo = getCompositionRepo(uri)
    .getOrElse(throw new BadCompositionRequestException(uri))
}
