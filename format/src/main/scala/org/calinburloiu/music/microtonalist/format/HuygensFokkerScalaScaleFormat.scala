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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation._

import java.io.{IOException, InputStream, OutputStream}
import java.net.URI
import java.nio.charset.StandardCharsets
import scala.io.Source
import scala.util.Try
import scala.util.matching.Regex

/**
 * [[ScaleFormat]] implementation that provides support for the
 * [[https://www.huygens-fokker.org/scala/ Scala application]] (from Huygens-Fokker organization) `.scl` files.
 */
class HuygensFokkerScalaScaleFormat extends ScaleFormat with StrictLogging {

  override val metadata: ScaleFormatMetadata = ScaleFormatMetadata("Huygens-Fokker Scala Application Scale", Set("scl"))

  private val intervalValueRegex: Regex = """[\s]*([\d]+[./]?[\d]*).*""".r

  @throws[IOException]
  @throws[InvalidHuygensFokkerScalaFileException]
  override def read(inputStream: InputStream,
                    baseUri: Option[URI] = None,
                    context: Option[ScaleFormatContext] = None): Scale[Interval] = {
    val lines = Source.fromInputStream(inputStream, StandardCharsets.ISO_8859_1.toString).getLines()
      .filter(!_.startsWith("!")).toIndexedSeq
    logger.debug(s"The .scl file has ${lines.size} line(s)")

    if (lines.size < 2)
      throw new InvalidHuygensFokkerScalaFileException("Invalid file format: it should have at least 2 lines")

    val description = lines.head
    val pitchesCount = Try(lines(1).toInt).recover {
      case e: NumberFormatException => throw new InvalidHuygensFokkerScalaFileException(
        "Invalid file format: the number of pitches is not a number", e)
    }.get

    val pitchValues = lines.slice(2, 2 + pitchesCount)
    // .slice might return less items than expected
    if (pitchValues.lengthCompare(pitchesCount) != 0)
      throw new InvalidHuygensFokkerScalaFileException(
        s"Invalid file format: expected $pitchesCount pitches, but only ${pitchValues.size} were present in the file")

    val pitches = pitchValues.zipWithIndex.map {
      case (intervalValueRegex(pitchValue), pitchIndex) =>
        val pitch = Interval.fromScalaTuningInterval(pitchValue).getOrElse(
          throw new InvalidHuygensFokkerScalaFileException(
            s"Invalid file format: the value of pitch with index ${pitchIndex + 1} is invalid")
        )

        logger.debug(s"Converted pitch value $pitchValue to $pitch")

        pitch

      case (_, pitchIndex) =>
        throw new InvalidHuygensFokkerScalaFileException(
          s"Invalid file format: the value of pitch with index ${pitchIndex + 1} is invalid")
    }

    Scale.create(description, pitches)
  }

  override def write(scale: Scale[Interval],
                     outputStream: OutputStream,
                     context: Option[ScaleFormatContext] = None): Unit = ???
}

class InvalidHuygensFokkerScalaFileException(message: String, cause: Throwable = null)
  extends InvalidScaleFormatException(message, cause)
