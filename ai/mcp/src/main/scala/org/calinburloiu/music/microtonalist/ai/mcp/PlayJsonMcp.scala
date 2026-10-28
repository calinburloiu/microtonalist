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

import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.scalalogging.StrictLogging
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema.{
  CallToolRequest, CallToolResult, Content, ImageContent, TextContent, Tool
}
import play.api.libs.json.*
import play.api.libs.json.jackson.PlayJsonMapperModule

import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Bridges play-json and the MCP Java SDK: the JSON mapper of the MCP servers, and the helpers that build their tools.
 *
 * See ai/mcp/docs/ARCHITECTURE.md.
 */
object PlayJsonMcp extends StrictLogging {
  /** Jackson's mapper, which also reads and writes play-json's `JsValue`s. */
  val objectMapper: ObjectMapper = ObjectMapper().registerModule(PlayJsonMapperModule(JsonConfig.settings))

  /** The mapper to give the SDK's transports, so that they write the tools' `JsObject` results. */
  val jsonMapper: McpJsonMapper = JacksonMcpJsonMapper(objectMapper)

  /**
   * Builds an SDK tool that reads its arguments with `I`'s `Reads`, calls `call` and writes the result with `O`'s
   * `OWrites`, as structured content and, for clients that don't read it, as text.
   *
   * Arguments that `I`'s `Reads` rejects, and an exception thrown by `call`, give a result with `isError` set and the
   * error as its text; the exception is also logged as a warning.
   *
   * @param inputSchema the JSON Schema of the arguments, which `I`'s `Reads` must agree with
   */
  def tool[I: Reads, O: OWrites](name: String, description: String, inputSchema: JsObject)(
      call: I => O): McpServerFeatures.SyncToolSpecification = {
    imageTool[I, O](name, description, inputSchema) { args => ImageResult(Seq.empty, call(args)) }
  }

  /**
   * Like [[tool]], but `call` also returns PNG images, which the result's content holds before the text; an error
   * result has no images.
   */
  def imageTool[I: Reads, O: OWrites](name: String, description: String, inputSchema: JsObject)(
      call: I => ImageResult[O]): McpServerFeatures.SyncToolSpecification = {
    val schema = objectMapper.convertValue(inputSchema, classOf[java.util.Map[String, Object]])
    val tool = Tool.builder(name, schema).description(description).build()

    McpServerFeatures.SyncToolSpecification.builder()
      .tool(tool)
      .callHandler { (_, request) => handle(name, request, call) }
      .build()
  }

  private def handle[I: Reads, O: OWrites](name: String, request: CallToolRequest,
                                           call: I => ImageResult[O]): CallToolResult = {
    try {
      readArguments(request).validate[I] match {
        case JsSuccess(args, _) => successResult(call(args))
        case JsError(errors) => errorResult(describe(errors))
      }
    } catch {
      case NonFatal(e) =>
        logger.warn(s"""The "$name" tool failed""", e)
        errorResult(Option(e.getMessage).getOrElse(e.getClass.getName))
    }
  }

  private def readArguments(request: CallToolRequest): JsValue = Option(request.arguments()) match {
    case Some(arguments) => objectMapper.convertValue(arguments, classOf[JsValue])
    case None => Json.obj()
  }

  private def describe(errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]): String = {
    errors.map { case (path, pathErrors) => s"$path: ${pathErrors.flatMap(_.messages).mkString(", ")}" }
      .mkString("; ")
  }

  private def successResult[O: OWrites](imageResult: ImageResult[O]): CallToolResult = {
    val structuredContent = Json.toJsObject(imageResult.result)
    val images = imageResult.pngs.map { png =>
      ImageContent.builder(Base64.getEncoder.encodeToString(png), "image/png").build()
    }
    val text = TextContent.builder(Json.prettyPrint(structuredContent)).build()
    val content: Seq[Content] = images :+ text

    CallToolResult.builder()
      .content(content.asJava)
      .structuredContent(structuredContent)
      .build()
  }

  private def errorResult(message: String): CallToolResult = {
    CallToolResult.builder().addTextContent(message).isError(true).build()
  }
}

/**
 * The result of an [[PlayJsonMcp.imageTool]]: PNG images and the result to write as structured content.
 *
 * @param pngs the images, each the bytes of a PNG file
 */
final case class ImageResult[O](pngs: Seq[Array[Byte]], result: O)
