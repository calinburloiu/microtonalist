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

import org.calinburloiu.music.microtonalist.core.ScaleList

import java.net.URI
import scala.concurrent.Future

/**
 * Scale list repository trait to be extended for choosing different repository implementations based on URI (see
 * [[ComposedScaleListRepo#getScaleListRepo]]).
 */
trait ComposedScaleListRepo extends ScaleListRepo {
  /**
   * @param uri URI used for identifying the scale list
   * @return a [[ScaleListRepo]] implementation based on the given URI
   */
  def getScaleListRepo(uri: URI): Option[ScaleListRepo]

  override def read(uri: URI): ScaleList = getScaleListRepoOrThrow(uri).read(uri)

  override def readAsync(uri: URI): Future[ScaleList] = getScaleListRepoOrThrow(uri).readAsync(uri)

  override def write(scaleList: ScaleList, uri: URI): Unit = getScaleListRepoOrThrow(uri).write(scaleList, uri)

  override def writeAsync(scaleList: ScaleList, uri: URI): Future[Unit] =
    getScaleListRepoOrThrow(uri).writeAsync(scaleList, uri)

  /**
   * Variant of [[getScaleListRepo]] that throws if a repository implementation is not available.
   */
  protected def getScaleListRepoOrThrow(uri: URI): ScaleListRepo = getScaleListRepo(uri)
    .getOrElse(throw new BadScaleListRequestException(uri))
}
