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

import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import org.calinburloiu.music.microtonalist.ai.mcp.PlayJsonMcp

import java.io.{FilterInputStream, IOException, InputStream, OutputStream}
import java.util.concurrent.CompletableFuture

/** The QA MCP server, `microtonalist-qa`, which offers the [[QaTools]] over stdio. */
object QaMcpServer {
  /** The name that the server tells its clients. */
  val Name: String = "microtonalist-qa"

  /**
   * Starts the server on the given streams, with one JSON-RPC message per line.
   *
   * @param input the stream of the client's messages, whose end, or failure, means that the client is gone
   * @param output the stream of the server's messages, which nothing else may write to
   * @param version the server's version, which it tells its clients
   * @return a future that completes when the input ends
   */
  def start(service: QaService, input: InputStream, output: OutputStream, version: String): CompletableFuture[Unit] = {
    val inputEnded = CompletableFuture[Unit]()
    val transport = StdioServerTransportProvider(PlayJsonMcp.jsonMapper, EndAwareInputStream(input, inputEnded), output)

    McpServer.sync(transport)
      .serverInfo(Name, version)
      .capabilities(ServerCapabilities.builder().tools(true).build())
      .tools(QaTools.all(service)*)
      .build()
    inputEnded
  }

  /** An input stream that completes a future when it ends or fails. */
  private final class EndAwareInputStream(input: InputStream, ended: CompletableFuture[Unit])
    extends FilterInputStream(input) {

    override def read(): Int = completeAtEnd(super.read())

    override def read(bytes: Array[Byte], offset: Int, length: Int): Int = {
      completeAtEnd(super.read(bytes, offset, length))
    }

    private def completeAtEnd(read: => Int): Int = {
      val result =
        try {
          read
        } catch {
          case e: IOException =>
            ended.complete(())
            throw e
        }
      if (result == -1) {
        ended.complete(())
      }
      result
    }
  }
}
