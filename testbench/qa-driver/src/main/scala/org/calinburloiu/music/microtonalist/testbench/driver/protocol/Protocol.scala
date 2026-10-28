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

package org.calinburloiu.music.microtonalist.testbench.driver.protocol

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.*
import play.api.libs.json.*

import scala.util.Try

/** A mouse button that a click presses. */
enum MouseButtonName {
  /** The primary button, which selects and activates. */
  case Primary

  /** The secondary button, which opens a context menu. */
  case Secondary
}

/** A request that the QA server sends to the driver. */
enum DriverRequest {
  /** Lists the windows; answers `{"windows":[…]}`. */
  case Windows

  /** Dumps the scene graph of every window, or of one; answers `{"windows":[…]}`. */
  case Dump(window: Option[Int])

  /** Takes a PNG of a window or of a node; answers `{"png":<base64>,"width":…,"height":…}`. */
  case Snapshot(window: Option[Int], selector: Option[String], scale: Double)

  /** Clicks the node that a selector matches; answers `{"matchedBy":…,"node":…,"disabled":…}`. */
  case Click(selector: String, window: Option[Int], button: MouseButtonName, clickCount: Int)

  /** Presses a key or a combination, e.g. `Shortcut+N`; answers `{}`. */
  case Key(key: String)

  /** Types text into the focused control; answers `{}`. */
  case Type(text: String)

  /** Waits until the JavaFX Application Thread has drained; answers `{}`. */
  case Idle

  /** Reads the clipboard's text; answers `{"text":<string or null>}`. */
  case ReadClipboard

  /** Writes text to the clipboard; answers `{}`. */
  case WriteClipboard(text: String)
}

/** A request with the ID that its response carries. */
final case class Envelope(id: Long, request: DriverRequest)

/** The driver's answer to the request with the same ID. */
enum DriverResponse {
  /** The request succeeded with the given result. */
  case Ok(id: Long, result: JsObject)

  /** The request failed with the given message. */
  case Failed(id: Long, message: String)
}

/** The first message the driver writes once connected: the app's process ID and the JavaFX platform it runs on. */
final case class Hello(pid: Long, platform: GuiPlatform)

/**
 * The messages between the QA server and the driver: one JSON object per line, with no newline inside.
 *
 * Once connected, the driver writes a [[Hello]], then answers each [[Envelope]] with a [[DriverResponse]] that carries
 * its ID.
 */
object Protocol {
  /** The ID of the response to a line that has no readable ID. */
  val UnreadableId: Long = -1

  /** Encodes a request with its ID, e.g. `{"id":7,"op":"click","selector":"Play",…}`. */
  def encode(envelope: Envelope): String = {
    Json.stringify(Json.obj("id" -> envelope.id) ++ writeRequest(envelope.request))
  }

  /**
   * Decodes a request with its ID.
   *
   * @return the failure to answer with, carrying the line's ID, or [[UnreadableId]] if it has none, when the line is
   *   not a known, well-formed request
   */
  def decodeEnvelope(line: String): Either[DriverResponse.Failed, Envelope] = {
    val json = parse(line)
    val id = json.toOption.flatMap { value => (value \ "id").asOpt[Long] }.getOrElse(UnreadableId)
    val envelope = for {
      value <- json
      _ <- read[Long](value, "id")
      request <- readRequest(value)
    } yield Envelope(id, request)

    envelope.left.map { error => DriverResponse.Failed(id, s"unreadable request: $error") }
  }

  /** Encodes a response, `{"id":…,"result":{…}}` or `{"id":…,"error":"…"}`. */
  def encode(response: DriverResponse): String = Json.stringify(response match {
    case DriverResponse.Ok(id, result) => Json.obj("id" -> id, "result" -> result)
    case DriverResponse.Failed(id, message) => Json.obj("id" -> id, "error" -> message)
  })

  /** Decodes a response, or tells why it can't. */
  def decodeResponse(line: String): Either[String, DriverResponse] = for {
    json <- parse(line)
    id <- read[Long](json, "id")
    response <- (json \ "result").asOpt[JsObject].map { result => Right(DriverResponse.Ok(id, result)) }
      .getOrElse(read[String](json, "error").map { message => DriverResponse.Failed(id, message) })
  } yield response

  /** Encodes the hello, `{"pid":…,"platform":"Headless"|"Desktop"}`. */
  def encode(hello: Hello): String = {
    Json.stringify(Json.obj("pid" -> hello.pid, "platform" -> hello.platform.toString))
  }

  /** Decodes the hello, or tells why it can't. */
  def decodeHello(line: String): Either[String, Hello] = for {
    json <- parse(line)
    pid <- read[Long](json, "pid")
    platform <- readEnum(json, "platform", GuiPlatform.values)
  } yield Hello(pid, platform)

  private def writeRequest(request: DriverRequest): JsObject = request match {
    case Windows => Json.obj("op" -> "windows")
    case Dump(window) => Json.obj("op" -> "dump", "window" -> window)
    case Snapshot(window, selector, scale) =>
      Json.obj("op" -> "snapshot", "window" -> window, "selector" -> selector, "scale" -> scale)
    case Click(selector, window, button, clickCount) =>
      Json.obj("op" -> "click", "selector" -> selector, "window" -> window, "button" -> button.toString,
        "clickCount" -> clickCount)
    case Key(key) => Json.obj("op" -> "key", "key" -> key)
    case Type(text) => Json.obj("op" -> "type", "text" -> text)
    case Idle => Json.obj("op" -> "idle")
    case ReadClipboard => Json.obj("op" -> "readClipboard")
    case WriteClipboard(text) => Json.obj("op" -> "writeClipboard", "text" -> text)
  }

  private def readRequest(json: JsValue): Either[String, DriverRequest] = {
    def window = readOpt[Int](json, "window")

    read[String](json, "op").flatMap {
      case "windows" => Right(Windows)
      case "dump" => window.map(Dump(_))
      case "snapshot" => for {
          w <- window
          selector <- readOpt[String](json, "selector")
          scale <- read[Double](json, "scale")
        } yield Snapshot(w, selector, scale)
      case "click" => for {
          selector <- read[String](json, "selector")
          w <- window
          button <- readEnum(json, "button", MouseButtonName.values)
          clickCount <- read[Int](json, "clickCount")
        } yield Click(selector, w, button, clickCount)
      case "key" => read[String](json, "key").map(Key(_))
      case "type" => read[String](json, "text").map(Type(_))
      case "idle" => Right(Idle)
      case "readClipboard" => Right(ReadClipboard)
      case "writeClipboard" => read[String](json, "text").map(WriteClipboard(_))
      case other => Left(s"unknown op $other")
    }
  }

  private def parse(line: String): Either[String, JsValue] = Try(Json.parse(line)).toEither.left.map(_ => "not JSON")

  private def read[T: Reads](json: JsValue, field: String): Either[String, T] = {
    describe((json \ field).validate[T], field)
  }

  private def readOpt[T: Reads](json: JsValue, field: String): Either[String, Option[T]] = {
    describe((json \ field).validateOpt[T], field)
  }

  private def readEnum[E](json: JsValue, field: String, values: Array[E]): Either[String, E] = {
    read[String](json, field).flatMap { name =>
      values.find(_.toString == name).toRight(s"$field: unknown value $name")
    }
  }

  private def describe[T](result: JsResult[T], field: String): Either[String, T] = result.asEither.left.map { errors =>
    errors.flatMap { (_, fieldErrors) => fieldErrors.flatMap(_.messages) }.mkString(s"$field: ", ", ", "")
  }
}
