/*
 * Copyright 2025 Calin-Andrei Burloiu
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
import org.calinburloiu.music.microtonalist.tuner.*

import java.io.InputStream
import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.{Failure, Success}

/**
 * A concrete implementation of the [[TrackRepo]] trait that retrieves and persists track specifications
 * via HTTP.
 *
 * @param httpClient              The HTTP client used for sending HTTP requests.
 * @param trackFormat             Format object used to read and write track specifications.
 * @param synchronousAwaitTimeout The maximum duration to wait for asynchronous operations to complete when used 
 *                                synchronously.
 */
class HttpTrackRepo(httpClient: HttpClient,
                    trackFormat: TrackFormat,
                    synchronousAwaitTimeout: FiniteDuration = 1 minute) extends TrackRepo with StrictLogging {

  override def readTracks(uri: URI): TrackSpecs = {
    checkRequirements(uri)

    logger.info(s"Reading tracks from $uri via HTTP...")
    val request = createReadRequest(uri)
    val response = httpClient.send(request, BodyHandlers.ofInputStream())

    val result = Await.result(handleReadResponse(uri, response), synchronousAwaitTimeout)
    logger.info(s"Successfully read tracks from $uri")
    result
  }

  override def readTracksAsync(uri: URI): Future[TrackSpecs] = {
    checkRequirements(uri)

    logger.info(s"Reading tracks from $uri via HTTP...")
    val request = createReadRequest(uri)
    val futureResponse: Future[HttpResponse[InputStream]] = httpClient
      .sendAsync(request, BodyHandlers.ofInputStream())
      .asScala

    futureResponse
      .flatMap { response => handleReadResponse(uri, response) }
      .andThen {
        case Success(_) => logger.info(s"Successfully read tracks from $uri via HTTP")
        case Failure(exception) => logger.error(s"Failed to read tracks from $uri via HTTP!", exception)
      }
  }

  override def writeTracks(trackSpecs: TrackSpecs, uri: URI): Unit = ???

  override def writeTracksAsync(trackSpecs: TrackSpecs, uri: URI): Future[Unit] = ???

  private def checkRequirements(uri: URI): Unit = require(
    uri.isAbsolute && UriScheme.HttpSet.contains(uri.getScheme),
    "URI must be absolute and have an http/https scheme!"
  )

  private def createReadRequest(uri: URI): HttpRequest = HttpRequest.newBuilder(uri)
    .GET()
    .build()

  private def handleReadResponse(uri: URI, response: HttpResponse[InputStream]): Future[TrackSpecs] = {
    response.statusCode() match {
      case 200 => trackFormat.readTracksAsync(response.body(), Some(uri))
      case 404 => throw new TracksNotFoundException(uri)
      case status if status >= 400 && status < 500 => throw new BadTracksRequestException(uri,
        Some(s"HTTP request to $uri returned status code $status"))
      case status if status >= 500 && status < 600 => throw new TracksReadFailureException(uri,
        s"HTTP request to $uri returned status code $status")
      case status => throw new TracksReadFailureException(uri,
        s"Unexpected HTTP response status code $status for $uri")
    }
  }
}
