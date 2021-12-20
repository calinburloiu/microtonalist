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

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import java.nio.file.Paths
import scala.util.Try

trait ScaleListRepo {
  def read(uri: URI): ScaleList

  def write(scaleList: ScaleList, uri: URI): Unit
}

class FileScaleListRepo(scaleListFormat: ScaleListFormat) extends ScaleListRepo {
  override def read(uri: URI): ScaleList = {
    val absolutePath = if (uri.isAbsolute) Paths.get(uri) else Paths.get(uri.toString)
    val inputStream = Try {
      new FileInputStream(absolutePath.toString)
    }.recover {
      case e: FileNotFoundException => throw new ScaleListNotFoundException(uri, e.getCause)
    }.get

    scaleListFormat.read(inputStream, Some(baseUriOf(uri)))
  }

  override def write(scaleList: ScaleList, uri: URI): Unit = ???
}

class HttpScaleListRepo(scaleListFormat: ScaleListFormat) extends ScaleListRepo {
  import HttpScaleListRepo._

  override def read(uri: URI): ScaleList = {
    require(uri.isAbsolute && UriSchemes.contains(uri.getScheme), "URI must be absolute and have an http/https scheme!")

    ???
  }

  override def write(scaleList: ScaleList, uri: URI): Unit = ???
}

trait ComposedScaleListRepo extends ScaleListRepo {
  def getScaleListRepo(uri: URI): Option[ScaleListRepo]

  override def read(uri: URI): ScaleList = getScaleListRepoOrThrow(uri).read(uri)

  override def write(scaleList: ScaleList, uri: URI): Unit = getScaleListRepoOrThrow(uri).write(scaleList, uri)

  protected def getScaleListRepoOrThrow(uri: URI): ScaleListRepo = getScaleListRepo(uri)
    .getOrElse(throw new BadScaleListRequestException(uri))
}

class DefaultScaleListRepo(fileScaleListRepo: FileScaleListRepo,
                           httpScaleListRepo: HttpScaleListRepo) extends ComposedScaleListRepo {
  override def getScaleListRepo(uri: URI): Option[ScaleListRepo] = uri.getScheme match {
    case null | UriScheme.File => Some(fileScaleListRepo)
    case UriScheme.Http | UriScheme.Https => Some(httpScaleListRepo)
    case _ => None
  }
}

object HttpScaleListRepo {
  val UriSchemes: Set[String] = Set(UriScheme.Http, UriScheme.Https)
}


/**
 * Exception thrown if the requested scale list could not be found.
 */
class ScaleListNotFoundException(uri: URI, cause: Throwable = null)
  extends RuntimeException(s"A scale list with $uri was not found", cause)


/**
 * Exception thrown if the the scale list request was invalid.
 */
class BadScaleListRequestException(uri: URI, cause: Throwable = null)
  extends RuntimeException(s"A scale list could not be processed for $uri", cause)
