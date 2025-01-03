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
import play.api.libs.json.{JsObject, JsPath, Json}

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}

/**
 * Loader for JSON preprocessor references that retrieves the referenced URIs via HTTP without
 * performing any validation and checking for the path context.
 */
class JsonPreprocessorHttpRefLoader(httpClient: HttpClient) extends JsonPreprocessorRefLoader with StrictLogging {

  override def load(uri: URI, pathContext: JsPath): Option[JsObject] = {
    if (!(uri.isAbsolute && UriScheme.HttpSet.contains(uri.getScheme))) {
      return None
    }

    logger.info(s"Reading JSON preprocessor reference $uri via HTTP...")
    val request = HttpRequest.newBuilder(uri)
      .GET()
      .build()
    val response = httpClient.send(request, BodyHandlers.ofInputStream())
    val result = response.statusCode() match {
      case 200 =>
        Json.parse(response.body()) match {
          case obj: JsObject => Some(obj)
          case _ => throw new JsonPreprocessorRefLoadException(uri, pathContext,
            s"Referenced JSON from $uri at $pathContext must be a JSON object")
        }
      case 404 => throw new ScaleNotFoundException(uri)
      case status if status >= 400 && status < 600 =>
        throw new JsonPreprocessorRefLoadException(uri, pathContext,
          s"HTTP request to $uri returned status code $status")
      case status =>
        throw new JsonPreprocessorRefLoadException(uri, pathContext,
          s"Unexpected HTTP response status code $status for $uri")
    }
    logger.info(s"Successfully read JSON preprocessor reference from $uri via HTTP")
    result
  }
}
