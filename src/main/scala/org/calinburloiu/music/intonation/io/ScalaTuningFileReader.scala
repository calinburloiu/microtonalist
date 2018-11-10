package org.calinburloiu.music.intonation.io

import java.io.{IOException, InputStream}
import java.nio.charset.StandardCharsets

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation._

import scala.io.Source
import scala.util.Try
import scala.util.matching.Regex

trait ScalaTuningFileReader extends ScaleReader {

  @throws[IOException]
  @throws[InvalidScalaTuningFileException]
  override def read(inputStream: InputStream): Scale[Interval]
}

// TODO DI
object ScalaTuningFileReader extends ScalaTuningFileReaderImpl

class ScalaTuningFileReaderImpl extends ScalaTuningFileReader with StrictLogging {

  private[this] val intervalValueRegex: Regex = """[\s]*([\d]+[./]{0,1}[\d]*).*""".r

  override def read(inputStream: InputStream): Scale[Interval] = {
    val lines = Source.fromInputStream(inputStream, StandardCharsets.ISO_8859_1.toString).getLines()
      .filter(!_.startsWith("!")).toIndexedSeq
    logger.debug(s"The .scl file has ${lines.size} line(s)")

    if (lines.size < 2)
      throw new InvalidScalaTuningFileException("Invalid file format: it should have at least 2 lines")

    val description = lines.head
    val pitchesCount = Try(lines(1).toInt).recover {
      case e: NumberFormatException => throw new InvalidScalaTuningFileException(
        "Invalid file format: the number of pitches is not a number", e)
    }.get

    val pitchValues = lines.slice(2, 2 + pitchesCount)
    // .slice might return less items than expected
    if (pitchValues.lengthCompare(pitchesCount) != 0)
      throw new InvalidScalaTuningFileException(
        s"Invalid file format: expected $pitchesCount pitches, but only ${pitchValues.size} were present in the file")

    val pitches = pitchValues.zipWithIndex.map {
      case (intervalValueRegex(pitchValue), pitchIndex) =>
        val pitch = Interval.fromScalaTuningInterval(pitchValue).getOrElse(
          throw new InvalidScalaTuningFileException(
            s"Invalid file format: the value of pitch with index ${pitchIndex + 1} is invalid")
        )

        logger.debug(s"Converted pitch value $pitchValue to $pitch")

        pitch

      case (_, pitchIndex) =>
        throw new InvalidScalaTuningFileException(
          s"Invalid file format: the value of pitch with index ${pitchIndex + 1} is invalid")
    }

    ScaleReader.createScale(description, pitches)
  }
}

class InvalidScalaTuningFileException(message: String, cause: Throwable = null)
  extends InvalidScaleFormatException(message, cause)
