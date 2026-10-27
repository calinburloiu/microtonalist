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
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, CallToolResult, ImageContent, TextContent}
import org.calinburloiu.music.microtonalist.ai.mcp.PlayJsonMcp
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.*
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.MouseButtonName.{Primary, Secondary}
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.{DriverRequest, GuiPlatform}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsObject, JsValue, Json}

import java.nio.file.Files
import java.util.Base64
import scala.jdk.CollectionConverters.*

class QaToolsTest extends AnyWordSpec with Matchers with Inside {

  private abstract class Fixture {
    val service: FakeQaService = FakeQaService()
    val tools: Seq[SyncToolSpecification] = QaTools.all(service)

    /** Calls a tool's handler as the SDK does, with a request built by the SDK. */
    def call(name: String, arguments: (String, Json.JsValueWrapper)*): CallToolResult = {
      val spec = tools.find(_.tool().name() == name).getOrElse(fail(s"no tool $name"))
      val javaArguments = PlayJsonMcp.objectMapper.convertValue(Json.obj(arguments*),
        classOf[java.util.Map[String, Object]])
      spec.callHandler().apply(null, CallToolRequest.builder(name).arguments(javaArguments).build())
    }

    /** The structured content of a successful result. */
    def resultOf(result: CallToolResult): JsValue = {
      withClue(textOf(result)) {
        result.isError shouldBe false
      }
      PlayJsonMcp.objectMapper.convertValue(result.structuredContent(), classOf[JsValue])
    }

    /** The message of an error result. */
    def errorOf(result: CallToolResult): String = {
      result.isError shouldBe true
      textOf(result)
    }

    /** The lines of the run's `tools.log`. */
    def toolsLog: Seq[String] = Files.readAllLines(service.run.file("tools.log")).asScala.toSeq
  }

  /** The text of the result's last content. */
  private def textOf(result: CallToolResult): String = inside(result.content().asScala.last) {
    case content: TextContent => content.text()
  }

  "QaTools" should {
    "offer the tools of the design" in new Fixture {
      // Then
      tools.map(_.tool().name()) should contain theSameElementsAs Seq("get_run", "start_app", "stop_app",
        "send_midi", "unplug", "replug", "list_windows", "dump", "snapshot", "click", "press_key", "type_text",
        "wait_idle", "read_clipboard", "write_clipboard")
      all(tools.map(_.tool().description())) should not be empty
    }

    "tell the run" in new Fixture {
      // When
      val result: JsValue = resultOf(call("get_run"))

      // Then
      result shouldBe Json.obj("runId" -> service.run.id, "runDir" -> service.run.dir.toString)
    }
  }

  "QaTools' start_app" should {
    "start the app headless, with every device plugged in, by default" in new Fixture {
      // When
      val result: JsValue = resultOf(call("start_app", "composition" -> "qa.mtlist"))

      // Then
      service.calls shouldBe Seq("startApp" -> StartAppArgs("qa.mtlist", GuiPlatform.Headless, Nil))
      (result \ "pid").as[Long] shouldBe FakeQaService.Pid
      (result \ "platform").as[String] shouldBe "headless"
      (result \ "devices" \ 0 \ "device").as[String] shouldBe "keys"
      (result \ "appLog").as[String] shouldBe service.run.file("app.log").toString
    }

    "start the app on the desktop platform with devices unplugged" in new Fixture {
      // When
      val result: JsValue = resultOf(call("start_app", "composition" -> "qa.mtlist", "platform" -> "desktop",
        "unplugged" -> Seq("synth")))

      // Then
      service.calls shouldBe Seq("startApp" -> StartAppArgs("qa.mtlist", GuiPlatform.Desktop, Seq("synth")))
      (result \ "platform").as[String] shouldBe "desktop"
    }

    "refuse an unknown platform" in new Fixture {
      // When
      val message: String = errorOf(call("start_app", "composition" -> "qa.mtlist", "platform" -> "wayland"))

      // Then
      message should include("headless, desktop")
      service.calls shouldBe empty
    }
  }

  "QaTools' app and MIDI tools" should {
    "stop the app" in new Fixture {
      // When
      val result: JsValue = resultOf(call("stop_app"))

      // Then
      result shouldBe Json.obj("exitCode" -> 0, "killed" -> false)
      service.calls shouldBe Seq("stopApp" -> ())
    }

    "send MIDI to a device" in new Fixture {
      // When
      val result: JsValue = resultOf(call("send_midi", "device" -> "keys", "lines" -> Seq("on 60 100", "off 60 0")))

      // Then
      result shouldBe Json.obj("sent" -> 2, "file" -> service.run.file("midi-in-keys.txt").toString)
      service.calls shouldBe Seq("sendMidi" -> ("keys", Seq("on 60 100", "off 60 0")))
    }

    "refuse to send no MIDI lines" in new Fixture {
      // When
      errorOf(call("send_midi", "device" -> "keys", "lines" -> Seq.empty[String]))

      // Then
      service.calls shouldBe empty
    }

    "turn a QaException into an error result" in new Fixture {
      // When
      val message: String = errorOf(call("send_midi", "device" -> "keys", "lines" -> Seq("dev IAC")))

      // Then
      Left(message) shouldBe SendMidiLines.validate(Seq("dev IAC"))
    }

    "unplug and replug a device" in new Fixture {
      // When
      val unplugged: JsValue = resultOf(call("unplug", "device" -> "synth"))
      val replugged: JsValue = resultOf(call("replug", "device" -> "synth"))

      // Then
      unplugged shouldBe Json.obj("device" -> "synth", "plugged" -> false)
      replugged shouldBe Json.obj("device" -> "synth", "plugged" -> true)
      service.calls shouldBe Seq("unplug" -> "synth", "replug" -> "synth")
    }
  }

  "QaTools' GUI tools" should {
    "pass the requests to the driver and return its results" in new Fixture {
      // Given
      service.driverResult = request => Json.obj("request" -> request.toString)

      // When
      val results: Seq[JsValue] = Seq(
        call("list_windows"),
        call("dump"),
        call("dump", "window" -> 1),
        call("press_key", "key" -> "Shortcut+N"),
        call("type_text", "text" -> "Rast"),
        call("wait_idle"),
        call("read_clipboard"),
        call("write_clipboard", "text" -> "Saba"),
      ).map(resultOf)

      // Then
      val expectedRequests: Seq[DriverRequest] = Seq(Windows, Dump(None), Dump(Some(1)), Key("Shortcut+N"),
        Type("Rast"), Idle, ReadClipboard, WriteClipboard("Saba"))
      service.requests shouldBe expectedRequests
      results shouldBe expectedRequests.map(request => Json.obj("request" -> request.toString))
    }

    "fill in click's defaults" in new Fixture {
      // When
      call("click", "selector" -> "Play")

      // Then
      service.requests shouldBe Seq(Click("Play", None, Primary, 1))
    }

    "double-click a node of a window with the secondary button" in new Fixture {
      // When
      call("click", "selector" -> "Play", "window" -> 1, "button" -> "secondary", "clickCount" -> 2)

      // Then
      service.requests shouldBe Seq(Click("Play", Some(1), Secondary, 2))
    }

    "refuse a click count other than 1 or 2" in new Fixture {
      // When
      errorOf(call("click", "selector" -> "Play", "clickCount" -> 3))

      // Then
      service.requests shouldBe empty
    }

    "return a snapshot inline" in new Fixture {
      // When
      val result: CallToolResult = call("snapshot")

      // Then
      inside(result.content().get(0)) {
        case image: ImageContent =>
          image.data() shouldBe Base64.getEncoder.encodeToString(service.png)
          image.mimeType() shouldBe "image/png"
      }
      resultOf(result) shouldBe Json.obj("file" -> service.run.file("snapshots").resolve("1.png").toString,
        "width" -> 4, "height" -> 3)
      service.requests shouldBe Seq(Snapshot(None, None, 1.0))
    }

    "take a snapshot of a node at a scale" in new Fixture {
      // When
      resultOf(call("snapshot", "window" -> 1, "selector" -> "Play", "scale" -> 2))

      // Then
      service.requests shouldBe Seq(Snapshot(Some(1), Some("Play"), 2.0))
    }

    "refuse a scale out of (0, 4]" in new Fixture {
      // When
      errorOf(call("snapshot", "scale" -> 0))
      errorOf(call("snapshot", "scale" -> 4.5))

      // Then
      service.requests shouldBe empty
    }
  }

  "QaTools' log" should {
    "log each call in tools.log" in new Fixture {
      // When
      call("get_run")
      call("send_midi", "device" -> "keys", "lines" -> Seq("dev IAC"))

      // Then
      toolsLog should have size 2
      toolsLog.head should fullyMatch regex
        """\d\d:\d\d:\d\d\.\d{3} get_run \{} ok \{"runId":"qa-[0-9a-f]{4}","runDir":".*"}"""
      toolsLog(1) should fullyMatch regex
        """\d\d:\d\d:\d\d\.\d{3} send_midi \{"device":"keys","lines":\["dev IAC"]} error .*"""
      toolsLog(1) should endWith(SendMidiLines.validate(Seq("dev IAC")).left.toOption.get)
    }

    "cut a result to 200 characters, and leave images out" in new Fixture {
      // Given
      service.driverResult = _ => Json.obj("text" -> "x" * 300)

      // When
      call("read_clipboard")
      call("snapshot")

      // Then
      val loggedResults: Seq[String] = toolsLog.map(_.split(" ok ", 2)(1))
      loggedResults.head should have length 200
      loggedResults.head should startWith("""{"text":"xxx""")
      loggedResults(1) should not include Base64.getEncoder.encodeToString(service.png)
    }

    "keep each call on one line" in new Fixture {
      // Given
      service.driverResult = _ => throw QaException("several nodes match:\n  Button\n  Label")

      // When
      call("click", "selector" -> "Play")

      // Then
      toolsLog should have size 1
      toolsLog.head should endWith("error several nodes match:   Button   Label")
    }
  }
}
