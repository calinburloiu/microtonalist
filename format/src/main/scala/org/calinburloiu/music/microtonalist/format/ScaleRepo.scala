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

    scaleFormat.read(inputStream, Some(baseUriOf(uri)))
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = ???
}

class HttpScaleRepo(scaleFormatRegistry: ScaleFormatRegistry) extends ScaleRepo {
  override def read(uri: URI, mediaType: Option[MediaType]): Scale[Interval] = {
    require(uri.isAbsolute && UriScheme.HttpSet.contains(uri.getScheme), "URI must be absolute and have http/https scheme!")

    ???
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = ???
}

trait ComposedScaleRepo extends ScaleRepo {
  def getScaleRepo(uri: URI): Option[ScaleRepo]

  override def read(uri: URI, mediaType: Option[MediaType]): Scale[Interval] =
    getScaleRepoOrThrow(uri, mediaType).read(uri, mediaType)

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit =
    getScaleRepoOrThrow(uri, mediaType).write(scale, uri, mediaType)

  protected def getScaleRepoOrThrow(uri: URI, mediaType: Option[MediaType]): ScaleRepo = getScaleRepo(uri)
    .getOrElse(throw new BadScaleRequestException(uri, mediaType))
}

class MicrotonalistLibraryScaleRepo(libraryUri: URI,
                                    fileScaleRepo: FileScaleRepo,
                                    httpScaleRepo: HttpScaleRepo) extends ScaleRepo {
  val scaleRepo: ScaleRepo = new ComposedScaleRepo {
    override def getScaleRepo(uri: URI): Option[ScaleRepo] = {
      uri.getScheme match {
        case UriScheme.File => Some(fileScaleRepo)
        case UriScheme.Http | UriScheme.Https => Some(httpScaleRepo)
        case _ => None
      }
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
    require(uri.isAbsolute && uri.getScheme == UriScheme.MicrotonalistLibrary,
      "URI must be absolute and have microtonalist scheme!")

    val absolutePath = Paths.get(uri.getPath)
    // Making the path relative to root. E.g. "/path/to/file" => "path/to/file"
    val relativePath = absolutePath.getRoot.relativize(absolutePath)
    libraryUri.resolve(new URI(relativePath.toString))
  }
}

// TODO #38 Not sure about the name of this class
class DefaultScaleRepo(fileScaleRepo: FileScaleRepo,
                       httpScaleRepo: HttpScaleRepo,
                       microtonalistLibraryScaleRepo: MicrotonalistLibraryScaleRepo) extends ComposedScaleRepo {
  override def getScaleRepo(uri: URI): Option[ScaleRepo] = uri.getScheme match {
    case null | UriScheme.File => Some(fileScaleRepo)
    case UriScheme.Http | UriScheme.Https => Some(httpScaleRepo)
    case UriScheme.MicrotonalistLibrary => Some(microtonalistLibraryScaleRepo)
    case _ => None
  }
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