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
import org.calinburloiu.music.microtonalist.core.ScaleList

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}

/**
 * Scale repository implementation that retrieves and persists scale lists remotely by using HTTP.
 *
 * @param httpClient      HTTP client configured to access scale lists
 * @param scaleListFormat format implementation responsible for (de)serialization.
 */
class HttpScaleListRepo(httpClient: HttpClient, scaleListFormat: ScaleListFormat)
  extends ScaleListRepo with StrictLogging {

  override def read(uri: URI): ScaleList = {
    require(uri.isAbsolute && UriScheme.HttpSet.contains(uri.getScheme),
      "URI must be absolute and have an http/https scheme!")

    val request = HttpRequest.newBuilder(uri)
      .GET()
      .build()
    val response = httpClient.send(request, BodyHandlers.ofInputStream())
    response.statusCode() match {
      case 200 =>
        logger.info(s"Reading scale list from $uri via HTTP...")
        scaleListFormat.read(response.body(), Some(baseUriOf(uri)))
      case 404 =>
        throw new ScaleListNotFoundException(uri)
      case status if status >= 400 && status < 500 =>
        throw new BadScaleListRequestException(uri, Some(s"HTTP response status code $status"))
      case status if status >= 500 && status < 600 =>
        throw new ScaleListReadFailureException(uri, s"HTTP response status code $status")
      case status =>
        throw new ScaleListReadFailureException(uri, s"Unexpected HTTP response status code $status")
    }
  }

  override def write(scaleList: ScaleList, uri: URI): Unit = ???
}
