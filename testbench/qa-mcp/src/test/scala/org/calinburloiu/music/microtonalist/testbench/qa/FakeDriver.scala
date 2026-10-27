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
import play.api.libs.json.Json

import java.io.{BufferedReader, IOException, InputStreamReader, OutputStreamWriter}
import java.net.UnixDomainSocketAddress
import java.nio.channels.{Channels, SocketChannel}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import scala.collection.mutable.ArrayBuffer

/**
 * Connects to the QA server's socket as the driver does once the app shows a window: says [[Hello]] with the given PID,
 * or `helloLine` instead if given, then answers each request with the line that [[script]] gives, until the connection
 * closes.
 */
class FakeDriver(socket: Path, pid: Long, platform: GuiPlatform = GuiPlatform.Headless,
                 helloLine: Option[String] = None) {
  /** The line that answers a request; by default, an empty success. */
  @volatile var script: Envelope => String = envelope => Protocol.encode(DriverResponse.Ok(envelope.id, Json.obj()))

  /** Completes once the server has closed the connection, or it failed. */
  val disconnected: CompletableFuture[Unit] = CompletableFuture()

  private val _requests: ArrayBuffer[DriverRequest] = ArrayBuffer()
  private val channel: SocketChannel = SocketChannel.open(UnixDomainSocketAddress.of(socket))
  private val writer: OutputStreamWriter = OutputStreamWriter(Channels.newOutputStream(channel), UTF_8)

  writeLine(helloLine.getOrElse(Protocol.encode(Hello(pid, platform))))
  Thread.ofVirtual().start(() => answerRequests())

  /** Each request it received, in order. */
  def requests: Seq[DriverRequest] = synchronized(_requests.toSeq)

  /** Closes the connection, as the driver's does when the app exits. */
  def close(): Unit = channel.close()

  private def answerRequests(): Unit = {
    val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), UTF_8))
    try {
      Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
        val envelope = Protocol.decodeEnvelope(line).toOption.get
        synchronized {
          _requests += envelope.request
        }
        writeLine(script(envelope))
      }
    } catch {
      case _: IOException => ()
    } finally {
      disconnected.complete(())
    }
  }

  private def writeLine(line: String): Unit = {
    writer.write(line + "\n")
    writer.flush()
  }
}
