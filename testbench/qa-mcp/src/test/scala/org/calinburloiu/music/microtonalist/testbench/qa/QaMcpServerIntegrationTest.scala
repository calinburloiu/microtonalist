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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.{DriverRequest, DriverResponse, Protocol}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.io.{ByteArrayOutputStream, IOException, InputStream, OutputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/** Drives the QA server over stdio, writing JSON-RPC lines to a pipe and reading its replies, as `mcp.md` says. */
class QaMcpServerIntegrationTest extends AnyWordSpec with Matchers {

  import QaMcpServerIntegrationTest.*

  /** A QA session on a run with the design's fixture rig, whose app's process connects a [[FakeDriver]]. */
  private class Fixture {
    val launcher: FakeProcessLauncher = FakeProcessLauncher()
    val run: QaRun = QaRun.create(Files.createTempDirectory("qa"))
    val java: Path = Path.of("/jdk/bin/java")
    val session: QaSession = QaSession(run, launcher, QaJars(Path.of("/jars/app.jar"), Path.of("/jars/driver.jar")),
      java)
    val drivers: ArrayBuffer[FakeDriver] = ArrayBuffer()

    Files.writeString(run.file("qa.mtlist"), """{ "tunings": [] }""")
    Files.writeString(run.file("qa.mtlist.tracks"), FixtureRig)

    launcher.newProcess = (spec, pid) => {
      if (spec.command.head == java.toString) {
        val driver = FakeDriver(run.socket, pid)
        driver.script = envelope => Protocol.encode(DriverResponse.Ok(envelope.id, DumpResult))
        drivers += driver
      }
      FakeProcess(spec, pid)
    }

    private val clientOutput = PipedOutputStream()
    private val serverInput = PipedInputStream(clientOutput, PipeSize)
    private val serverOutput = LineQueue()
    private var nextId: Int = 1

    /** Completes once the server's input ends. */
    val done: CompletableFuture[Unit] = QaMcpServer.start(session, serverInput, serverOutput, "test")

    /** Sends a request and returns its response, skipping the notifications before it. */
    def request(method: String, params: JsObject = Json.obj()): JsValue = {
      val id = nextId
      nextId += 1
      send(Json.obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params))
      responseTo(id)
    }

    def initialize(): JsValue = {
      val response = request("initialize",
        Json.obj("protocolVersion" -> "2025-06-18", "capabilities" -> Json.obj(),
          "clientInfo" -> Json.obj("name" -> "QaMcpServerIntegrationTest", "version" -> "1")))
      send(Json.obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized"))
      response
    }

    def listTools(): JsValue = request("tools/list")

    def callTool(name: String, arguments: JsObject = Json.obj()): JsValue = {
      request("tools/call", Json.obj("name" -> name, "arguments" -> arguments))
    }

    /** Ends the server's input, as the agent does when it finishes. */
    def closeInput(): Unit = clientOutput.close()

    def close(): Unit = {
      closeInput()
      session.shutdown()
    }

    private def send(message: JsObject): Unit = {
      clientOutput.write((Json.stringify(message) + "\n").getBytes(UTF_8))
      clientOutput.flush()
    }

    @tailrec
    private def responseTo(id: Int): JsValue = {
      val line = Option(serverOutput.lines.poll(AwaitSeconds, TimeUnit.SECONDS))
        .getOrElse(fail(s"no response to request $id in $AwaitSeconds s"))
      val message = Json.parse(line)
      if ((message \ "id").asOpt[Int].contains(id)) message else responseTo(id)
    }
  }

  private def withServer(test: Fixture => Unit): Unit = {
    val fixture = Fixture()
    try {
      test(fixture)
    } finally {
      fixture.close()
    }
  }

  "the QA server over stdio" should {
    "initialize as microtonalist-qa" in withServer { server =>
      // When
      val response: JsValue = server.initialize()

      // Then
      (response \ "result" \ "serverInfo" \ "name").as[String] shouldBe "microtonalist-qa"
      (response \ "result" \ "serverInfo" \ "version").as[String] shouldBe "test"
      (response \ "result" \ "capabilities" \ "tools").toOption shouldBe defined
    }

    "list its tools" in withServer { server =>
      // Given
      server.initialize()

      // When
      val response: JsValue = server.listTools()

      // Then
      (response \ "result" \ "tools").as[JsArray].value.size shouldBe 16
    }

    "start the app and dump its scene through the driver" in withServer { server =>
      // Given
      server.initialize()

      // When
      val started: JsValue = server.callTool("start_app", Json.obj("composition" -> "qa.mtlist"))
      val dumped: JsValue = server.callTool("dump")

      // Then
      (started \ "result" \ "isError").as[Boolean] shouldBe false
      (started \ "result" \ "structuredContent" \ "pid").as[Long] shouldBe server.launcher.processes.last.pid
      (dumped \ "result" \ "structuredContent").as[JsObject] shouldBe DumpResult
      server.drivers.last.requests shouldBe Seq(DriverRequest.Dump(None))
    }

    "answer a refused call with a tool error" in withServer { server =>
      // Given
      server.initialize()

      // When
      val response: JsValue = server.callTool("send_midi", Json.obj("device" -> "keys", "lines" -> Json.arr("on")))

      // Then
      (response \ "result" \ "isError").as[Boolean] shouldBe true
      (response \ "result" \ "content" \ 0 \ "text").as[String] should include("no app running")
    }

    "refuse a call whose arguments its input schema doesn't allow, before the tool runs" in withServer { server =>
      // Given
      server.initialize()
      server.callTool("start_app", Json.obj("composition" -> "qa.mtlist"))

      // When
      // The tool's `Reads` would ignore the misspelt `click_count`; only the SDK's validation of the schema's
      // `additionalProperties: false` refuses it.
      val response: JsValue = server.callTool("click", Json.obj("selector" -> "Play", "click_count" -> 2))

      // Then
      (response \ "result" \ "isError").as[Boolean] shouldBe true
      (response \ "result" \ "content" \ 0 \ "text").as[String] should include("click_count")
      server.drivers.last.requests shouldBe empty
    }

    "complete when its input ends" in withServer { server =>
      // Given
      server.initialize()

      // When
      server.closeInput()

      // Then
      server.done.get(AwaitSeconds, TimeUnit.SECONDS) shouldBe ()
    }

    "complete when its input fails" in {
      // Given
      val failingInput: InputStream = new InputStream {
        override def read(): Int = throw IOException("broken pipe")
      }

      // When
      val done: CompletableFuture[Unit] = QaMcpServer.start(FakeQaService(), failingInput, ByteArrayOutputStream(),
        "test")

      // Then
      done.get(AwaitSeconds, TimeUnit.SECONDS) shouldBe ()
    }
  }
}

private object QaMcpServerIntegrationTest {
  private val AwaitSeconds: Long = 5
  private val PipeSize: Int = 1 << 16

  /** The design's fixture rig, which the `qa` skill's agent copies. */
  private val FixtureRig: String =
    """{
      |  "tracks": [
      |    {
      |      "id": "qa",
      |      "name": "QA",
      |      "input": { "midiDeviceId": { "name": "qa:keys" } },
      |      "tuningChangers": ["pedal"],
      |      "tuner": "mtsOctave1ByteNonRealTime",
      |      "output": { "midiDeviceId": { "name": "qa:synth" } }
      |    }
      |  ]
      |}""".stripMargin

  private val DumpResult: JsObject = Json.obj("windows" -> Json.arr(Json.obj("index" -> 0, "type" -> "Stage",
    "title" -> "Microtonalist", "nodes" -> Json.arr("Button \"Play\""))))

  /** An output stream that queues each line written to it, without its newline. */
  private final class LineQueue extends OutputStream {
    val lines: LinkedBlockingQueue[String] = LinkedBlockingQueue()

    private val line = ByteArrayOutputStream()

    override def write(b: Int): Unit = synchronized {
      if (b == '\n') {
        lines.put(line.toString(UTF_8))
        line.reset()
      } else {
        line.write(b)
      }
    }
  }
}
