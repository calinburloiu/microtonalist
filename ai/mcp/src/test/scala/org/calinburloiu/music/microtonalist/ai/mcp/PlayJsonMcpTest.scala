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

package org.calinburloiu.music.microtonalist.ai.mcp

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, CallToolResult, ImageContent, TextContent}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsObject, Json, OWrites, Reads}

import scala.jdk.CollectionConverters.*

class PlayJsonMcpTest extends AnyWordSpec with Matchers with Inside {

  private case class EchoArgs(text: String)

  private case class EchoResult(echoed: String)

  private given Reads[EchoArgs] = Json.reads[EchoArgs]

  private given OWrites[EchoResult] = Json.writes[EchoResult]

  private val echoSchema: JsObject = Json.obj(
    "type" -> "object",
    "properties" -> Json.obj("text" -> Json.obj("type" -> "string")),
    "required" -> Json.arr("text"),
  )

  private lazy val echoTool: SyncToolSpecification =
    PlayJsonMcp.tool[EchoArgs, EchoResult]("echo", "Echoes the text", echoSchema) { args => EchoResult(args.text) }

  private val hiArguments: java.util.Map[String, Object] = java.util.Map.of("text", "hi")

  /** Calls the tool's handler as the SDK does, with a request built by the SDK. */
  private def call(spec: SyncToolSpecification,
                   arguments: java.util.Map[String, Object] = hiArguments): CallToolResult = {
    val request = CallToolRequest.builder(spec.tool().name()).arguments(arguments).build()
    spec.callHandler().apply(null, request)
  }

  /** The text of a result whose only content is a text. */
  private def textOf(result: CallToolResult): String = inside(result.content().asScala.toList) {
    case List(content: TextContent) => content.text()
  }

  "PlayJsonMcp.tool" should {
    "give the SDK tool its name, description and input schema" in {
      // When
      val tool = echoTool.tool()

      // Then
      tool.name() shouldBe "echo"
      tool.description() shouldBe "Echoes the text"
      tool.inputSchema().get("type") shouldBe "object"
      tool.inputSchema().get("required") shouldBe java.util.List.of("text")
    }

    "read the arguments of the SDK's CallToolRequest and return the result" in {
      // When
      val result = call(echoTool)

      // Then
      result.isError() should not be java.lang.Boolean.TRUE
      result.structuredContent() shouldBe Json.obj("echoed" -> "hi")
      result.content().asScala.toList shouldBe List(
        TextContent.builder(Json.prettyPrint(Json.obj("echoed" -> "hi"))).build())
    }

    "read missing arguments as an empty object" in {
      // Given
      val identity = PlayJsonMcp.tool[JsObject, JsObject]("identity", "Returns its arguments", Json.obj()) { args =>
        args
      }

      // When
      val result = call(identity, arguments = null)

      // Then
      result.isError() should not be java.lang.Boolean.TRUE
      result.structuredContent() shouldBe Json.obj()
    }

    "turn arguments that don't match the Reads into an error result" in {
      // When
      val result = call(echoTool, arguments = java.util.Map.of("txt", "hi"))

      // Then
      result.isError() shouldBe java.lang.Boolean.TRUE
      textOf(result) shouldBe "/text: error.path.missing"
    }

    "turn an exception thrown by the tool into an error result" in {
      // Given
      val failing = PlayJsonMcp.tool[EchoArgs, EchoResult]("fail", "Fails", echoSchema) { _ =>
        throw IllegalStateException("no app running")
      }

      // When
      val result = call(failing)

      // Then
      result.isError() shouldBe java.lang.Boolean.TRUE
      textOf(result) shouldBe "no app running"
    }

    "describe an exception without a message by its class" in {
      // Given
      val failing = PlayJsonMcp.tool[EchoArgs, EchoResult]("fail", "Fails", echoSchema) { _ =>
        throw IllegalStateException()
      }

      // When
      val result = call(failing)

      // Then
      result.isError() shouldBe java.lang.Boolean.TRUE
      textOf(result) shouldBe "java.lang.IllegalStateException"
    }

    "write the structured content through the JSON mapper" in {
      // Given
      val result = call(echoTool)

      // When
      val json = PlayJsonMcp.jsonMapper.writeValueAsString(result)

      // Then
      json should include(""""structuredContent":{"echoed":"hi"}""")
    }
  }

  "PlayJsonMcp.imageTool" should {
    "return each PNG as an image, then the result as text" in {
      // Given
      val screenshot = PlayJsonMcp.imageTool[EchoArgs, JsObject]("screenshot", "Takes screenshots", echoSchema) { _ =>
        ImageResult(Seq(Array[Byte](1, 2, 3), Array[Byte](4, 5, 6)), Json.obj("file" -> "a.png"))
      }

      // When
      val result = call(screenshot)

      // Then
      result.isError() should not be java.lang.Boolean.TRUE
      result.structuredContent() shouldBe Json.obj("file" -> "a.png")
      result.content().asScala.toList shouldBe List(
        ImageContent.builder("AQID", "image/png").build(),
        ImageContent.builder("BAUG", "image/png").build(),
        TextContent.builder(Json.prettyPrint(Json.obj("file" -> "a.png"))).build(),
      )
    }

    "turn an exception into an error result without images" in {
      // Given
      val failing = PlayJsonMcp.imageTool[EchoArgs, JsObject]("screenshot", "Takes screenshots", echoSchema) { _ =>
        throw IllegalStateException("no window")
      }

      // When
      val result = call(failing)

      // Then
      result.isError() shouldBe java.lang.Boolean.TRUE
      textOf(result) shouldBe "no window"
    }
  }
}
