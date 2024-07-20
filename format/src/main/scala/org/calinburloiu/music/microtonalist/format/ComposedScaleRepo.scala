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
import org.calinburloiu.music.intonation.{Interval, Scale}

import java.net.URI
import scala.concurrent.Future

/**
 * Scale repository trait to be extended for choosing different repository implementations based on URI (see
 * [[ComposedScaleRepo#getScaleRepo]]).
 */
trait ComposedScaleRepo extends ScaleRepo {
  /**
   * @param uri URI used for identifying the scale
   * @return a [[ScaleRepo]] implementation based on the given URI
   */
  def getScaleRepo(uri: URI): Option[ScaleRepo]

  override def read(uri: URI, context: Option[ScaleFormatContext] = None): Scale[Interval] =
    getScaleRepoOrThrow(uri).read(uri)

  override def readAsync(uri: URI, context: Option[ScaleFormatContext] = None): Future[Scale[Interval]] =
    getScaleRepoOrThrow(uri).readAsync(uri)

  override def write(scale: Scale[Interval],
                     uri: URI,
                     mediaType: Option[MediaType],
                     context: Option[ScaleFormatContext] = None): Unit =
    getScaleRepoOrThrow(uri).write(scale, uri, mediaType)

  override def writeAsync(scale: Scale[Interval],
                          uri: URI,
                          mediaType: Option[MediaType],
                          context: Option[ScaleFormatContext] = None): Future[Unit] =
    getScaleRepoOrThrow(uri).writeAsync(scale, uri, mediaType)

  /**
   * Variant of [[getScaleRepo]] that throws if a repository implementation is not available.
   */
  protected def getScaleRepoOrThrow(uri: URI): ScaleRepo = getScaleRepo(uri)
    .getOrElse(throw new BadScaleRequestException(uri))
}
