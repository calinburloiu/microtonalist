/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.testbench.qa

import play.api.libs.json.{JsNull, JsString, JsValue, Json}

import java.io.IOException
import java.net.{URI, URISyntaxException}
import java.nio.file.{Files, Path}

/** Finds the rig file of a composition, as the app would, refusing one outside the run directory. */
object RigLocator {
  private val TracksUrlKey: String = "tracksUrl"
  private val TracksExtension: String = ".tracks"
  private val FileScheme: String = "file"

  /**
   * Finds the rig file of `composition`, whether it exists or not: its `tracksUrl`, a relative or `file:` URL resolved
   * against the composition, or else `<composition>.tracks`.
   *
   * @return the rig file's real path; or an error if the composition can't be read as JSON, or if the rig file would be
   *   outside the run directory or on another URL scheme
   */
  def locate(composition: Path, run: QaRun): Either[String, Path] = {
    for {
      json <- readJson(composition)
      rigFile <- rigFileOf(composition, json)
      resolved <- inRun(rigFile, run)
    } yield resolved
  }

  private def readJson(composition: Path): Either[String, JsValue] = {
    try {
      Right(Json.parse(Files.readString(composition)))
    } catch {
      case e: IOException => Left(s"Can't read the composition $composition as JSON: $e")
    }
  }

  private def rigFileOf(composition: Path, json: JsValue): Either[String, Path] = {
    (json \ TracksUrlKey).toOption match {
      case None | Some(JsNull) => Right(composition.resolveSibling(s"${composition.getFileName}$TracksExtension"))
      case Some(JsString(url)) => fileOfUrl(composition, url)
      case Some(other) => Left(s"The composition's $TracksUrlKey $other isn't a string")
    }
  }

  private def fileOfUrl(composition: Path, url: String): Either[String, Path] = {
    try {
      val resolved = composition.toUri.resolve(URI(url))
      if (resolved.getScheme == FileScheme) {
        Right(Path.of(resolved))
      } else {
        Left(s"The composition's $TracksUrlKey \"$url\" is neither relative nor a $FileScheme: URL")
      }
    } catch {
      case e: URISyntaxException => Left(s"The composition's $TracksUrlKey \"$url\" is invalid: ${e.getMessage}")
      case e: IllegalArgumentException =>
        Left(s"The composition's $TracksUrlKey \"$url\" names no file: ${e.getMessage}")
    }
  }

  private def inRun(rigFile: Path, run: QaRun): Either[String, Path] = {
    try {
      Right(run.resolve(rigFile.toString))
    } catch {
      case e: QaException => Left(s"The rig file must be in the run directory: ${e.getMessage}")
    }
  }
}
