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

import java.io.InputStream
import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.FutureConverters.CompletionStageOps
import scala.jdk.OptionConverters.RichOptional
import scala.util.{Failure, Success}

/**
 * Scale repository implementation that retrieves and persists scales remotely by using HTTP.
 *
 * @param httpClient          HTTP client configured to access scales
 * @param scaleFormatRegistry registry responsible for choosing the scale format
 */
class HttpScaleRepo(httpClient: HttpClient,
                    scaleFormatRegistry: ScaleFormatRegistry) extends ScaleRepo with StrictLogging {

  override def read(uri: URI, context: Option[ScaleFormatContext] = None): Scale[Interval] = {
    checkReadRequirements(uri)

    logger.info(s"Reading scale from $uri via HTTP...")
    val request = createReadRequest(uri)
    val response = httpClient.send(request, BodyHandlers.ofInputStream())

    val result = handleReadResponse(uri, response)
    logger.info(s"Successfully read scale from $uri via HTTP")
    result
  }

  override def readAsync(uri: URI, context: Option[ScaleFormatContext] = None): Future[Scale[Interval]] = {
    checkReadRequirements(uri)

    logger.info(s"Reading scale from $uri via HTTP...")
    val request = createReadRequest(uri)
    val futureResponse: Future[HttpResponse[InputStream]] = httpClient
      .sendAsync(request, BodyHandlers.ofInputStream())
      .asScala

    futureResponse
      .map { response => handleReadResponse(uri, response) }
      .andThen {
        case Success(_) => logger.info(s"Successfully read scale from $uri via HTTP")
        case Failure(exception) => logger.error(s"Failed to read scale from $uri via HTTP!",
          exception)
      }
  }

  private def checkReadRequirements(uri: URI): Unit = require(
    uri.isAbsolute && UriScheme.HttpSet.contains(uri.getScheme),
    "URI must be absolute and have http/https scheme!"
  )

  private def createReadRequest(uri: URI): HttpRequest = HttpRequest.newBuilder(uri)
    .GET()
    .build()

  private def handleReadResponse(uri: URI,
                                 response: HttpResponse[InputStream]): Scale[Interval] = response.statusCode() match {
    case 200 =>
      val mediaType = response.headers().firstValue(GuavaHttpHeaders.CONTENT_TYPE).toScala.map(MediaType.parse)

      val scaleFormat = scaleFormatRegistry.get(uri, mediaType)
        .getOrElse(throw new BadScaleRequestException(uri, mediaType))

      scaleFormat.read(response.body(), Some(uri))
    case 404 => throw new ScaleNotFoundException(uri)
    case status if status >= 400 && status < 500 => throw new BadScaleRequestException(uri, None,
      Some(s"HTTP request to $uri returned status code $status"))
    case status if status >= 500 && status < 600 => throw new ScaleReadFailureException(uri,
      s"HTTP request to $uri returned status code $status")
    case status => throw new ScaleReadFailureException(uri, s"Unexpected HTTP response status code $status for $uri")
  }

  override def write(scale: Scale[Interval],
                     uri: URI,
                     mediaType: Option[MediaType],
                     context: Option[ScaleFormatContext] = None): Unit = ???

  override def writeAsync(scale: Scale[Interval],
                          uri: URI,
                          mediaType: Option[MediaType],
                          context: Option[ScaleFormatContext] = None): Future[Unit] = ???
}
