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

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, CallToolResult, TextContent}
import org.calinburloiu.music.microtonalist.ai.mcp.{ImageResult, PlayJsonMcp}
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.{DriverRequest, GuiPlatform, MouseButtonName}
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardOpenOption.{APPEND, CREATE}
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.jdk.CollectionConverters.*

/**
 * The tools of the QA MCP server, each calling the [[QaService]].
 *
 * Each call appends a line to the run's `tools.log`: the time, the tool, its arguments, and `ok` with its result, cut
 * to [[MaxLoggedResultLength]] characters and without images, or `error` with the message.
 */
object QaTools {
  /** The most characters of a result that `tools.log` holds. */
  val MaxLoggedResultLength: Int = 200

  private val ToolsLogFileName: String = "tools.log"
  private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
  /** The largest scale of a snapshot, as the driver allows. */
  private val MaxScale: Double = 4

  /** Every tool, on the given service. */
  def all(service: QaService): Seq[SyncToolSpecification] = {
    val tools = Seq(getRun(service), startApp(service), stopApp(service), sendMidi(service), unplug(service),
      replug(service), listWindows(service), dump(service), snapshot(service), click(service), pressKey(service),
      typeText(service), waitIdle(service), readClipboard(service), writeClipboard(service), osScreenshot(service))
    tools.map(logged(service.run, _))
  }

  // # Tools

  private def getRun(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[Unit, JsObject]("get_run",
      "Tells the run's ID and its directory. Every path a tool takes is inside the run directory, where the app's " +
        "log, its MIDI files and the snapshots are also written; copy the fixtures or write a composition there.",
      NoArgumentsSchema) { _ =>
      Json.obj("runId" -> service.run.id, "runDir" -> service.run.dir.toString)
    }
  }

  private def startApp(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[StartAppArgs, StartAppResult]("start_app",
      "Starts the app on a composition of the run directory, and answers once it shows a window. The composition's " +
        "rig, its `.tracks` file, may name only QA devices, `qa:<device>`, each of which becomes a virtual MIDI port " +
        "of the run; the rig is rewritten in place. Only one app runs at a time; its output goes to `app.log`.",
      objectSchema(
        "composition" -> Json.obj("type" -> "string",
          "description" -> "The composition's path, relative to the run directory or absolute inside it"),
        "platform" -> Json.obj("type" -> "string", "enum" -> GuiPlatformNames, "default" -> "headless",
          "description" -> "`headless` shows no window and takes snapshots; `desktop` shows the windows"),
        "unplugged" -> Json.obj("type" -> "array", "items" -> Json.obj("type" -> "string"), "default" -> Json.arr(),
          "description" -> "QA devices to leave unplugged at startup, which replug plugs in later"),
      )(required = "composition")) {
      args => service.startApp(args)
    }
  }

  private def stopApp(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[Unit, StopResult]("stop_app",
      "Stops the app with SIGTERM, waiting for its shutdown, and kills it if it doesn't exit; then stops its MIDI " +
        "ports. Tells the app's exit code and whether it was killed.",
      NoArgumentsSchema) { _ =>
      service.stopApp()
    }
  }

  private def sendMidi(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[SendMidiArgs, SendMidiResult]("send_midi",
      "Sends SendMIDI command lines, e.g. `on 60 100` or `cc 64 127`, to an input device of the running app's rig, " +
        "and logs each with the time sent in `midi-in-<device>.txt`. Only SendMIDI's commands that send MIDI or wait " +
        "are allowed, with numbers or note names as parameters: no `dev`, `virt`, `file`, `syf`, `mpp`, `hex` or " +
        "`dec`. What the app sends to an output device is recorded in `midi-out-<device>.txt`.",
      objectSchema(
        "device" ->
          Json.obj("type" -> "string", "description" -> "The QA device, `<device>` in the rig's `qa:<device>`"),
        "lines" -> Json.obj("type" -> "array", "items" -> Json.obj("type" -> "string"), "minItems" -> 1,
          "description" -> "SendMIDI command lines, one command with its parameters per line"),
      )(required = "device", "lines")) { args =>
      val file = service.sendMidi(args.device, args.lines)
      SendMidiResult(args.lines.size, file.toString)
    }
  }

  private def unplug(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[DeviceArgs, PlugResult]("unplug",
      "Unplugs a QA device of the running app's rig, like pulling its cable: its virtual MIDI port disappears.",
      deviceSchema) { args =>
      service.unplug(args.device)
      PlugResult(args.device, plugged = false)
    }
  }

  private def replug(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[DeviceArgs, PlugResult]("replug",
      "Plugs in an unplugged QA device of the running app's rig, like plugging its cable in: its virtual MIDI port " +
        "appears again, and its output keeps going to the same file.",
      deviceSchema) { args =>
      service.replug(args.device)
      PlugResult(args.device, plugged = true)
    }
  }

  private def listWindows(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[Unit, JsObject]("list_windows",
      "Lists the app's windows, popups and dialogs included, each with the index that the other tools take as " +
        "`window`, its type, title, bounds, focus and owner.",
      NoArgumentsSchema) { _ =>
      service.driver(DriverRequest.Windows)
    }
  }

  private def dump(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[WindowArgs, JsObject]("dump",
      "Dumps the visible scene graph of every window, or of one: one line per control, node with an ID or canvas, " +
        "indented by its depth. Use it to find the selectors of the nodes to click.",
      objectSchema(WindowProperty)()) { args =>
      service.driver(DriverRequest.Dump(args.window))
    }
  }

  private def snapshot(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.imageTool[SnapshotArgs, SnapshotInfo]("snapshot",
      "Takes a PNG of a node, of a window, or else of the first window, returns it inline, and saves it as " +
        "`snapshots/<n>.png` in the run directory. Works on both platforms.",
      objectSchema(
        WindowProperty,
        SelectorProperty,
        "scale" -> Json.obj("type" -> "number", "exclusiveMinimum" -> 0, "maximum" -> MaxScale, "default" -> 1,
          "description" -> "The image's scale, in (0, 4]"),
      )()) { args =>
      val result = service.snapshot(args.window, args.selector, args.scale)
      ImageResult(Seq(result.png), SnapshotInfo(result.file.toString, result.width, result.height))
    }
  }

  private def click(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[DriverRequest.Click, JsObject]("click",
      "Clicks the single visible node that a selector names, as a mouse does, and tells which of its properties " +
        "matched, the node, and whether it is disabled. Fails if no node or several nodes match.",
      objectSchema(
        SelectorProperty,
        WindowProperty,
        "button" -> Json.obj("type" -> "string", "enum" -> MouseButtonNames, "default" -> "primary"),
        "clickCount" -> Json.obj("type" -> "integer", "enum" -> Json.arr(1, 2), "default" -> 1,
          "description" -> "1 for a click, 2 for a double-click"),
      )(required = "selector")) { request =>
      service.driver(request)
    }
  }

  private def pressKey(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[DriverRequest.Key, JsObject]("press_key",
      "Presses a key, or a combination, in the focused window, or else in the dialog on top.",
      objectSchema(
        "key" -> Json.obj("type" -> "string",
          "description" ->
            ("A JavaFX KeyCode name, or modifiers and a key " +
              "joined by `+`, e.g. `ENTER`, `DIGIT1` or `Shortcut+N`; `Shortcut` is Command on macOS")),
      )(required = "key")) { request =>
      service.driver(request)
    }
  }

  private def typeText(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[DriverRequest.Type, JsObject]("type_text",
      "Types text into the focused control; click the control first to focus it.",
      objectSchema("text" -> Json.obj("type" -> "string"))(required = "text")) { request =>
      service.driver(request)
    }
  }

  private def waitIdle(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[Unit, JsObject]("wait_idle",
      "Waits until the app's JavaFX Application Thread has handled every pending event, e.g. after a click, before " +
        "the next dump or snapshot.",
      NoArgumentsSchema) { _ =>
      service.driver(DriverRequest.Idle)
    }
  }

  private def readClipboard(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[Unit, JsObject]("read_clipboard",
      "Reads the text of the app's clipboard, which is null if it holds no text.",
      NoArgumentsSchema) { _ =>
      service.driver(DriverRequest.ReadClipboard)
    }
  }

  private def writeClipboard(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.tool[DriverRequest.WriteClipboard, JsObject]("write_clipboard",
      "Writes text to the app's clipboard, e.g. before pasting it with press_key.",
      objectSchema("text" -> Json.obj("type" -> "string"))(required = "text")) { request =>
      service.driver(request)
    }
  }

  private def osScreenshot(service: QaService): SyncToolSpecification = {
    PlayJsonMcp.imageTool[Unit, OsScreenshotInfo]("os_screenshot",
      "Takes a PNG of each on-screen window of the app with macOS's screencapture, as the OS draws it, returns them " +
        "inline, and saves them as `screenshots/os-<n>-<window ID>.png` in the run directory. Desktop platform only; " +
        "it needs the Screen Recording permission.",
      NoArgumentsSchema) { _ =>
      val screenshots = service.osScreenshot()
      ImageResult(screenshots.map(_._2), OsScreenshotInfo(screenshots.map(_._1.toString)))
    }
  }

  // # The log

  /** Wraps a tool so that each call is logged in the run's `tools.log`. */
  private def logged(run: QaRun, spec: SyncToolSpecification): SyncToolSpecification = {
    SyncToolSpecification.builder()
      .tool(spec.tool())
      .callHandler { (exchange, request) =>
        val result = spec.callHandler().apply(exchange, request)
        log(run, request, result)
        result
      }
      .build()
  }

  private def log(run: QaRun, request: CallToolRequest, result: CallToolResult): Unit = {
    val arguments = Option(request.arguments()).fold(Json.obj()) { arguments =>
      PlayJsonMcp.objectMapper.convertValue(arguments, classOf[JsValue])
    }
    val outcome =
      if (result.isError) {
        val message = result.content().asScala.collect { case text: TextContent => text.text() }.mkString(" ")
        s"error ${message.replace('\n', ' ')}"
      } else {
        val structured = PlayJsonMcp.objectMapper.convertValue(result.structuredContent(), classOf[JsValue])
        s"ok ${Json.stringify(structured).take(MaxLoggedResultLength)}"
      }
    val line = s"${TimeFormat.format(LocalTime.now())} ${request.name()} ${Json.stringify(arguments)} $outcome\n"

    run.synchronized {
      Files.writeString(run.file(ToolsLogFileName), line, UTF_8, CREATE, APPEND)
    }
  }

  // # Arguments and results

  private final case class SendMidiArgs(device: String, lines: Seq[String])

  private final case class DeviceArgs(device: String)

  private final case class WindowArgs(window: Option[Int])

  private final case class SnapshotArgs(window: Option[Int], selector: Option[String], scale: Double)

  private final case class SendMidiResult(sent: Int, file: String)

  private final case class PlugResult(device: String, plugged: Boolean)

  private final case class SnapshotInfo(file: String, width: Int, height: Int)

  private final case class OsScreenshotInfo(files: Seq[String])

  /** Reads the arguments of a tool that takes none, ignoring any. */
  private given Reads[Unit] = Reads.pure(())

  private given OWrites[JsObject] = OWrites(identity)

  private given Format[GuiPlatform] = lowerCaseEnumFormat(GuiPlatform.values)

  private given Format[MouseButtonName] = lowerCaseEnumFormat(MouseButtonName.values)

  private given Reads[StartAppArgs] = (
    (__ \ "composition").read[String] and
      (__ \ "platform").readWithDefault[GuiPlatform](GuiPlatform.Headless) and
      (__ \ "unplugged").readWithDefault[Seq[String]](Nil)
  )(StartAppArgs.apply)

  private given Reads[SendMidiArgs] = (
    (__ \ "device").read[String] and
      (__ \ "lines").read[Seq[String]](using Reads.minLength[Seq[String]](1))
  )(SendMidiArgs.apply)

  private given Reads[DeviceArgs] = (__ \ "device").read[String].map(DeviceArgs.apply)

  private val windowReads: Reads[Option[Int]] = (__ \ "window").readNullable[Int]

  private given Reads[WindowArgs] = windowReads.map(WindowArgs.apply)

  private given Reads[SnapshotArgs] = (
    windowReads and
      (__ \ "selector").readNullable[String] and
      (__ \ "scale").readWithDefault[Double](1).filter(JsonValidationError(s"the scale must be in (0, $MaxScale]")) {
        scale => scale > 0 && scale <= MaxScale
      }
  )(SnapshotArgs.apply)

  private given Reads[DriverRequest.Click] = (
    (__ \ "selector").read[String] and
      windowReads and
      (__ \ "button").readWithDefault[MouseButtonName](MouseButtonName.Primary) and
      (__ \ "clickCount").readWithDefault[Int](1).filter(JsonValidationError("the click count must be 1 or 2")) {
        count => count == 1 || count == 2
      }
  ) { (selector, window, button, clickCount) =>
    new DriverRequest.Click(selector, window, button, clickCount)
  }

  private given Reads[DriverRequest.Key] = (__ \ "key").read[String].map(new DriverRequest.Key(_))

  private given Reads[DriverRequest.Type] = (__ \ "text").read[String].map(new DriverRequest.Type(_))

  private given Reads[DriverRequest.WriteClipboard] = {
    (__ \ "text").read[String].map(new DriverRequest.WriteClipboard(_))
  }

  private given OWrites[DeviceInfo] = Json.writes[DeviceInfo]

  private given OWrites[StartAppResult] = Json.writes[StartAppResult]

  private given OWrites[StopResult] = Json.writes[StopResult]

  private given OWrites[SendMidiResult] = Json.writes[SendMidiResult]

  private given OWrites[PlugResult] = Json.writes[PlugResult]

  private given OWrites[SnapshotInfo] = Json.writes[SnapshotInfo]

  private given OWrites[OsScreenshotInfo] = Json.writes[OsScreenshotInfo]

  // # Schemas

  private def lowerCaseName(value: Any): String = value.toString.toLowerCase(Locale.ROOT)

  private val GuiPlatformNames: Seq[String] = GuiPlatform.values.toSeq.map(lowerCaseName)

  private val MouseButtonNames: Seq[String] = MouseButtonName.values.toSeq.map(lowerCaseName)

  private val WindowProperty: (String, JsObject) = "window" -> Json.obj("type" -> "integer", "minimum" -> 0,
    "description" -> "A window's index, as list_windows tells; every window by default")

  private val SelectorProperty: (String, JsObject) = "selector" -> Json.obj("type" -> "string",
    "description" ->
      ("What names a node: its accessible text, else its ID, else its visible text, as dump shows " +
        "them"))

  private val NoArgumentsSchema: JsObject = objectSchema()()

  private val deviceSchema: JsObject = objectSchema(
    "device" -> Json.obj("type" -> "string", "description" -> "The QA device, `<device>` in the rig's `qa:<device>`"),
  )(required = "device")

  /** The JSON Schema of an object with the given properties, which allows no other property. */
  private def objectSchema(properties: (String, JsObject)*)(required: String*): JsObject = Json.obj(
    "type" -> "object",
    "properties" -> JsObject(properties),
    "required" -> required,
    "additionalProperties" -> false,
  )

  /** Reads and writes an enum's values by their lower-case names, e.g. `headless`. */
  private def lowerCaseEnumFormat[E](values: Array[E]): Format[E] = {
    val names = values.toSeq.map(value => lowerCaseName(value) -> value)
    val reads = Reads.StringReads.flatMap { name =>
      names.collectFirst { case (`name`, value) => Reads.pure(value) }
        .getOrElse(Reads.failed[E](s"expected one of ${names.map(_._1).mkString(", ")}"))
    }
    Format(reads, Writes(value => JsString(lowerCaseName(value))))
  }
}
