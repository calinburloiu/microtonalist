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
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsObject, Json}

import java.io.IOException
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, CountDownLatch, ExecutionException, TimeUnit}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

class AppLauncherTest extends AnyWordSpec with Matchers {

  private val AwaitSeconds: Long = 5
  private val pid: Long = FakeProcessLauncher.FirstPid
  private val scriptedDump: JsObject = Json.obj("windows" -> Json.arr(Json.obj("title" -> "Microtonalist")))

  /** Answers each request with `result`. */
  private def ok(result: DriverRequest => JsObject): Envelope => String = { envelope =>
    Protocol.encode(DriverResponse.Ok(envelope.id, result(envelope.request)))
  }

  private abstract class Fixture(startupTimeout: FiniteDuration = 60.seconds) {
    val run: QaRun = QaRun.create(Files.createTempDirectory("qa"))
    val launcher: FakeProcessLauncher = FakeProcessLauncher()
    val jars: QaJars = QaJars(Path.of("/jars/app.jar"), Path.of("/jars/driver.jar"))
    val java: Path = Path.of("/jdk/bin/java")
    val composition: Path = run.dir.resolve("song.mtlist")
    val appLog: Path = run.dir.resolve("app.log")
    val appLauncher: AppLauncher = AppLauncher(run, launcher, jars, java, startupTimeout)

    /** The drivers that the started processes connected, in order. */
    val drivers: ArrayBuffer[FakeDriver] = ArrayBuffer()

    // By default, the app's process connects its driver as soon as it starts.
    launcher.newProcess = (spec, pid) => {
      drivers += FakeDriver(run.socket, pid)
      FakeProcess(spec, pid)
    }

    def driver: FakeDriver = drivers.head
  }

  private abstract class StartedFixture(ignoresTerminate: Boolean = false) extends Fixture {
    launcher.newProcess = (spec, pid) => {
      drivers += FakeDriver(run.socket, pid)
      FakeProcess(spec, pid, ignoresTerminate = ignoresTerminate)
    }
    val app: RunningApp = appLauncher.start(composition, GuiPlatform.Headless)
  }

  "AppLauncher.start" should {
    "start the app with the driver and the run's config" in new Fixture {
      // When
      appLauncher.start(composition, GuiPlatform.Headless)

      // Then
      launcher.specs.last.command shouldBe Seq(java.toString, "-Dglass.platform=Headless",
        s"-javaagent:${jars.driver}=${run.socket}", "-jar", jars.app.toString, composition.toString,
        run.dir.resolve("qa.conf").toString)
      launcher.specs.last.output shouldBe Some(appLog)
      Files.readString(run.dir.resolve("qa.conf")) shouldBe
        s"""core {\n  libraryBaseUrl = "${run.dir.toUri}"\n}\n"""
      run.dir.toUri.toString should endWith("/")
    }

    "answer once the driver says hello" in new Fixture {
      // When
      val app: RunningApp = appLauncher.start(composition, GuiPlatform.Headless)

      // Then
      app.process.pid shouldBe pid
      app.process shouldBe launcher.process(0)
      app.platform shouldBe GuiPlatform.Headless
    }

    "leave out the headless property on the desktop platform" in new Fixture {
      // When
      val app: RunningApp = appLauncher.start(composition, GuiPlatform.Desktop)

      // Then
      launcher.specs.last.command should not contain "-Dglass.platform=Headless"
      launcher.specs.last.command(1) shouldBe s"-javaagent:${jars.driver}=${run.socket}"
      app.platform shouldBe GuiPlatform.Desktop
    }

    "remove a stale socket file before listening" in new Fixture {
      // Given
      Files.writeString(run.socket, "stale")

      // When
      val app: RunningApp = appLauncher.start(composition, GuiPlatform.Headless)

      // Then
      app.process.pid shouldBe pid
    }

    "fail pointing at app.log when the app exits before its window shows" in new Fixture {
      // Given
      launcher.newProcess = (spec, pid) => {
        val process = FakeProcess(spec, pid)
        process.exit(1)
        process
      }

      // When / Then
      (the[QaException] thrownBy appLauncher.start(composition, GuiPlatform.Headless)).getMessage shouldBe
        s"the app exited with code 1 before showing a window; see $appLog"
    }

    "refuse a driver that names another PID" in new Fixture {
      // Given
      val wrongDriverPid: Long = 1
      @volatile var wrongDriver: Option[FakeDriver] = None
      launcher.newProcess = (spec, pid) => {
        val wrong = FakeDriver(run.socket, wrongDriverPid)
        wrongDriver = Some(wrong)
        // The right driver connects only once the server has closed the wrong one's connection.
        Thread.ofVirtual().start { () =>
          wrong.disconnected.join()
          FakeDriver(run.socket, pid)
        }
        FakeProcess(spec, pid)
      }

      // When
      val app: RunningApp = appLauncher.start(composition, GuiPlatform.Headless)

      // Then
      app.process.pid shouldBe pid
      wrongDriver.get.disconnected.isDone shouldBe true
      app.request(Windows) shouldBe Json.obj()
      wrongDriver.get.requests shouldBe empty
    }

    "stop the app when no window shows in time" in new Fixture(startupTimeout = 200.millis) {
      // Given
      launcher.newProcess = (spec, pid) => FakeProcess(spec, pid)

      // When / Then
      (the[QaException] thrownBy appLauncher.start(composition, GuiPlatform.Headless)).getMessage shouldBe
        s"no window after 200 ms; see $appLog"
      launcher.process(0).terminateCount shouldBe 1
      launcher.process(0).isAlive shouldBe false
    }

    "say so when the app's JVM can't be started" in new Fixture {
      // Given
      launcher.startFailure = _ => Some(IOException("Cannot run program \"/jdk/bin/java\""))

      // When / Then
      (the[QaException] thrownBy appLauncher.start(composition, GuiPlatform.Headless)).getMessage shouldBe
        "can't start the app: Cannot run program \"/jdk/bin/java\""
    }
  }

  "RunningApp" should {
    "pass a request to the driver and return its result" in new StartedFixture {
      // Given
      driver.script = ok(_ => scriptedDump)

      // When / Then
      app.request(Dump(None)) shouldBe scriptedDump
      driver.requests shouldBe Seq(Dump(None))
    }

    "read an answer of any length" in new StartedFixture {
      // Given
      val png: String = "A" * (8 * 1024 * 1024)
      driver.script = ok(_ => Json.obj("png" -> png))

      // When / Then
      (app.request(Snapshot(None, None, 4.0)) \ "png").as[String] shouldBe png
    }

    "turn a driver failure into a QaException with its message" in new StartedFixture {
      // Given
      driver.script = envelope => Protocol.encode(DriverResponse.Failed(envelope.id, "no window 9; there are 1"))

      // When / Then
      (the[QaException] thrownBy app.request(Dump(Some(9)))).getMessage shouldBe "no window 9; there are 1"
    }

    "point at app.log once the app has exited" in new StartedFixture {
      // Given
      driver.close()
      launcher.process(0).exit(1)

      // When / Then
      (the[QaException] thrownBy app.request(Windows)).getMessage shouldBe
        s"the app exited with code 1; see $appLog"
    }

    "stop with SIGTERM, letting the app exit" in new StartedFixture {
      // When
      val result: StopResult = app.stop()

      // Then
      result shouldBe StopResult(FakeProcess.TerminatedExitCode, killed = false)
      launcher.process(0).killCount shouldBe 0
      driver.disconnected.get(AwaitSeconds, TimeUnit.SECONDS)
    }

    "stop with SIGTERM, then SIGKILL after the timeout" in new StartedFixture(ignoresTerminate = true) {
      // When
      val result: StopResult = app.stop(200.millis)

      // Then
      result.killed shouldBe true
      result.exitCode shouldBe FakeProcess.KilledExitCode
      launcher.process(0).terminateCount shouldBe 1
      launcher.process(0).killCount shouldBe 1
    }

    "terminate without waiting" in new StartedFixture(ignoresTerminate = true) {
      // When
      app.terminate()

      // Then
      launcher.process(0).terminateCount shouldBe 1
      launcher.process(0).isAlive shouldBe true
      driver.disconnected.get(AwaitSeconds, TimeUnit.SECONDS)
    }
  }

  /** A [[DriverClient]] over a connection that a [[FakeDriver]] made. */
  private abstract class ClientFixture(timeout: FiniteDuration = 30.seconds, helloLine: Option[String] = None) {
    val socket: Path = Files.createTempDirectory("qa").resolve("d.sock")
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(socket))
    val driver: FakeDriver = FakeDriver(socket, pid, GuiPlatform.Desktop, helloLine)
    val client: DriverClient = DriverClient(server.accept(), timeout)
    server.close()
  }

  "DriverClient" should {
    "read the driver's hello" in new ClientFixture {
      // When / Then
      client.hello() shouldBe Hello(pid, GuiPlatform.Desktop)
    }

    "number its requests" in new ClientFixture {
      // Given
      client.hello()
      driver.script = envelope => Protocol.encode(DriverResponse.Ok(envelope.id, Json.obj("id" -> envelope.id)))

      // When / Then
      Seq(client.request(Idle), client.request(Idle)) shouldBe Seq(Json.obj("id" -> 1), Json.obj("id" -> 2))
    }

    "refuse an unreadable hello" in new ClientFixture(helloLine = Some("""{"pid":7}""")) {
      // When / Then
      (the[QaException] thrownBy client.hello()).getMessage should startWith(
        "the driver's hello is unreadable: platform: ")
    }

    "refuse an unreadable answer" in new ClientFixture {
      // Given
      client.hello()
      driver.script = _ => "not JSON"

      // When / Then
      (the[QaException] thrownBy client.request(Idle)).getMessage shouldBe
        "the driver's answer is unreadable: not JSON"
    }

    "refuse an answer to another request" in new ClientFixture {
      // Given
      client.hello()
      driver.script = _ => Protocol.encode(DriverResponse.Failed(Protocol.UnreadableId, "unreadable request"))

      // When / Then
      (the[QaException] thrownBy client.request(Idle)).getMessage shouldBe
        "the driver answered request -1 instead of 1"
    }

    "refuse a result for another request" in new ClientFixture {
      // Given
      client.hello()
      driver.script = _ => Protocol.encode(DriverResponse.Ok(99, Json.obj()))

      // When / Then
      (the[QaException] thrownBy client.request(Idle)).getMessage shouldBe
        "the driver answered request 99 instead of 1"
    }

    "unblock a request waiting for its answer when closed" in new ClientFixture {
      // Given
      client.hello()
      val requested: CountDownLatch = CountDownLatch(1)
      val release: CountDownLatch = CountDownLatch(1)
      driver.script = envelope => {
        requested.countDown()
        release.await()
        Protocol.encode(DriverResponse.Ok(envelope.id, Json.obj()))
      }
      val answer: CompletableFuture[JsObject] = CompletableFuture.supplyAsync(() => client.request(Idle))
      requested.await(AwaitSeconds, TimeUnit.SECONDS) shouldBe true

      // When
      client.close()

      // Then
      val failure = the[ExecutionException] thrownBy answer.get(AwaitSeconds, TimeUnit.SECONDS)
      failure.getCause shouldBe an[IOException]
      release.countDown()
    }

    "describe a timeout in whole seconds, or else in milliseconds" in {
      // When / Then
      Seq(60.seconds, 1500.millis).map(DriverClient.describe) shouldBe Seq("60 s", "1500 ms")
    }

    "give up on a driver that doesn't answer in time, closing the connection" in new ClientFixture(200.millis) {
      // Given
      client.hello()
      val release: CountDownLatch = CountDownLatch(1)
      driver.script = envelope => {
        release.await()
        Protocol.encode(DriverResponse.Ok(envelope.id, Json.obj()))
      }

      // When / Then
      (the[QaException] thrownBy client.request(Idle)).getMessage shouldBe "the driver didn't answer in 200 ms"
      release.countDown()
      driver.disconnected.get(AwaitSeconds, TimeUnit.SECONDS)
      an[IOException] should be thrownBy client.request(Idle)
    }

    "fail with an IOException once the driver closed the connection" in new ClientFixture {
      // Given
      client.hello()
      driver.close()

      // When / Then
      an[IOException] should be thrownBy client.request(Idle)
    }
  }
}
