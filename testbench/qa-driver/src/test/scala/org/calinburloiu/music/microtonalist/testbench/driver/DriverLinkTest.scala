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

package org.calinburloiu.music.microtonalist.testbench.driver

import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.*
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.{Dump, Idle}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import play.api.libs.json.Json

import java.io.{BufferedReader, ByteArrayOutputStream, InputStreamReader, OutputStreamWriter, PrintStream, Writer}
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{Channels, ServerSocketChannel, SocketChannel}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}

class DriverLinkTest extends AnyWordSpec with Matchers with Inside with BeforeAndAfterAll {

  private val TimeoutSeconds: Long = 10

  override protected def beforeAll(): Unit = HeadlessFx.start()

  override protected def afterAll(): Unit = HeadlessFx.closeAllWindows()

  /** Runs a blocking `body` off the test thread, failing the test if it takes longer than [[TimeoutSeconds]]. */
  private def within[T](body: => T): T = CompletableFuture.supplyAsync(() => body).get(TimeoutSeconds, TimeUnit.SECONDS)

  private def showStage(): Unit = HeadlessFx.fx {
    val stage = Stage()
    stage.setScene(Scene(StackPane(), 100, 100))
    stage.show()
  }

  /** The QA server's end of the link: listens on a socket in a fresh temporary directory. */
  private class Server {
    private val dir: Path = Files.createTempDirectory("qa")
    val socket: Path = dir.resolve("d.sock")
    private val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    serverChannel.bind(UnixDomainSocketAddress.of(socket))

    private var accepted: Option[SocketChannel] = None

    private def channel: SocketChannel = accepted.getOrElse {
      val newChannel = within(serverChannel.accept())
      accepted = Some(newChannel)
      newChannel
    }

    private lazy val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), UTF_8))
    private lazy val writer: Writer = OutputStreamWriter(Channels.newOutputStream(channel), UTF_8)

    /** Reads the next line from the driver, accepting its connection first if needed. */
    def readLine(): String = within(reader.readLine())

    /** Sends one line to the driver and decodes its answer. */
    def exchange(line: String): DriverResponse = {
      writer.write(line + "\n")
      writer.flush()
      Protocol.decodeResponse(readLine()).fold(error => fail(error), identity)
    }

    def exchange(envelope: Envelope): DriverResponse = exchange(Protocol.encode(envelope))

    def close(): Unit = {
      serverChannel.close()
      // Closing the connection ends the driver's link.
      accepted.foreach(_.close())
      Files.deleteIfExists(socket)
      Files.delete(dir)
    }
  }

  private def withServer(test: Server => Unit): Unit = {
    val server = Server()
    try {
      test(server)
    } finally {
      server.close()
    }
  }

  private def withConnectedDriver(test: Server => Unit): Unit = withServer { server =>
    showStage()
    QaDriverAgent.premain(server.socket.toString)
    Protocol.decodeHello(server.readLine()) shouldBe a[Right[?, ?]]
    test(server)
  }

  /** Captures `System.err` while `body` runs, and counts down `printed` on the first line written to it. */
  private def capturingStdErr(printed: CountDownLatch)(body: ByteArrayOutputStream => Unit): Unit = {
    val captured = ByteArrayOutputStream()
    val capturing = new PrintStream(captured, true, UTF_8) {
      override def println(line: String): Unit = {
        super.println(line)
        printed.countDown()
      }
    }
    val stdErr = System.err
    System.setErr(capturing)
    try {
      body(captured)
    } finally {
      System.setErr(stdErr)
    }
  }

  "DriverLink" should {
    "connect once a window shows and say hello" in withServer { server =>
      // Given
      HeadlessFx.closeAllWindows()

      // When
      QaDriverAgent.premain(server.socket.toString)
      showStage()

      // Then
      Protocol.decodeHello(server.readLine()) shouldBe Right(Hello(ProcessHandle.current.pid, GuiPlatform.Headless))
    }

    "answer a request with its id" in withConnectedDriver { server =>
      // When / Then
      server.exchange(Envelope(3, Idle)) shouldBe DriverResponse.Ok(3, Json.obj())
    }

    "answer an unreadable request with an error and keep serving" in withConnectedDriver { server =>
      // When / Then
      server.exchange("""{"id":4,"op":"explode"}""") shouldBe a[DriverResponse.Failed]
      server.exchange(Envelope(5, Idle)) shouldBe DriverResponse.Ok(5, Json.obj())
    }

    "answer a failed request with its message and keep serving" in withConnectedDriver { server =>
      // When
      val response = server.exchange(Envelope(6, Dump(Some(99))))

      // Then
      inside(response) { case DriverResponse.Failed(6, message) => message should include("99") }
      server.exchange(Envelope(7, Idle)) shouldBe DriverResponse.Ok(7, Json.obj())
    }

    "report on standard error when it can't connect" in {
      // Given
      showStage()
      val dir = Files.createTempDirectory("qa")
      val socket = dir.resolve("none.sock")
      val printed = CountDownLatch(1)

      try {
        capturingStdErr(printed) { captured =>
          // When
          QaDriverAgent.premain(socket.toString)

          // Then
          printed.await(TimeoutSeconds, TimeUnit.SECONDS) shouldBe true
          captured.toString(UTF_8) should include(s"qa-driver: the link to $socket failed")
        }
      } finally {
        Files.delete(dir)
      }
    }
  }
}
