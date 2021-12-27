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

import com.google.common.net.{MediaType, HttpHeaders => GuavaHttpHeaders}
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.{Interval, Scale}

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import scala.jdk.OptionConverters.RichOptional

class HttpScaleRepo(httpClient: HttpClient,
                    scaleFormatRegistry: ScaleFormatRegistry) extends ScaleRepo with StrictLogging {

  override def read(uri: URI): Scale[Interval] = {
    require(uri.isAbsolute && UriScheme.HttpSet.contains(uri.getScheme), "URI must be absolute and have http/https " +
      "scheme!")

    val request = HttpRequest.newBuilder(uri)
      .GET()
      .build()
    val response = httpClient.send(request, BodyHandlers.ofInputStream())
    response.statusCode() match {
      case 200 =>
        val mediaType = response.headers().firstValue(GuavaHttpHeaders.CONTENT_TYPE).toScala.map(MediaType.parse)

        val scaleFormat = scaleFormatRegistry.get(uri, mediaType)
          .getOrElse(throw new BadScaleRequestException(uri, mediaType))

        logger.info(s"Reading scale from $uri via HTTP...")
        scaleFormat.read(response.body(), Some(baseUriOf(uri)))
      case 404 => throw new ScaleNotFoundException(uri)
      case status if status >= 400 && status < 500 => throw new BadScaleRequestException(uri, None,
        Some(s"HTTP response status code $status"))
      case status if status >= 500 && status < 600 => throw new ScaleReadFailureException(uri,
        s"HTTP response status code $status")
      case status => throw new ScaleReadFailureException(uri, s"Unexpected HTTP response status code $status")
    }
  }

  override def write(scale: Scale[Interval], uri: URI, mediaType: Option[MediaType]): Unit = ???
}
