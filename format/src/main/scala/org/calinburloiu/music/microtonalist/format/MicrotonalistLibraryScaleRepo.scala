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

class MicrotonalistLibraryScaleRepo(libraryUri: URI,
                                    fileScaleRepo: FileScaleRepo,
                                    httpScaleRepo: HttpScaleRepo) extends ScaleRepo {

  import MicrotonalistLibraryScaleRepo._

  val scaleRepo: ScaleRepo = new ComposedScaleRepo {
    override def getScaleRepo(uri: URI): Option[ScaleRepo] = {
      uri.getScheme match {
        case UriScheme.File => Some(fileScaleRepo)
        case UriScheme.Http | UriScheme.Https => Some(httpScaleRepo)
        case _ => None
      }
    }
  }

  override def read(uri: URI): Scale[Interval] = {
    val resolvedUri = resolveUri(uri)
    scaleRepo.read(resolvedUri)
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = {
    val resolvedUri = resolveUri(uri)
    scaleRepo.write(scale, resolvedUri, mediaType)
  }

  private def resolveUri(uri: URI) = {
    require(uri.isAbsolute && uri.getScheme == UriScheme.MicrotonalistLibrary,
      "URI must be absolute and have microtonalist scheme!")

    // Making the path relative to root. E.g. "/path/to/file" => "path/to/file"
    val relativePath = makePathRelativeToRoot(uri)
    libraryUri.resolve(relativePath)
  }
}

object MicrotonalistLibraryScaleRepo {
  private def makePathRelativeToRoot(uri: URI): String = {
    val path = uri.getPath
    require(path != null && path.startsWith("/"), "microtonalist scheme URI must point to a file")

    path.substring(1)
  }
}