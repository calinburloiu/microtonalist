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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.*
import play.api.libs.json.JsObject

import java.io.{BufferedReader, EOFException, InputStreamReader, OutputStreamWriter}
import java.nio.channels.{Channels, SocketChannel}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CompletableFuture, ExecutionException, Executor, Executors, TimeUnit, TimeoutException}
import scala.concurrent.duration.*

/**
 * The QA server's side of the connection to the driver: sends one request at a time and reads its answer, as the
 * driver's [[Protocol]] says.
 *
 * It reads lines of any length, since a snapshot's answer carries a whole PNG on one line.
 *
 * @param channel the connection that the driver made
 * @param timeout how long to wait for each line; after it, the connection is closed
 */
final class DriverClient(channel: SocketChannel, timeout: FiniteDuration = 45.seconds) {
  private val reader: BufferedReader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), UTF_8))
  private val writer: OutputStreamWriter = OutputStreamWriter(Channels.newOutputStream(channel), UTF_8)
  private var lastId: Long = 0

  /**
   * Reads the [[Hello]] that the driver writes once connected; call it once, before any request.
   *
   * @throws QaException if the hello is unreadable or doesn't come in time
   * @throws java.io.IOException if the connection closes or fails
   */
  def hello(): Hello = synchronized {
    Protocol.decodeHello(readLine()).fold(error => throw QaException(s"the driver's hello is unreadable: $error"),
      identity)
  }

  /**
   * Sends a request to the driver and returns its result.
   *
   * @throws QaException with the driver's message if the request failed, or if the answer is unreadable or doesn't come
   *   in time
   * @throws java.io.IOException if the connection closes or fails
   */
  def request(request: DriverRequest): JsObject = synchronized {
    lastId += 1
    val id = lastId
    writer.write(Protocol.encode(Envelope(id, request)) + "\n")
    writer.flush()

    Protocol.decodeResponse(readLine()) match {
      case Right(DriverResponse.Ok(`id`, result)) => result
      case Right(DriverResponse.Failed(`id`, message)) => throw QaException(message)
      case Right(DriverResponse.Ok(otherId, _)) => throw answeredAnother(otherId, id)
      case Right(DriverResponse.Failed(otherId, _)) => throw answeredAnother(otherId, id)
      case Left(error) => throw QaException(s"the driver's answer is unreadable: $error")
    }
  }

  /** Closes the connection, which also unblocks a request waiting for its answer. */
  def close(): Unit = channel.close()

  private def readLine(): String = {
    // A blocking channel has no read timeout, so the read runs on a thread of its own.
    val line = CompletableFuture.supplyAsync(() => reader.readLine(), DriverClient.ReaderExecutor)
    val result =
      try {
        line.get(timeout.toMillis, TimeUnit.MILLISECONDS)
      } catch {
        case _: TimeoutException =>
          close()
          throw QaException(s"the driver didn't answer in ${DriverClient.describe(timeout)}")
        case e: ExecutionException => throw e.getCause
      }
    Option(result).getOrElse(throw EOFException("the driver closed the connection"))
  }

  private def answeredAnother(otherId: Long, id: Long): QaException = {
    QaException(s"the driver answered request $otherId instead of $id")
  }
}

private[qa] object DriverClient {
  /** Runs the blocking reads, each on a virtual thread of its own. */
  private val ReaderExecutor: Executor = Executors.newVirtualThreadPerTaskExecutor()

  /** A duration for a message: in whole seconds, e.g. `60 s`, or else in milliseconds, e.g. `200 ms`. */
  def describe(duration: FiniteDuration): String = {
    if (duration.toMillis % 1000 == 0) s"${duration.toSeconds} s" else s"${duration.toMillis} ms"
  }
}
