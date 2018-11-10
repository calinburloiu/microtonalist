package org.calinburloiu.music.intonation.io

class ScaleReaderRegistry(val bindings: Seq[(FormatIdentifier, ScaleReader)]) {

  private[this] val byExtension: Map[String, ScaleReader] = (for {
    (FormatIdentifier(_, extensions, _), scaleReader) <- bindings
    extension <- extensions
  } yield extension -> scaleReader).toMap

  private[this] val byMediaType: Map[String, ScaleReader] = (for {
    (FormatIdentifier(_, _, mediaTypes), scaleReader) <- bindings
    mediaType <- mediaTypes
  } yield mediaType -> scaleReader).toMap

  /**
    * @throws UnsupportedOperationException if no [[ScaleReader]] is registered for the extension
    */
  def getByExtension(extension: String): ScaleReader = byExtension.getOrElse(extension, throw
      new UnsupportedOperationException(s"No ScaleReader registered for extension '$extension'"))

  /**
    * @throws UnsupportedOperationException if no [[ScaleReader]] is registered for the media type
    */
  def getByMediaType(mediaType: String): ScaleReader = byMediaType.getOrElse(mediaType, throw
      new UnsupportedOperationException(s"No ScaleReader registered for media type '$mediaType'"))
}

// TODO DI
object ScaleReaderRegistry extends ScaleReaderRegistry(Seq(
  (FormatIdentifier("Scala Application Scale", Set("scl")), ScalaTuningFileReader),
  (FormatIdentifier("JSON Scale", Set("jscl", "json")), JsonScaleReader)
))
