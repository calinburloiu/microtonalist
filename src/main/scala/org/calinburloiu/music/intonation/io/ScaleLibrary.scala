package org.calinburloiu.music.intonation.io

import java.io.{FileInputStream, FileNotFoundException}
import java.nio.file.Path

import com.google.common.io.Files
import org.calinburloiu.music.intonation.{Interval, Scale}

import scala.util.Try

trait ScaleLibrary extends RefResolver[Scale[Interval]] {

  protected val scaleReaderRegistry: ScaleReaderRegistry

  // TODO Make this an actual URI
  def get(uri: String, mediaType: Option[String] = None): Scale[Interval]
}

// TODO DI
class LocalScaleLibrary(
    protected override val scaleReaderRegistry: ScaleReaderRegistry,
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

class ScaleNotFoundException(uri: String, mediaType: Option[String], cause: Throwable = null)
    extends RuntimeException(s"A scale $uri with ${mediaType.getOrElse("any")} media type was not found",
        cause)
