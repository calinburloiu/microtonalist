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

import com.google.common.io.Files
import org.calinburloiu.music.intonation.{Interval, Scale}

import java.io.{FileInputStream, FileNotFoundException}
import java.nio.file.Path
import scala.util.Try

/**
 * Repository pattern trait used for retrieving scales by their URI. Implementations are responsible for implementing
 * the retrieval from a particular data source like file, Web or cloud service.
 */
trait ScaleLibrary extends RefResolver[Scale[Interval]] {

  protected val scaleReaderRegistry: ScaleFormatRegistry

  // TODO Make this an actual URI
  /**
   * Retrieves a scale.
   *
   * @param uri       universal resource identifier for the scale
   * @param mediaType the media type that identifies the format of the scale. If not provided, the extension might be
   *                  used for identification.
   * @return the identified scale
   */
  def get(uri: String, mediaType: Option[String] = None): Scale[Interval]
}

/**
 * Repository used for retrieving scales from local library based on files.
 *
 * @param scaleReaderRegistry provides a mapping between the scale media type or extension and the format class used for
 *                            deserializing it
 * @param scaleLibraryPath    path on the local machine where the scales are stored
 */
class LocalScaleLibrary(protected override val scaleReaderRegistry: ScaleFormatRegistry,
                        scaleLibraryPath: Path) extends ScaleLibrary {

  override def get(uri: String, mediaType: Option[String] = None): Scale[Interval] = {
    val scaleRelativePath = uri
    val scaleAbsolutePath = scaleLibraryPath.resolve(scaleRelativePath)
    val scaleInputStream = Try {
      new FileInputStream(scaleAbsolutePath.toString)
    }.recover {
      case e: FileNotFoundException => throw new ScaleNotFoundException(uri, mediaType, e.getCause)
    }.get
    val scaleReader = mediaType match {
      case Some(actualMediaType) => scaleReaderRegistry.getByMediaType(actualMediaType)
      case None =>
        val extension = Files.getFileExtension(scaleRelativePath)
        scaleReaderRegistry.getByExtension(extension)
    }

    scaleReader.read(scaleInputStream)
  }
}

/**
 * Exception thrown is the requested scale could not be found.
 */
class ScaleNotFoundException(uri: String, mediaType: Option[String], cause: Throwable = null)
  extends RuntimeException(s"A scale $uri with ${mediaType.getOrElse("any")} media type was not found",
    cause)
