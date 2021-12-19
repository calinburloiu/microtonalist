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
import org.calinburloiu.music.microtonalist.format.HttpScaleRepo.UriSchemes

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import java.nio.file.Paths
import scala.util.Try

// TODO #38 Break inheritance from RefResolver
/**
 * Repository pattern trait used for retrieving scales by their URI. Implementations are responsible for implementing
 * the retrieval from a particular data source like file, Web or cloud service.
 */
trait ScaleRepo extends RefResolver[Scale[Interval]] {

  /**
   * Retrieves a scale.
   *
   * @param uri       universal resource identifier for the scale
   * @param mediaType the media type that identifies the format of the scale. If not provided, the extension might be
   *                  used for identification.
   * @return the identified scale
   */
  def read(uri: URI, mediaType: Option[MediaType] = None): Scale[Interval]

  def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit
}

class FileScaleRepo(scaleFormatRegistry: ScaleFormatRegistry) extends ScaleRepo {
  override def read(uri: URI, mediaType: Option[MediaType]): Scale[Interval] = {
    val scaleAbsolutePath = if (uri.isAbsolute) Paths.get(uri) else Paths.get(uri.toString)
    val inputStream = Try {
      new FileInputStream(scaleAbsolutePath.toString)
    }.recover {
      case e: FileNotFoundException => throw new ScaleNotFoundException(uri, mediaType, e.getCause)
    }.get
    val scaleFormat = scaleFormatRegistry.get(uri, mediaType)
      .getOrElse(throw new BadScaleRequestException(uri, mediaType))

    scaleFormat.read(inputStream)
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = ???
}

object FileScaleRepo {
  val FileUriScheme: String = "file"
}

class HttpScaleRepo(scaleFormatRegistry: ScaleFormatRegistry) extends ScaleRepo {
  override def read(uri: URI, mediaType: Option[MediaType]): Scale[Interval] = {
    require(uri.isAbsolute && UriSchemes.contains(uri.getScheme), "URI must be absolute and have file scheme!")

    ???
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = ???
}

object HttpScaleRepo {
  val HttpUriScheme: String = "http"
  val HttpsUriScheme: String = "https"
  val UriSchemes: Set[String] = Set(HttpUriScheme, HttpsUriScheme)
}

abstract class ComposedScaleRepo() extends ScaleRepo {
  override def read(uri: URI, mediaType: Option[MediaType]): Scale[Interval] = {
    val resolvedUri = resolveUri(uri)
    val scaleRepo = getScaleRepoOrThrow(resolvedUri, mediaType)
    scaleRepo.read(resolvedUri, mediaType)
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = {
    val resolvedUri = resolveUri(uri)
    val scaleRepo = getScaleRepoOrThrow(resolvedUri, mediaType)
    scaleRepo.write(scale, resolvedUri, mediaType)
  }

  def getScaleRepo(uri: URI): Option[ScaleRepo]

  def resolveUri(uri: URI): URI

  protected def getScaleRepoOrThrow(uri: URI, mediaType: Option[MediaType]): ScaleRepo = getScaleRepo(uri)
    .getOrElse(throw new BadScaleRequestException(uri, mediaType))
}

class MicrotonalistLibraryScaleRepo(libraryUri: URI,
                                    fileScaleRepo: FileScaleRepo,
                                    httpScaleRepo: HttpScaleRepo) extends ScaleRepo {
  import MicrotonalistLibraryScaleRepo._

  val scaleRepo: ScaleRepo = new ComposedScaleRepo {
    override def getScaleRepo(uri: URI): Option[ScaleRepo] = {
      uri.getScheme match {
        case FileScaleRepo.FileUriScheme => Some(fileScaleRepo)
        case HttpScaleRepo.HttpUriScheme | HttpScaleRepo.HttpsUriScheme => Some(httpScaleRepo)
        case _ => None
      }
    }

    override def resolveUri(uri: URI): URI = {
      assert(uri.isAbsolute)
      uri
    }
  }

  override def read(uri: URI, mediaType: Option[MediaType]): Scale[Interval] = {
    val resolvedUri = resolveUri(uri)
    scaleRepo.read(resolvedUri, mediaType)
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = {
    val resolvedUri = resolveUri(uri)
    scaleRepo.write(scale, resolvedUri, mediaType)
  }

  private def resolveUri(uri: URI) = {
    require(uri.isAbsolute && uri.getScheme == MicrotonalistUriScheme,
      "URI must be absolute and have microtonalist scheme!")

    val relativePath = RootPath.relativize(Paths.get(uri.getPath))
    libraryUri.resolve(new URI(relativePath.toString))
  }
}

object MicrotonalistLibraryScaleRepo {
  val MicrotonalistUriScheme: String = "microtonalist"

  private val RootPath = Paths.get("/")
}

class DefaultScaleRepo(baseUri: URI,
                       fileScaleRepo: FileScaleRepo,
                       httpScaleRepo: HttpScaleRepo,
                       microtonalistLibraryScaleRepo: MicrotonalistLibraryScaleRepo) extends ComposedScaleRepo {
  override def getScaleRepo(uri: URI): Option[ScaleRepo] = {
    uri.getScheme match {
      case null | FileScaleRepo.FileUriScheme => Some(fileScaleRepo)
      case HttpScaleRepo.HttpUriScheme | HttpScaleRepo.HttpsUriScheme => Some(httpScaleRepo)
      case MicrotonalistLibraryScaleRepo.MicrotonalistUriScheme => Some(microtonalistLibraryScaleRepo)
      case _ => None
    }
  }

  override def resolveUri(uri: URI): URI = baseUri.resolve(uri)
}

/**
 * Exception thrown if the requested scale could not be found.
 */
class ScaleNotFoundException(uri: URI, mediaType: Option[MediaType], cause: Throwable = null)
  extends RuntimeException(s"A scale $uri with ${mediaType.getOrElse("any")} media type was not found",
    cause)

/**
 * Exception thrown if the the scale request was invalid.
 */
class BadScaleRequestException(uri: URI, mediaType: Option[MediaType] = None, cause: Throwable = null)
  extends RuntimeException(s"A scale could not be processed for $uri and ${mediaType.getOrElse("any")} media type",
    cause)