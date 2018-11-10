package org.calinburloiu.music.microtuner.io

import java.io.InputStream
import java.nio.file.Path

import org.calinburloiu.music.microtuner.ScaleList

trait ScaleListReader {

  def read(inputStream: InputStream): ScaleList
}

class InvalidScaleListFileException(message: String, cause: Throwable = null) extends Exception(message, cause)
