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
  private val BaseUrlKey: String = "baseUrl"
  private val RefKey: String = "$ref"
  private val TracksExtension: String = ".tracks"
  private val FileScheme: String = "file"

  /**
   * Finds the rig file of `composition`, whether it exists or not, as the app does: its `tracksUrl`, resolved against
   * its `baseUrl`, itself resolved against the composition; or else `<composition>.tracks`.
   *
   * @return the rig file's real path; or an error if the composition can't be read as JSON, if it has a `$ref` at its
   *   root, which could bring in another `tracksUrl` or `baseUrl`, or if the rig file would be outside the run
   *   directory or on a URL scheme other than `file`
   */
  def locate(composition: Path, run: QaRun): Either[String, Path] = {
    for {
      json <- readJson(composition)
      _ <- refuseRootRef(json)
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

  private def refuseRootRef(json: JsValue): Either[String, Unit] = {
    if ((json \ RefKey).isDefined) {
      Left(s"The composition can't have a $RefKey at its root, since it could hide where its rig is")
    } else {
      Right(())
    }
  }

  /** The rig file as the app resolves it; see `JsonCompositionFormat` and `Composition.tracksUrl`. */
  private def rigFileOf(composition: Path, json: JsValue): Either[String, Path] = {
    val compositionUri = composition.toUri
    optionalString(json, TracksUrlKey).flatMap {
      // The app derives the default from the composition's own URL, not from its `baseUrl`.
      case None => Right(composition.resolveSibling(s"${composition.getFileName}$TracksExtension"))
      case Some(tracksUrl) =>
        for {
          baseUrl <- optionalString(json, BaseUrlKey)
          baseUri <- baseUrl.fold(Right(compositionUri))(url => parseUri(BaseUrlKey, url).map(compositionUri.resolve))
          tracksUri <- parseUri(TracksUrlKey, tracksUrl)
          rigFile <- fileOf(baseUri.resolve(tracksUri), tracksUrl)
        } yield rigFile
    }
  }

  /** The string value of the composition's `key`, absent if missing or `null`. */
  private def optionalString(json: JsValue, key: String): Either[String, Option[String]] = {
    (json \ key).toOption match {
      case None | Some(JsNull) => Right(None)
      case Some(JsString(value)) => Right(Some(value))
      case Some(other) => Left(s"The composition's $key $other isn't a string")
    }
  }

  private def parseUri(key: String, url: String): Either[String, URI] = {
    try {
      Right(URI(url))
    } catch {
      case e: URISyntaxException => Left(s"The composition's $key \"$url\" is invalid: ${e.getMessage}")
    }
  }

  private def fileOf(uri: URI, tracksUrl: String): Either[String, Path] = {
    if (uri.getScheme == FileScheme) {
      try {
        Right(Path.of(uri))
      } catch {
        case e: IllegalArgumentException =>
          Left(s"The composition's $TracksUrlKey \"$tracksUrl\" resolves to $uri, which names no file: ${e.getMessage}")
      }
    } else {
      Left(s"The composition's $TracksUrlKey \"$tracksUrl\" resolves to $uri, which isn't a $FileScheme: URL")
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
