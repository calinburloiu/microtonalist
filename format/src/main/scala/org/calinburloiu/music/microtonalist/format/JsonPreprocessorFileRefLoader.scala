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
import play.api.libs.json.{JsObject, JsPath, Json}

import java.io.{FileInputStream, FileNotFoundException}
import java.net.URI
import java.nio.file.Paths
import scala.util.Try

/**
 * Loader for JSON preprocessor references that loads the referenced URIs from the file system without
 * performing any validation and checking for the path context.
 */
class JsonPreprocessorFileRefLoader extends JsonPreprocessorRefLoader {
  override def load(uri: URI, pathContext: JsPath): Option[JsObject] = {
    if (uri.isAbsolute && uri.getScheme != UriScheme.File) {
      None
    } else {
      val path = pathOf(uri)
      val inputStream = Try {
        new FileInputStream(path.toString)
      }.recover {
        case e: FileNotFoundException => throw new JsonPreprocessorRefLoadException(uri, pathContext,
          s"Referenced file $uri not found at $pathContext", e.getCause)
      }.get

      Json.parse(inputStream) match {
        case obj: JsObject => Some(obj)
        case _ => throw new JsonPreprocessorRefLoadException(uri, pathContext,
          s"Referenced JSON from $uri at $pathContext must be a JSON object")
      }
    }
  }
}
